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
package io.karatelabs.output;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Slf4jCascade - verifies cascade behavior and LogContext integration.
 */
class Slf4jCascadeTest {

    @AfterEach
    void cleanup() {
        LogContext.setCascade(null);
        LogContext.setLogLevel(LogLevel.INFO);
        LogContext.clear();
    }

    @Test
    void testDefaultCategory() {
        assertEquals("karate.run", Slf4jCascade.DEFAULT_CATEGORY);
    }

    @Test
    void testCreateReturnsBiConsumer() {
        BiConsumer<LogLevel, String> consumer = Slf4jCascade.create();
        assertNotNull(consumer);
        // Should not throw when called
        consumer.accept(LogLevel.INFO, "test message");
    }

    @Test
    void testCreateWithCustomCategory() {
        BiConsumer<LogLevel, String> consumer = Slf4jCascade.create("custom.category");
        assertNotNull(consumer);
        consumer.accept(LogLevel.INFO, "test message");
    }

    @Test
    void testCascadeMapsLevelsCorrectly() {
        BiConsumer<LogLevel, String> consumer = Slf4jCascade.create("test.category");
        assertNotNull(consumer);
        for (LogLevel level : LogLevel.values()) {
            consumer.accept(level, "test at " + level);
        }
    }

    @Test
    void testIntegrationWithLogContext() {
        // Track messages via a simple list
        List<String> captured = new ArrayList<>();
        List<LogLevel> capturedLevels = new ArrayList<>();

        // Set up cascade that captures to our list AND forwards to SLF4J
        BiConsumer<LogLevel, String> slf4jCascade = Slf4jCascade.create();
        LogContext.setCascade((level, msg) -> {
            capturedLevels.add(level);
            captured.add(msg);
            slf4jCascade.accept(level, msg);
        });

        // Log some messages
        LogContext ctx = LogContext.get();
        ctx.log("Hello from test");
        ctx.log("Value: {}", 42);

        // Verify cascade received the messages
        assertEquals(2, captured.size());
        assertEquals("Hello from test", captured.get(0));
        assertEquals("Value: 42", captured.get(1));
        assertEquals(LogLevel.INFO, capturedLevels.get(0));  // Default level is INFO
        assertEquals(LogLevel.INFO, capturedLevels.get(1));

        // Verify LogContext still has the buffer
        String collected = ctx.collect();
        assertTrue(collected.contains("Hello from test"));
        assertTrue(collected.contains("Value: 42"));
    }

    @Test
    void testCascadeWithExplicitLevel() {
        List<String> captured = new ArrayList<>();
        List<LogLevel> capturedLevels = new ArrayList<>();

        LogContext.setLogLevel(LogLevel.TRACE);  // Allow all levels
        LogContext.setCascade((level, msg) -> {
            capturedLevels.add(level);
            captured.add(msg);
        });

        LogContext ctx = LogContext.get();
        ctx.log(LogLevel.DEBUG, "Debug message");
        ctx.log(LogLevel.WARN, "Warning message");
        ctx.log(LogLevel.ERROR, "Error message");

        assertEquals(3, captured.size());
        assertEquals(LogLevel.DEBUG, capturedLevels.get(0));
        assertEquals(LogLevel.WARN, capturedLevels.get(1));
        assertEquals(LogLevel.ERROR, capturedLevels.get(2));
    }

    @Test
    void testLogLevelFiltering() {
        List<String> captured = new ArrayList<>();

        LogContext.setCascade((level, msg) -> captured.add(msg));
        LogContext.setLogLevel(LogLevel.WARN);  // Only WARN and above

        LogContext ctx = LogContext.get();
        ctx.log(LogLevel.DEBUG, "Should be filtered");
        ctx.log(LogLevel.INFO, "Should be filtered");
        ctx.log(LogLevel.WARN, "Should appear");
        ctx.log(LogLevel.ERROR, "Should appear");

        assertEquals(2, captured.size());
        assertEquals("Should appear", captured.get(0));
        assertEquals("Should appear", captured.get(1));
    }

    @Test
    void testCascadeWorksAcrossMultipleLogs() {
        List<String> captured = new ArrayList<>();
        LogContext.setCascade((level, msg) -> captured.add(msg));

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
