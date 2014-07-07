/*
 * Copyright © 2014 Project Iris. All rights reserved.
 *
 * The current language binding is an official support library of the Iris cloud messaging framework, and as such, the same licensing terms apply.
 * For details please see http://iris.karalabe.com/downloads#License
 */

package com.karalabe.iris.protocol;

import com.karalabe.iris.ProtocolException;
import com.karalabe.iris.ServiceHandler;
import com.karalabe.iris.ServiceLimits;
import com.karalabe.iris.common.BoundedThreadPool;
import com.karalabe.iris.common.BoundedThreadPool.Terminate;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

public class RequestExecutor extends ExecutorBase {
    static class Result {
        boolean timeout;
        byte[]  reply;
        String  error;
    }

    private final ServiceHandler    handler; // Callback handler for processing inbound requests
    private final BoundedThreadPool workers; // Thread pool for limiting the concurrent processing

    private final LongAdder         nextId  = new LongAdder(); // Unique identifier for the next request
    private final Map<Long, Result> pending = new ConcurrentHashMap<>(); // Result objects for pending requests

    public RequestExecutor(final ProtocolBase protocol, @Nullable final ServiceHandler handler, final ServiceLimits limits) {
        super(protocol);

        this.handler = handler;
        this.workers = new BoundedThreadPool(limits.requestThreads, limits.requestMemory);
    }

    public byte[] request(final String cluster, byte[] request, long timeoutMillis) throws InterruptedException, TimeoutException {
        // Fetch a unique ID for the request
        nextId.increment();
        final long id = nextId.longValue();

        // Create a temporary object to store the reply
        final Result result = new Result();
        pending.put(id, result);

        try {
            synchronized (result) {
                // Send the request
                protocol.send(OpCode.REQUEST, () -> {
                    protocol.sendVarint(id);
                    protocol.sendString(cluster);
                    protocol.sendBinary(request);
                    protocol.sendVarint(timeoutMillis);
                });
                result.wait(); // Wait until a reply arrives
            }

            if (result.timeout) {
                throw new TimeoutException("Request timed out!");
            } else if (result.error != null) {
                throw new ProtocolException(result.error);
            } else {
                return result.reply;
            }
        } finally {
            pending.remove(id);
        }
    }

    public void reply(final long id, @Nullable final byte[] response, @Nullable final String error) {
        protocol.send(OpCode.REPLY, () -> {
            protocol.sendVarint(id);
            protocol.sendBoolean(error == null);
            if (error == null) {
                protocol.sendBinary(response);
            } else {
                protocol.sendString(error);
            }
        });
    }

    public void handleRequest() {
        final long id = protocol.receiveVarint();
        final byte[] request = protocol.receiveBinary();
        final int timeout = (int) protocol.receiveVarint();

        workers.schedule(request.length, timeout, () -> {
            byte[] response = null;
            String error = null;

            // Execute the request and flatten any error
            try {
                response = handler.handleRequest(request);
            } catch (Exception e) {
                error = e.toString();
            }
            reply(id, response, error);
        });
    }

    public void handleReply() {
        // Read the request id and fetch the pending result
        final long id = protocol.receiveVarint();
        final Result result = pending.get(id);
        if (result == null) { return; } // Already dead? Thread got interrupted!

        // Read the rest of the response and fill the result accordingly
        result.timeout = protocol.receiveBoolean();
        if (!result.timeout) {
            final boolean success = protocol.receiveBoolean();
            if (success) {
                result.reply = protocol.receiveBinary();
            } else {
                result.error = protocol.receiveString();
            }
        }
        // Wake the origin thread
        synchronized (result) {
            result.notify();
        }
    }

    @Override public void close() throws InterruptedException {
        workers.terminate(Terminate.NOW);
    }
}
