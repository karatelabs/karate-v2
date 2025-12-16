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

import io.karatelabs.common.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NdjsonReportListenerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        Console.setColorsEnabled(false);
    }

    @Test
    void testNdjsonFileCreated() throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: NDJSON Test

            Scenario: Passing scenario
            * def a = 1
            * match a == 1
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputNdjson(true)  // opt-in to NDJSON
                .parallel(1);

        assertTrue(result.isPassed());

        // Verify NDJSON file was created
        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        assertTrue(Files.exists(ndjsonPath), "NDJSON file should exist");

        String content = Files.readString(ndjsonPath);
        String[] lines = content.trim().split("\n");

        // Should have 3 lines: suite header, feature, suite_end
        assertEquals(3, lines.length, "Should have 3 NDJSON lines");
    }

    @Test
    void testNdjsonSuiteHeader() throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: Suite Header Test
            Scenario: Test
            * def a = 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputNdjson(true)
                .karateEnv("dev")
                .parallel(1);

        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        String[] lines = Files.readString(ndjsonPath).trim().split("\n");

        // Parse suite header
        @SuppressWarnings("unchecked")
        Map<String, Object> suiteHeader = (Map<String, Object>) Json.of(lines[0]).value();

        assertEquals("suite", suiteHeader.get("t"));
        assertEquals("dev", suiteHeader.get("env"));
        assertEquals("2.0.0", suiteHeader.get("version"));
        assertTrue(suiteHeader.containsKey("time"));
        assertTrue(suiteHeader.containsKey("threads"));
    }

    @Test
    void testNdjsonFeatureLine() throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: Feature Line Test

            Scenario: Passing scenario
            * def a = 1
            * match a == 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputNdjson(true)
                .parallel(1);

        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        String[] lines = Files.readString(ndjsonPath).trim().split("\n");

        // Parse feature line (second line)
        @SuppressWarnings("unchecked")
        Map<String, Object> featureLine = (Map<String, Object>) Json.of(lines[1]).value();

        assertEquals("feature", featureLine.get("t"));
        assertEquals("Feature Line Test", featureLine.get("name"));
        assertTrue(featureLine.get("path").toString().endsWith("test.feature"));
        assertTrue((Boolean) featureLine.get("passed"));
        assertTrue(featureLine.containsKey("ms"));

        // Check scenarios array
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) featureLine.get("scenarios");
        assertEquals(1, scenarios.size());

        Map<String, Object> scenario = scenarios.get(0);
        assertEquals("Passing scenario", scenario.get("name"));
        assertTrue((Boolean) scenario.get("passed"));

        // Check steps
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) scenario.get("steps");
        assertEquals(2, steps.size());
        assertEquals("passed", steps.get(0).get("status"));
    }

    @Test
    void testNdjsonSuiteEnd() throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: Suite End Test

            Scenario: Passing
            * def a = 1

            Scenario: Also passing
            * def b = 2
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputNdjson(true)
                .parallel(1);

        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        String[] lines = Files.readString(ndjsonPath).trim().split("\n");

        // Parse suite_end line (last line)
        @SuppressWarnings("unchecked")
        Map<String, Object> suiteEnd = (Map<String, Object>) Json.of(lines[lines.length - 1]).value();

        assertEquals("suite_end", suiteEnd.get("t"));
        assertEquals(1, ((Number) suiteEnd.get("featuresPassed")).intValue());
        assertEquals(0, ((Number) suiteEnd.get("featuresFailed")).intValue());
        assertEquals(2, ((Number) suiteEnd.get("scenariosPassed")).intValue());
        assertEquals(0, ((Number) suiteEnd.get("scenariosFailed")).intValue());
        assertTrue(suiteEnd.containsKey("ms"));
    }

    @Test
    void testNdjsonWithFailures() throws Exception {
        Path feature = tempDir.resolve("failing.feature");
        Files.writeString(feature, """
            Feature: Failing Test

            Scenario: Passing
            * def a = 1

            Scenario: Failing
            * def b = 2
            * match b == 999
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputNdjson(true)
                .parallel(1);

        assertTrue(result.isFailed());

        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        String[] lines = Files.readString(ndjsonPath).trim().split("\n");

        // Check feature line
        @SuppressWarnings("unchecked")
        Map<String, Object> featureLine = (Map<String, Object>) Json.of(lines[1]).value();
        assertFalse((Boolean) featureLine.get("passed"));

        // Check scenarios - one passed, one failed
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) featureLine.get("scenarios");
        assertEquals(2, scenarios.size());

        assertTrue((Boolean) scenarios.get(0).get("passed"));
        assertFalse((Boolean) scenarios.get(1).get("passed"));
        assertTrue(scenarios.get(1).containsKey("error"));

        // Check suite_end
        @SuppressWarnings("unchecked")
        Map<String, Object> suiteEnd = (Map<String, Object>) Json.of(lines[2]).value();
        assertEquals(0, ((Number) suiteEnd.get("featuresPassed")).intValue());
        assertEquals(1, ((Number) suiteEnd.get("featuresFailed")).intValue());
        assertEquals(1, ((Number) suiteEnd.get("scenariosPassed")).intValue());
        assertEquals(1, ((Number) suiteEnd.get("scenariosFailed")).intValue());
    }

    @Test
    void testNdjsonWithTags() throws Exception {
        Path feature = tempDir.resolve("tagged.feature");
        Files.writeString(feature, """
            Feature: Tagged Test

            @smoke @regression
            Scenario: Tagged scenario
            * def a = 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputNdjson(true)
                .parallel(1);

        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        String[] lines = Files.readString(ndjsonPath).trim().split("\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> featureLine = (Map<String, Object>) Json.of(lines[1]).value();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) featureLine.get("scenarios");
        Map<String, Object> scenario = scenarios.get(0);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) scenario.get("tags");
        assertNotNull(tags);
        assertEquals(2, tags.size());
        assertTrue(tags.contains("@smoke"));
        assertTrue(tags.contains("@regression"));
    }

    @Test
    void testNdjsonMultipleFeatures() throws Exception {
        Path feature1 = tempDir.resolve("feature1.feature");
        Files.writeString(feature1, """
            Feature: Feature One
            Scenario: Test 1
            * def a = 1
            """);

        Path feature2 = tempDir.resolve("feature2.feature");
        Files.writeString(feature2, """
            Feature: Feature Two
            Scenario: Test 2
            * def b = 2
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(tempDir.toString())
                .outputDir(reportDir)
                .outputNdjson(true)
                .parallel(1);

        Path ndjsonPath = reportDir.resolve("karate-results.ndjson");
        String[] lines = Files.readString(ndjsonPath).trim().split("\n");

        // Should have 4 lines: suite header, 2 features, suite_end
        assertEquals(4, lines.length);

        // Verify both feature lines
        int featureCount = 0;
        for (String line : lines) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) Json.of(line).value();
            if ("feature".equals(parsed.get("t"))) {
                featureCount++;
            }
        }
        assertEquals(2, featureCount);

        // Check suite_end totals
        @SuppressWarnings("unchecked")
        Map<String, Object> suiteEnd = (Map<String, Object>) Json.of(lines[3]).value();
        assertEquals(2, ((Number) suiteEnd.get("featuresPassed")).intValue());
        assertEquals(2, ((Number) suiteEnd.get("scenariosPassed")).intValue());
    }

    @Test
    void testHtmlReportGeneratedWithNdjson() throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: HTML With NDJSON Test
            Scenario: Test
            * def a = 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .outputNdjson(true)  // opt-in to NDJSON alongside HTML
                .parallel(1);

        // Verify both NDJSON and HTML reports exist when both are enabled
        assertTrue(Files.exists(reportDir.resolve("karate-results.ndjson")));
        assertTrue(Files.exists(reportDir.resolve("karate-summary.html")));
        assertTrue(Files.exists(reportDir.resolve("index.html")));
    }

}
