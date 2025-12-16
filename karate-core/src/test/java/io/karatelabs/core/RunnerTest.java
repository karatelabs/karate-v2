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
package io.karatelabs.core;

import io.karatelabs.common.Resource;
import io.karatelabs.gherkin.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RunnerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        // Disable colors for cleaner test output
        Console.setColorsEnabled(false);
    }

    @Test
    void testRunnerWithSingleFeature() throws Exception {
        Path feature = tempDir.resolve("simple.feature");
        Files.writeString(feature, """
            Feature: Simple test

            Scenario: Basic assertion
            * def a = 1
            * match a == 1
            """);

        SuiteResult result = Runner.path(feature.toString())
                .outputDir(tempDir.resolve("reports"))
                .parallel(1);

        assertTrue(result.isPassed());
        assertEquals(1, result.getFeatureCount());
        assertEquals(1, result.getScenarioPassedCount());
    }

    @Test
    void testRunnerWithMultipleFeatures() throws Exception {
        Path feature1 = tempDir.resolve("first.feature");
        Files.writeString(feature1, """
            Feature: First
            Scenario: Test 1
            * def x = 1
            """);

        Path feature2 = tempDir.resolve("second.feature");
        Files.writeString(feature2, """
            Feature: Second
            Scenario: Test 2
            * def y = 2
            """);

        SuiteResult result = Runner.path(tempDir.toString())
                .outputDir(tempDir.resolve("reports"))
                .parallel(1);

        assertTrue(result.isPassed());
        assertEquals(2, result.getFeatureCount());
        assertEquals(2, result.getScenarioPassedCount());
    }

    @Test
    void testRunnerWithFailure() throws Exception {
        Path feature = tempDir.resolve("failing.feature");
        Files.writeString(feature, """
            Feature: Failing test

            Scenario: This will fail
            * def a = 1
            * match a == 999
            """);

        SuiteResult result = Runner.path(feature.toString())
                .outputDir(tempDir.resolve("reports"))
                .parallel(1);

        assertTrue(result.isFailed());
        assertEquals(1, result.getScenarioFailedCount());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void testRunnerWithFeatureObjects() throws Exception {
        Path featurePath = tempDir.resolve("direct.feature");
        Files.writeString(featurePath, """
            Feature: Direct feature

            Scenario: Direct test
            * def value = 42
            * match value == 42
            """);

        Feature feature = Feature.read(Resource.from(featurePath));

        SuiteResult result = Runner.features(feature)
                .outputDir(tempDir.resolve("reports"))
                .parallel(1);

        assertTrue(result.isPassed());
        assertEquals(1, result.getFeatureCount());
    }

    @Test
    void testRunnerBuilderChaining() throws Exception {
        Path feature = tempDir.resolve("chained.feature");
        Files.writeString(feature, """
            Feature: Chained builder

            Scenario: Test chaining
            * def val = 1
            """);

        Runner.Builder builder = Runner.builder()
                .path(feature.toString())
                .karateEnv("test")
                .tags("@smoke")
                .dryRun(false)
                .outputDir(tempDir.resolve("reports").toString());

        Suite suite = builder.buildSuite();
        assertNotNull(suite);
        assertEquals("test", suite.getEnv());
    }

}
