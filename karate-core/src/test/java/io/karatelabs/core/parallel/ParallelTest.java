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
package io.karatelabs.core.parallel;

import io.karatelabs.core.MockServer;
import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parallel execution to verify thread safety and isolation.
 * <p>
 * These tests verify:
 * - callSingle returns same instance across threads
 * - callonce evaluated once per feature
 * - Config functions available in all parallel scenarios
 * - karate-base.js functions shared across threads
 * - Variables don't leak between parallel scenarios
 * - Dynamic outlines work correctly in parallel
 * - HTTP calls work in parallel with configure headers/cookies
 * - Java interop works across parallel scenarios
 */
class ParallelTest {

    private static MockServer mockServer;

    @BeforeAll
    static void startMockServer() {
        mockServer = MockServer.feature("classpath:io/karatelabs/core/parallel/http/http-mock.feature").start();
    }

    @AfterAll
    static void stopMockServer() {
        if (mockServer != null) {
            mockServer.stopAndWait();
        }
    }

    /**
     * Test that dynamic scenario outlines work correctly in parallel.
     * Each outline example runs in its own thread but shares the @setup data.
     */
    @Test
    void testDynamicOutlineParallel() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/outline-parallel.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(4);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
        // Should have 4 scenarios (from the @setup data)
        assertEquals(4, result.getScenarioCount());
    }

    /**
     * Test that callSingle returns the same instance across parallel threads.
     * This is critical for thread safety - the singleton pattern must work.
     */
    @Test
    void testCallSingleThreadSafety() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/callsingle-parallel.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(5);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
    }

    /**
     * Test that callonce in Background is evaluated once and shared across scenarios.
     */
    @Test
    void testCallonceParallel() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/callonce-parallel.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(3);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
    }

    /**
     * Test that variables are isolated between parallel scenarios.
     * Each scenario should have its own variable scope.
     */
    @Test
    void testVariableIsolation() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/variable-isolation.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(4);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
    }

    /**
     * Test that config functions work correctly across parallel threads.
     */
    @Test
    void testConfigFunctionsParallel() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/config-functions-parallel.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(4);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
    }

    /**
     * Test that karate-base.js functions work correctly across parallel threads.
     */
    @Test
    void testKarateBaseParallel() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/karate-base-parallel.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(4);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
    }

    /**
     * Test running multiple features in parallel.
     */
    @Test
    void testMultipleFeaturesParallel() {
        SuiteResult result = Runner.path(
                        "classpath:io/karatelabs/core/parallel/feature1.feature",
                        "classpath:io/karatelabs/core/parallel/feature2.feature",
                        "classpath:io/karatelabs/core/parallel/feature3.feature"
                )
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(3);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
        assertEquals(3, result.getFeatureCount());
    }

    /**
     * Test karate.config API returns configure settings.
     */
    @Test
    void testKarateConfigApi() {
        SuiteResult result = Runner.path("classpath:io/karatelabs/core/parallel/config-test.feature")
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(1);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
    }

    /**
     * Test that callonce is feature-scoped, not suite-scoped.
     * Two features running in parallel, each with callonce to the same helper,
     * should each execute the helper independently (not share cached results).
     */
    @Test
    void testCallonceFeatureIsolation() {
        // Reset the counter before test
        CallOnceCounter.reset();

        // Run two features in parallel, each using callonce to the same helper
        SuiteResult result = Runner.path(
                        "classpath:io/karatelabs/core/parallel/callonce-feature-a.feature",
                        "classpath:io/karatelabs/core/parallel/callonce-feature-b.feature"
                )
                .configDir("classpath:io/karatelabs/core/parallel")
                .outputConsoleSummary(false)
                .parallel(2);

        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));

        // Each feature should have executed the helper once (feature-scoped isolation)
        // If callonce were suite-scoped, counter would be 1 (shared cache across features)
        int count = CallOnceCounter.get();
        assertEquals(2, count,
                "callonce should be feature-scoped: expected 2 executions (one per feature), but got " + count);
    }

    /**
     * Comprehensive parallel HTTP test combining:
     * - Multiple scenarios with HTTP calls
     * - Dynamic scenario outline with HTTP
     * - configure headers (from callonce)
     * - configure cookies (set in feature)
     * - configure afterScenario hook
     * - callonce with Java.type
     * - callSingle from config with Java interop
     * - karate-base.js functions
     * - call to another feature within outline
     * <p>
     * This single test covers the functionality of V1's:
     * - ParallelTest.testParallel
     * - ParallelOutlineTest.testParallelOutline
     * - HelloTest.testParallel
     */
    @Test
    void testHttpParallel() {
        SuiteResult result = Runner.path(
                        "classpath:io/karatelabs/core/parallel/http/http-parallel.feature",
                        "classpath:io/karatelabs/core/parallel/http/http-outline.feature"
                )
                .configDir("classpath:io/karatelabs/core/parallel/http")
                .systemProperty("server.port", mockServer.getPort() + "")
                .outputConsoleSummary(false)
                .parallel(4);
        assertEquals(0, result.getScenarioFailedCount(), String.join("\n", result.getErrors()));
        // 3 scenarios from http-parallel + 3 from outline = 6 total
        assertEquals(6, result.getScenarioCount());
        assertEquals(2, result.getFeatureCount());
    }

}
