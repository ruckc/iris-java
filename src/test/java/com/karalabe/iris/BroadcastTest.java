// Copyright (c) 2014 Project Iris. All rights reserved.
//
// The current language binding is an official support library of the Iris
// cloud messaging framework, and as such, the same licensing terms apply.
// For details please see http://iris.karalabe.com/downloads#License
package com.karalabe.iris;

import com.carrotsearch.junitbenchmarks.AbstractBenchmark;
import com.carrotsearch.junitbenchmarks.BenchmarkOptions;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("JUnitTestNG")
public class BroadcastTest extends AbstractBenchmark {
    // Service handler for the broadcast tests.
    static class BroadcastTestHandler implements ServiceHandler {
        final Set<String> arrived = Collections.synchronizedSet(new HashSet<>());
        Connection connection;
        Semaphore  pending;

        @Override public void init(@NotNull final Connection connection) {
            this.connection = connection;
        }

        @Override public void handleBroadcast(@NotNull final byte[] message) {
            arrived.add(new String(message, StandardCharsets.UTF_8));
            pending.release(1);
        }
    }

    // Tests multiple concurrent client and service broadcasts.
    @BenchmarkOptions(benchmarkRounds = 5, warmupRounds = 10)
    @Test public void concurrentBroadcasts() throws Exception {
        final int CLIENT_COUNT = 25, SERVER_COUNT = 25, MESSAGE_COUNT = 25;

        final Collection<Thread> workers = new ArrayList<>(CLIENT_COUNT + SERVER_COUNT);
        final CyclicBarrier barrier = new CyclicBarrier(CLIENT_COUNT + SERVER_COUNT + 1);
        final List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        // Start up the concurrent broadcasting clients
        for (int i = 0; i < CLIENT_COUNT; i++) {
            final int client = i;
            final Thread worker = new Thread(() -> {
                try (final Connection conn = Iris.connect(Config.RELAY_PORT)) {
                    // Wait till all clients and servers connect
                    barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);

                    // Send all the client broadcasts
                    for (int j = 0; j < MESSAGE_COUNT; j++) {
                        final String message = String.format("client #%d, broadcast %d", client, j);
                        final byte[] messageBlob = message.getBytes(StandardCharsets.UTF_8);

                        conn.broadcast(Config.CLUSTER_NAME, messageBlob);
                    }
                    barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);
                    barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);
                }
                catch (Exception e) {
                    errors.add(e);
                }
            });
            worker.start();
            workers.add(worker);
        }
        // Start up the concurrent broadcast services
        for (int i = 0; i < SERVER_COUNT; i++) {
            final int server = i;
            final Thread worker = new Thread(() -> {
                final BroadcastTestHandler handler = new BroadcastTestHandler();

                try (final Service ignored = Iris.register(Config.RELAY_PORT, Config.CLUSTER_NAME, handler)) {
                    // Wait till all clients and servers connect
                    handler.pending = new Semaphore((CLIENT_COUNT + SERVER_COUNT) * MESSAGE_COUNT);
                    handler.pending.acquire((CLIENT_COUNT + SERVER_COUNT) * MESSAGE_COUNT);

                    barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);

                    // Send all the service broadcasts
                    for (int j = 0; j < MESSAGE_COUNT; j++) {
                        final String message = String.format("server #%d, broadcast %d", server, j);
                        final byte[] messageBlob = message.getBytes(StandardCharsets.UTF_8);

                        handler.connection.broadcast(Config.CLUSTER_NAME, messageBlob);
                    }
                    barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);

                    // Wait for all broadcasts to arrive
                    handler.pending.acquire((CLIENT_COUNT + SERVER_COUNT) * MESSAGE_COUNT);

                    for (int j = 0; j < CLIENT_COUNT; j++) {
                        for (int k = 0; k < MESSAGE_COUNT; k++) {
                            final String message = String.format("client #%d, broadcast %d", j, k);
                            Assert.assertTrue(handler.arrived.contains(message));
                        }
                    }
                    for (int j = 0; j < SERVER_COUNT; j++) {
                        for (int k = 0; k < MESSAGE_COUNT; k++) {
                            final String message = String.format("server #%d, broadcast %d", j, k);
                            Assert.assertTrue(handler.arrived.contains(message));
                        }
                    }
                    barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);
                }
                catch (Exception e) {
                    errors.add(e);
                }
            });
            worker.start();
            workers.add(worker);
        }
        // Schedule the parallel operations
        try {
            barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);
            Assert.assertTrue(errors.isEmpty());

            barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);
            Assert.assertTrue(errors.isEmpty());

            barrier.await(Config.PHASE_TIMEOUT, TimeUnit.SECONDS);
            Assert.assertTrue(errors.isEmpty());
        }
        finally {
            for (Thread worker : workers) {
                worker.join();
            }
        }
    }

    // Service handler for the thread limited broadcast tests.
    static class BroadcastLimitTestHandler implements ServiceHandler {
        final Set<String> arrived = Collections.synchronizedSet(new HashSet<>());
        Connection connection;
        int        sleep;

        @Override public void init(@NotNull final Connection connection) {
            this.connection = connection;
        }

        @Override public void handleBroadcast(@NotNull final byte[] message) {
            try {
                Thread.sleep(sleep);
                arrived.add(new String(message, StandardCharsets.UTF_8));
            }
            catch (InterruptedException ignored) { }
        }
    }

    // Tests the broadcast thread limitation.
    @BenchmarkOptions(benchmarkRounds = 5, warmupRounds = 10)
    @Test public void threadLimiting() throws Exception {
        final int MESSAGE_COUNT = 4, SLEEP = 100;

        // Create the service handler and limiter
        final BroadcastLimitTestHandler handler = new BroadcastLimitTestHandler();
        handler.sleep = SLEEP;

        final ServiceLimits limits = new ServiceLimits();
        limits.broadcastThreads = 1;

        try (final Service ignored = Iris.register(Config.RELAY_PORT, Config.CLUSTER_NAME, handler, limits)) {
            // Send a few broadcasts
            for (int j = 0; j < MESSAGE_COUNT; j++) {
                handler.connection.broadcast(Config.CLUSTER_NAME, new byte[]{(byte) j});
            }
            // Wait for half time and verify that only half was processed
            Thread.sleep(((MESSAGE_COUNT / 2) * SLEEP) + (SLEEP / 2));
            Assert.assertEquals(MESSAGE_COUNT / 2, handler.arrived.size());
        }
    }

    // Tests the broadcast memory limitation.
    @BenchmarkOptions(benchmarkRounds = 5, warmupRounds = 10)
    @Test public void memoryLimiting() throws Exception {
        // Create the service handler and limiter
        final BroadcastTestHandler handler = new BroadcastTestHandler();
        handler.pending = new Semaphore(2);
        handler.pending.acquire(2);

        final ServiceLimits limits = new ServiceLimits();
        limits.broadcastMemory = 1;

        try (final Service ignored = Iris.register(Config.RELAY_PORT, Config.CLUSTER_NAME, handler, limits)) {
            // Check that a 1 byte broadcast passes
            handler.connection.broadcast(Config.CLUSTER_NAME, new byte[]{0x00});
            Assert.assertTrue(handler.pending.tryAcquire(100, TimeUnit.MILLISECONDS));

            // Check that a 2 byte broadcast is dropped
            handler.connection.broadcast(Config.CLUSTER_NAME, new byte[]{0x00, 0x00});
            Assert.assertFalse(handler.pending.tryAcquire(100, TimeUnit.MILLISECONDS));
        }
    }
}
