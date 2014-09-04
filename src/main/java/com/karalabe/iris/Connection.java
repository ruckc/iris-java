// Copyright (c) 2014 Project Iris. All rights reserved.
//
// The current language binding is an official support library of the Iris
// cloud messaging framework, and as such, the same licensing terms apply.
// For details please see http://iris.karalabe.com/downloads#License
package com.karalabe.iris;

import com.karalabe.iris.exceptions.RemoteException;
import com.karalabe.iris.exceptions.TimeoutException;
import com.karalabe.iris.protocol.RelayProtocol;
import com.karalabe.iris.schemes.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/*
 * Message relay between the local app and the local iris node.
 **/
public class Connection implements AutoCloseable {
    private final RelayProtocol protocol;
    private final Thread        runner;

    // Communication pattern implementers
    private final BroadcastScheme broadcaster;
    private final RequestScheme   requester;
    private final PublishScheme   subscriber;
    private final TunnelScheme    tunneler;

    // Connects to the Iris network as a simple client.
    Connection(int port, @NotNull String cluster, @Nullable ServiceHandler handler, @Nullable ServiceLimits limits) throws IOException {
        // Load the default service limits if none specified
        if (limits == null) { limits = new ServiceLimits(); }

        protocol = new RelayProtocol(port, cluster);

        // Create the individual message pattern implementations
        broadcaster = new BroadcastScheme(protocol, handler, limits);
        requester = new RequestScheme(protocol, handler, limits);
        subscriber = new PublishScheme(protocol);
        tunneler = new TunnelScheme(protocol, handler);

        // Start processing inbound network packets
        runner = new Thread(() -> protocol.process(handler, broadcaster, requester, subscriber, tunneler));
        runner.start();
    }

    // Broadcasts a message to all members of a cluster. No guarantees are made that
    // all recipients receive the message (best effort).
    //
    // The call blocks until the message is forwarded to the local Iris node.
    public void broadcast(@NotNull final String cluster, @NotNull final byte[] message) throws IOException {
        Validators.validateRemoteClusterName(cluster);
        broadcaster.broadcast(cluster, message);
    }

    // Executes a synchronous request to be serviced by a member of the specified
    // cluster, load-balanced between all participant, returning the received reply.
    //
    // The timeout unit is in milliseconds. Anything lower will fail with an error.
    public byte[] request(@NotNull final String cluster, @NotNull final byte[] request, final long timeout) throws IOException, InterruptedException, RemoteException, TimeoutException {
        Validators.validateRemoteClusterName(cluster);
        return requester.request(cluster, request, timeout);
    }

    // Subscribes to a topic using handler as the callback for arriving events.
    //
    // The method blocks until the subscription is forwarded to the relay. There
    // might be a small delay between subscription completion and start of event
    // delivery. This is caused by subscription propagation through the network.
    public void subscribe(@NotNull final String topic, @NotNull final TopicHandler handler) throws IOException {
        subscribe(topic, handler, null);
    }

    // Subscribes to a topic using handler as the callback for arriving events,
    // and additionally sets some limits on the inbound event processing.
    //
    // The method blocks until the subscription is forwarded to the relay. There
    // might be a small delay between subscription completion and start of event
    // delivery. This is caused by subscription propagation through the network.
    public void subscribe(@NotNull final String topic, @NotNull final TopicHandler handler, @Nullable TopicLimits limits) throws IOException {
        Validators.validateTopicName(topic);
        if (limits == null) { limits = new TopicLimits(); }
        subscriber.subscribe(topic, handler, limits);
    }

    // Publishes an event asynchronously to topic. No guarantees are made that all
    // subscribers receive the message (best effort).
    //
    // The method blocks until the message is forwarded to the local Iris node.
    public void publish(@NotNull final String topic, @NotNull final byte[] event) throws IOException {
        Validators.validateTopicName(topic);
        subscriber.publish(topic, event);
    }

    // Unsubscribes from topic, receiving no more event notifications for it.
    //
    // The method blocks until the unsubscription is forwarded to the local Iris node.
    public void unsubscribe(@NotNull final String topic) throws IOException {
        Validators.validateTopicName(topic);
        subscriber.unsubscribe(topic);
    }

    // Opens a direct tunnel to a member of a remote cluster, allowing pairwise-
    // exclusive, order-guaranteed and throttled message passing between them.
    //
    // The method blocks until the newly created tunnel is set up, or the time
    // limit is reached.
    //
    // The timeout unit is in milliseconds. Anything lower will fail with an error.
    public Tunnel tunnel(@NotNull final String cluster, final long timeout) throws IOException, TimeoutException, InterruptedException {
        Validators.validateRemoteClusterName(cluster);
        return new Tunnel(tunneler.tunnel(cluster, timeout));
    }

    // Gracefully terminates the connection removing all subscriptions and closing
    // all active tunnels.
    //
    // The call blocks until the connection tear-down is confirmed by the Iris node.
    @Override public void close() throws IOException, InterruptedException {
        // Terminate the relay connection
        if (runner != null) {
            protocol.sendClose();
            runner.join();
        }
        // Tear down the individual scheme implementations
        tunneler.close();
        subscriber.close();
        requester.close();
        broadcaster.close();
    }
}
