/*
 * Copyright © 2014 Project Iris. All rights reserved.
 *
 * The current language binding is an official support library of the Iris cloud messaging framework, and as such, the same licensing terms apply.
 * For details please see http://iris.karalabe.com/downloads#License
 */

package com.karalabe.iris;

public class TopicLimits {
    public int eventThreads = 4 * Runtime.getRuntime().availableProcessors(); // Event handlers to execute concurrently
    public int eventMemory  = 64 * 1024 * 1024;                               // Memory allowance for pending events
}
