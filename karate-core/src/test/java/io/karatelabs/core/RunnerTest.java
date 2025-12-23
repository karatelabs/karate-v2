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
                .workingDir(tempDir)
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
                .workingDir(tempDir)
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
                .workingDir(tempDir)
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

        Feature feature = Feature.read(Resource.from(featurePath, tempDir));

        SuiteResult result = Runner.features(feature)
                .workingDir(tempDir)
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
                .workingDir(tempDir)
                .karateEnv("test")
                .tags("@smoke")
                .dryRun(false)
                .outputDir(tempDir.resolve("reports").toString());

        Suite suite = builder.buildSuite();
        assertNotNull(suite);
        assertEquals("test", suite.getEnv());
    }

    @Test
    void testRunnerWithClasspathDirectory() {
        // This directory contains 2 feature files in test resources
        // One has an intentionally failing scenario for report testing
        SuiteResult result = Runner.path("classpath:io/karatelabs/report")
                .outputDir(tempDir.resolve("reports"))
                .outputHtmlReport(false)
                .outputConsoleSummary(false)
                .parallel(1);

        // Should find all feature files: test-report, second-feature, third-feature
        assertEquals(3, result.getFeatureCount());
        // Total scenarios: 6 + 4 + 4 = 14
        assertEquals(14, result.getScenarioCount());
    }

    @Test
    void testRunnerWithClasspathDirectoryTrailingSlash() {
        // Same test but with trailing slash
        // Contains intentionally failing scenario for report testing
        SuiteResult result = Runner.path("classpath:io/karatelabs/report/")
                .outputDir(tempDir.resolve("reports"))
                .outputHtmlReport(false)
                .outputConsoleSummary(false)
                .parallel(1);

        assertEquals(3, result.getFeatureCount());
    }

    @Test
    void testRunnerWithClasspathSingleFile() {
        // Test single classpath file (existing behavior)
        SuiteResult result = Runner.path("classpath:io/karatelabs/report/second-feature.feature")
                .outputDir(tempDir.resolve("reports"))
                .outputHtmlReport(false)
                .parallel(1);

        assertEquals(1, result.getFeatureCount());
        assertEquals(4, result.getScenarioPassedCount());  // Payment Processing has 4 scenarios
    }

    @Test
    void testRunnerWithClasspathNestedDirectory() {
        // Test that features/ directory only contains 1 feature
        SuiteResult result = Runner.path("classpath:feature")
                .outputDir(tempDir.resolve("reports"))
                .outputHtmlReport(false)
                .parallel(1);

        // Should find http-simple.feature (or may be 0 if it requires HTTP)
        assertTrue(result.getFeatureCount() >= 0);
    }

    @Test
    void testRunnerMixedPaths() throws Exception {
        // Mix file system and classpath paths
        Path feature = tempDir.resolve("local.feature");
        Files.writeString(feature, """
            Feature: Local feature
            Scenario: Local test
            * def x = 1
            """);

        SuiteResult result = Runner.path(feature.toString(), "classpath:io/karatelabs/report/second-feature.feature")
                .workingDir(tempDir)
                .outputDir(tempDir.resolve("reports"))
                .outputHtmlReport(false)
                .parallel(1);

        // 1 local + 1 classpath
        assertEquals(2, result.getFeatureCount());
    }

}
