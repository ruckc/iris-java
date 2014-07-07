/*
 * Copyright © 2014 Project Iris. All rights reserved.
 *
 * The current language binding is an official support library of the Iris cloud messaging framework, and as such, the same licensing terms apply.
 * For details please see http://iris.karalabe.com/downloads#License
 */

package com.karalabe.iris;

import com.carrotsearch.junitbenchmarks.AbstractBenchmark;
import org.junit.Test;

@SuppressWarnings({"resource", "JUnitTestNG", "ProhibitedExceptionDeclared", "UnqualifiedStaticUsage"})
public class HandshakeTest extends AbstractBenchmark {
    @Test public void connection() throws Exception {
        try (final Connection ignored = Iris.connect(TestConfig.RELAY_PORT)) {}
    }

    @Test public void service() throws Exception {
        try (final Service ignored = Iris.register(TestConfig.RELAY_PORT, TestConfig.CLUSTER_NAME, new ServiceHandler() { })) {}
    }
}