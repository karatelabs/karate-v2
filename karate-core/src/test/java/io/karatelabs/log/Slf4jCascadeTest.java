/*
 * The MIT License
 *
 * Copyright 2025 Karate Labs Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.karatelabs.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Slf4jCascade - verifies cascade behavior and LogContext integration.
 */
class Slf4jCascadeTest {

    @AfterEach
    void cleanup() {
        LogContext.setCascade(null);
        LogContext.clear();
    }

    @Test
    void testDefaultCategory() {
        assertEquals("karate.run", Slf4jCascade.DEFAULT_CATEGORY);
    }

    @Test
    void testCreateReturnsConsumer() {
        Consumer<String> consumer = Slf4jCascade.create();
        assertNotNull(consumer);
        // Should not throw when called
        consumer.accept("test message");
    }

    @Test
    void testCreateWithCustomCategory() {
        Consumer<String> consumer = Slf4jCascade.create("custom.category");
        assertNotNull(consumer);
        consumer.accept("test message");
    }

    @Test
    void testCreateWithLogLevel() {
        for (LogLevel level : LogLevel.values()) {
            Consumer<String> consumer = Slf4jCascade.create("test.category", level);
            assertNotNull(consumer);
            consumer.accept("test at " + level);
        }
    }

    @Test
    void testIntegrationWithLogContext() {
        // Track messages via a simple list
        List<String> captured = new ArrayList<>();

        // Set up cascade that captures to our list AND forwards to SLF4J
        Consumer<String> slf4jCascade = Slf4jCascade.create();
        LogContext.setCascade(msg -> {
            captured.add(msg);
            slf4jCascade.accept(msg);
        });

        // Log some messages
        LogContext ctx = LogContext.get();
        ctx.log("Hello from test");
        ctx.log("Value: {}", 42);

        // Verify cascade received the messages
        assertEquals(2, captured.size());
        assertEquals("Hello from test", captured.get(0));
        assertEquals("Value: 42", captured.get(1));

        // Verify LogContext still has the buffer
        String collected = ctx.collect();
        assertTrue(collected.contains("Hello from test"));
        assertTrue(collected.contains("Value: 42"));
    }

    @Test
    void testCascadeWorksAcrossMultipleLogs() {
        List<String> captured = new ArrayList<>();
        LogContext.setCascade(captured::add);

        LogContext ctx = LogContext.get();
        ctx.log("First");
        ctx.log("Second");
        ctx.log("Third");

        assertEquals(3, captured.size());
        assertEquals("First", captured.get(0));
        assertEquals("Second", captured.get(1));
        assertEquals("Third", captured.get(2));
    }

}
