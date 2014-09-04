// Copyright (c) 2014 Project Iris. All rights reserved.
//
// The current language binding is an official support library of the Iris
// cloud messaging framework, and as such, the same licensing terms apply.
// For details please see http://iris.karalabe.com/downloads#License
package com.karalabe.iris.schemes;

import com.karalabe.iris.ServiceHandler;
import com.karalabe.iris.Tunnel;
import com.karalabe.iris.protocol.RelayProtocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// Implements the tunnel communication pattern.
public class TunnelScheme {
    private static class PendingBuild {
        boolean timeout;
        long    chunking;
    }

    private static final int DEFAULT_TUNNEL_BUFFER = 64 * 1024 * 1024; // Size of a tunnel's input buffer.

    private final RelayProtocol  protocol; // Network connection implementing the relay protocol
    private final ServiceHandler handler;  // Callback handler for processing inbound tunnels

    private final AtomicInteger           nextId  = new AtomicInteger();          // Unique identifier for the next tunnel
    private final Map<Long, PendingBuild> pending = new ConcurrentHashMap<>(128); // Result objects for pending tunnel
    private final Map<Long, TunnelBridge> active  = new ConcurrentHashMap<>(128); // Currently active tunnels

    // Constructs a tunnel scheme implementation.
    public TunnelScheme(final RelayProtocol protocol, final ServiceHandler handler) {
        this.protocol = protocol;
        this.handler = handler;
    }

    // Relays a tunnel construction request to the local Iris node, waits for a
    // reply or timeout and potentially returns a new tunnel.
    public TunnelBridge tunnel(final String cluster, final long timeout) throws IOException, InterruptedException, TimeoutException {
        // Fetch a unique ID for the tunnel
        final long id = nextId.addAndGet(1);

        // Create a temporary object to store the construction result
        final PendingBuild operation = new PendingBuild();
        pending.put(id, operation);

        try {
            // Create the potential tunnel (needs pre-creation due to activation race)
            final TunnelBridge bridge = new TunnelBridge(this, id, 0);
            active.put(id, bridge);

            // Send the construction request and wait for the reply
            synchronized (operation) {
                protocol.sendTunnelInit(id, cluster, timeout);
                operation.wait();
            }

            if (operation.timeout) {
                throw new TimeoutException("Tunnel construction timed out!");
            }
            bridge.chunkLimit = (int) operation.chunking;

            // Send the data allowance and return the active tunnel
            protocol.sendTunnelAllowance(id, DEFAULT_TUNNEL_BUFFER);
            return bridge;
        } catch (IOException | InterruptedException | TimeoutException e) {
            // Make sure the half initialized tunnel is discarded
            active.remove(id);
            throw e;
        } finally {
            // Make sure the pending operations are cleaned up
            pending.remove(id);
        }
    }

    // Opens a new local tunnel endpoint and binds it to the remote side.
    public void handleTunnelInit(final long initId, final long chunking) {
        final TunnelScheme self = this;

        new Thread(() -> {
            // Create the local tunnel endpoint
            final long id = nextId.addAndGet(1);

            TunnelBridge bridge = new TunnelBridge(self, id, (int) chunking);
            active.put(id, bridge);

            // Confirm the tunnel creation to the relay node and send the allowance
            try {
                protocol.sendTunnelConfirm(initId, id);
                protocol.sendTunnelAllowance(id, DEFAULT_TUNNEL_BUFFER);
                handler.handleTunnel(new Tunnel(bridge));
            } catch (IOException e) {
                active.remove(id);
                e.printStackTrace();
            }
        }).start();
    }

    // Forwards the tunnel construction result to the requested tunnel.
    public void handleTunnelResult(final long id, final long chunking) {
        // Fetch the pending construction result
        final PendingBuild operation = pending.get(id);
        if (operation == null) {
            // Already dead? Thread got interrupted!
            return;
        }
        // Fill in the operation result and wake teh origin thread
        synchronized (operation) {
            operation.timeout = (chunking == 0);
            operation.chunking = chunking;

            operation.notify();
        }
    }

    // Forwards a tunnel data allowance to the requested tunnel.
    public void handleTunnelAllowance(final long id, final int space) {
        final TunnelBridge bridge = active.get(id);
        if (bridge != null) {
            bridge.handleAllowance(space);
        }
    }

    // Forwards a message chunk transfer to the requested tunnel.
    public void handleTunnelTransfer(final long id, final int size, final byte[] chunk) {
        final TunnelBridge bridge = active.get(id);
        if (bridge != null) {
            bridge.handleTransfer(size, chunk);
        }
    }

    // Terminates a tunnel, stopping all data transfers.
    public void handleTunnelClose(final long id, final String reason) throws IOException {
        final TunnelBridge bridge = active.get(id);
        if (bridge != null) {
            bridge.handleClose(reason);
            active.remove(id);
        }
    }

    // Terminates the tunnel primitive.
    public void close() {
        // TODO: Nothing for now?
    }

    // Bridge between the scheme implementation and an API tunnel instance.
    public class TunnelBridge {
        private final long         id;       // Tunnel identifier for de/multiplexing
        private final TunnelScheme scheme;   // Protocol executor to the local relay

        // Chunking fields
        private int                   chunkLimit;    // Maximum length of a data payload
        private ByteArrayOutputStream chunkBuffer;   // Current message being assembled
        private int                   chunkCapacity; // Size of the message being assembled

        // Quality of service fields
        private final Queue<byte[]> itoaBuffer = new LinkedBlockingQueue<>(); // Iris to application message buffer

        private       long   atoiSpace = 0;            // Application to Iris space allowance
        private final Object atoiLock  = new Object(); // Protects the allowance and doubles as a signaller

        // Bookkeeping fields
        private final Object exitLock   = new Object(); // Tear-down synchronizer
        private       String exitStatus = null;         // Reason for termination, if not clean exit

        public TunnelBridge(final TunnelScheme scheme, final long id, final int chunking) {
            this.id = id;
            this.scheme = scheme;

            chunkLimit = chunking;
        }

        // Requests the closure of the tunnel.
        public void close() throws IOException, InterruptedException {
            synchronized (exitLock) {
                // Send the tear-down request if still alive and wait until a reply arrives
                if (exitStatus == null) {
                    protocol.sendTunnelClose(id);
                    exitLock.wait();
                }
                // If a failure occurred, throw an exception
                if (exitStatus.length() != 0) {
                    throw new RemoteException("Remote close failed: " + exitStatus);
                }
            }
        }

        // Sends a message over the tunnel to the remote pair, blocking until the local
        // Iris node receives the message or the operation times out.
        public void send(final byte[] message, final long timeout) throws IOException, TimeoutException, InterruptedException {
            // Calculate the deadline for the operation to finish
            final long deadline = System.nanoTime() + timeout * 10000000;

            // Split the original message into bounded chunks
            for (int pos = 0; pos < message.length; pos += chunkLimit) {
                final int end = Math.min(pos + chunkLimit, message.length);
                final int sizeOrCont = ((pos == 0) ? message.length : 0);
                final byte[] chunk = Arrays.copyOfRange(message, pos, end);

                // Wait for enough space allowance
                synchronized (atoiLock) {
                    while (atoiSpace < chunk.length) {
                        if (timeout == 0) {
                            atoiLock.wait();
                        } else {
                            final long sleep = (deadline - System.nanoTime()) / 10000000;
                            if (sleep <= 0) {
                                throw new TimeoutException("");
                            }
                            atoiLock.wait(sleep);
                        }
                    }
                    atoiSpace -= chunk.length;
                }
                protocol.sendTunnelTransfer(id, sizeOrCont, chunk);
            }
        }

        // Retrieves a message from the tunnel, blocking until one is available or the
        // operation times out.
        public byte[] receive(final long timeout) throws InterruptedException, TimeoutException {
            synchronized (itoaBuffer) {
                // Wait for a message to arrive if none is available
                if (itoaBuffer.isEmpty()) {
                    if (timeout > 0) {
                        itoaBuffer.wait(timeout);
                    } else {
                        itoaBuffer.wait();
                    }
                    if (itoaBuffer.isEmpty()) {
                        throw new TimeoutException("");
                    }
                }
                // Fetch the pending message and send a remote allowance
                final byte[] message = itoaBuffer.remove();
                new Thread(() -> {
                    try {
                        protocol.sendTunnelAllowance(id, message.length);
                    } catch (IOException ignored) {}
                }).start();
                return message;
            }
        }

        // Increases the available data allowance of the remote endpoint.
        public void handleAllowance(final int space) {
            synchronized (atoiLock) {
                atoiSpace += space;
                atoiLock.notify();
            }
        }

        // Adds the chunk to the currently building message and delivers it upon
        // completion. If a new message starts, the old is discarded.
        public void handleTransfer(final int size, final byte[] chunk) {
            // If a new message is arriving, dump anything stored before
            if (size != 0) {
                if (chunkBuffer != null) {
                    // A large transfer timed out, new started, grant the partials allowance
                    final int allowance = chunkBuffer.size();
                    new Thread(() -> {
                        try {
                            protocol.sendTunnelAllowance(id, allowance);
                        } catch (IOException ignored) {}
                    }).start();
                }
                chunkCapacity = size;
                chunkBuffer = new ByteArrayOutputStream(chunkCapacity);
            }
            // Append the new chunk and check completion
            try {
                chunkBuffer.write(chunk);
            } catch (IOException ignored) {}

            if (chunkBuffer.size() == chunkCapacity) {
                // Transfer the completed message into the inbound queue
                synchronized (itoaBuffer) {
                    itoaBuffer.add(chunkBuffer.toByteArray());
                    chunkBuffer = null;
                    chunkCapacity = 0;

                    // Wake up any thread waiting for inbound data
                    itoaBuffer.notify();
                }
            }
        }

        // Handles the graceful remote closure of the tunnel.
        public void handleClose(final String reason) {
            synchronized (exitLock) {
                exitStatus = reason;
                exitLock.notifyAll();
            }
        }
    }
}