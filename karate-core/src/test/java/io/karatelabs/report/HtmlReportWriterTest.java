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
package io.karatelabs.report;

import io.karatelabs.core.Console;
import io.karatelabs.core.Globals;
import io.karatelabs.core.HtmlReport;
import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HTML report generation.
 * <p>
 * <b>For Report Development:</b>
 * <pre>
 * mvn test -Dtest=HtmlReportWriterTest#testHtmlReportGeneration -q
 * open target/karate-report-dev/karate-summary.html
 * </pre>
 * <p>
 * See also: /docs/HTML_REPORTS.md for the full development guide.
 */
class HtmlReportWriterTest {

    private static final Path OUTPUT_DIR = Path.of("target/karate-report-dev");

    @BeforeEach
    void setup() {
        Console.setColorsEnabled(false);
    }

    /**
     * Main dev test - run this to regenerate HTML reports after template changes.
     * <p>
     * Reports written to: target/karate-report-dev/
     * <p>
     * Quick run: mvn test -Dtest=HtmlReportWriterTest#testHtmlReportGeneration -q
     */
    @Test
    void testHtmlReportGeneration() {
        // Run features from test-classes directory with parallel for timeline
        String testResourcesDir = "target/test-classes/io/karatelabs/report";
        SuiteResult result = Runner.path(testResourcesDir)
                .outputDir(OUTPUT_DIR)
                .outputNdjson(true)
                .parallel(3);  // parallel for timeline testing

        // Verify the run completed (feature count may vary with @ignore features)
        assertTrue(result.getFeatureCount() >= 3, "Should have at least 3 main features");
        assertTrue(result.getScenarioPassedCount() >= 12, "Should have many passing scenarios");
        assertTrue(result.getScenarioFailedCount() >= 1, "Should have at least the @wip failing scenario");

        // Verify HTML reports were generated
        assertTrue(Files.exists(OUTPUT_DIR.resolve("karate-summary.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("karate-timeline.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("index.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("features")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("res/bootstrap.min.css")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("res/favicon.ico")));

        // Verify NDJSON file was created
        assertTrue(Files.exists(OUTPUT_DIR.resolve("karate-results.ndjson")));

        System.out.println("\n=== HTML Reports Generated ===");
        System.out.println("Open: file://" + OUTPUT_DIR.toAbsolutePath().resolve("karate-summary.html"));
        System.out.println("Or:   cd " + OUTPUT_DIR.toAbsolutePath() + " && python3 -m http.server 8000");
    }

    @Test
    void testHtmlReportWithEnv() {
        Path outputDir = Path.of("target/karate-report-dev-env");
        String testResourcesDir = "target/test-classes/io/karatelabs/report";

        SuiteResult result = Runner.path(testResourcesDir)
                .karateEnv("staging")
                .outputDir(outputDir)
                .outputNdjson(true)  // opt-in for NDJSON
                .parallel(1);

        assertTrue(Files.exists(outputDir.resolve("karate-summary.html")));
        assertTrue(Files.exists(outputDir.resolve("karate-results.ndjson")));

        System.out.println("\n=== HTML Reports (with env) Generated ===");
        System.out.println("Open: " + outputDir.toAbsolutePath().resolve("karate-summary.html"));
    }

    @Test
    void testHtmlContainsInlinedJson(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: Inlined JSON Test

            Scenario: Test scenario
            * def a = 1
            * match a == 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .parallel(1);

        // Verify HTML contains the JSON data placeholder replacement
        String summaryHtml = Files.readString(reportDir.resolve("karate-summary.html"));
        assertTrue(summaryHtml.contains("<script id=\"karate-data\" type=\"application/json\">"));
        assertTrue(summaryHtml.contains("\"feature_count\""));
        assertTrue(summaryHtml.contains("x-data=\"KarateReport.summaryData()\""));

        // Verify feature page also has inlined JSON
        Path featuresDir = reportDir.resolve("features");
        assertTrue(Files.exists(featuresDir));
        String[] featureFiles = featuresDir.toFile().list();
        assertNotNull(featureFiles);
        assertTrue(featureFiles.length > 0);

        String featureHtml = Files.readString(featuresDir.resolve(featureFiles[0]));
        assertTrue(featureHtml.contains("<script id=\"karate-data\" type=\"application/json\">"));
        assertTrue(featureHtml.contains("x-data=\"KarateReport.featureData()\""));
    }

    @Test
    void testNdjsonFormat(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: NDJSON Format Test

            Scenario: First scenario
            * def a = 1

            Scenario: Second scenario
            * def b = 2
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputNdjson(true)  // opt-in to NDJSON
                .parallel(1);

        // Verify NDJSON format
        String ndjson = Files.readString(reportDir.resolve("karate-results.ndjson"));
        String[] lines = ndjson.trim().split("\n");

        assertEquals(3, lines.length, "Should have 3 lines: suite, feature, suite_end");

        // First line should be suite header
        assertTrue(lines[0].contains("\"t\":\"suite\""));
        assertTrue(lines[0].contains("\"version\":\"" + Globals.KARATE_VERSION + "\""));

        // Second line should be feature
        assertTrue(lines[1].contains("\"t\":\"feature\""));
        assertTrue(lines[1].contains("\"scenarios\""));

        // Last line should be suite_end
        assertTrue(lines[2].contains("\"t\":\"suite_end\""));
        assertTrue(lines[2].contains("\"featuresPassed\""));
    }

    @Test
    void testReportAggregation(@TempDir Path tempDir) throws Exception {
        // Create two feature files and run them separately
        Path feature1 = tempDir.resolve("feature1.feature");
        Files.writeString(feature1, """
            Feature: Aggregation Test 1
            Scenario: Test 1
            * def a = 1
            """);

        Path feature2 = tempDir.resolve("feature2.feature");
        Files.writeString(feature2, """
            Feature: Aggregation Test 2
            Scenario: Test 2
            * def b = 2
            """);

        Path run1Dir = tempDir.resolve("run1");
        Path run2Dir = tempDir.resolve("run2");
        Path combinedDir = tempDir.resolve("combined");

        // Run features separately with NDJSON enabled for aggregation
        Runner.path(feature1.toString())
                .workingDir(tempDir)
                .outputDir(run1Dir)
                .outputNdjson(true)
                .parallel(1);

        Runner.path(feature2.toString())
                .workingDir(tempDir)
                .outputDir(run2Dir)
                .outputNdjson(true)
                .parallel(1);

        // Verify both NDJSON files exist
        assertTrue(Files.exists(run1Dir.resolve("karate-results.ndjson")));
        assertTrue(Files.exists(run2Dir.resolve("karate-results.ndjson")));

        // Aggregate reports
        HtmlReport.aggregate()
                .json(run1Dir.resolve("karate-results.ndjson"))
                .json(run2Dir.resolve("karate-results.ndjson"))
                .outputDir(combinedDir)
                .generate();

        // Verify combined report
        assertTrue(Files.exists(combinedDir.resolve("karate-summary.html")));
        assertTrue(Files.exists(combinedDir.resolve("karate-results.ndjson")));

        // Verify the combined NDJSON has both features
        String combinedNdjson = Files.readString(combinedDir.resolve("karate-results.ndjson"));
        assertTrue(combinedNdjson.contains("Aggregation Test 1"));
        assertTrue(combinedNdjson.contains("Aggregation Test 2"));

        // Count feature lines
        int featureCount = 0;
        for (String line : combinedNdjson.split("\n")) {
            if (line.contains("\"t\":\"feature\"")) {
                featureCount++;
            }
        }
        assertEquals(2, featureCount, "Combined report should have 2 features");
    }

    // ========== Scenario Name Substitution Tests ==========

    @Test
    void testOutlinePlaceholderSubstitutionInScenarioName(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("outline-name.feature");
        Files.writeString(feature, """
            Feature: Outline Name Substitution

            Scenario Outline: Testing <name> with value <value>
            * def result = '<name>'
            * match result == name

            Examples:
            | name  | value |
            | foo   | 1     |
            | bar   | 2     |
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .outputNdjson(true)
                .parallel(1);

        assertTrue(result.isPassed(), "All scenarios should pass");
        assertEquals(2, result.getScenarioPassedCount());

        // Verify NDJSON contains the substituted scenario names
        String ndjson = Files.readString(reportDir.resolve("karate-results.ndjson"));
        assertTrue(ndjson.contains("Testing foo with value 1"),
            "Should contain substituted scenario name for first example");
        assertTrue(ndjson.contains("Testing bar with value 2"),
            "Should contain substituted scenario name for second example");
    }

    @Test
    void testBacktickScenarioNameInterpolation(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("backtick-name.feature");
        Files.writeString(feature, """
            Feature: Backtick Name Interpolation

            Scenario: `result is ${1+1}`
            * def a = 2
            * match a == 2
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .outputNdjson(true)
                .parallel(1);

        assertTrue(result.isPassed());
        assertEquals(1, result.getScenarioPassedCount());

        // Verify NDJSON contains the evaluated scenario name
        String ndjson = Files.readString(reportDir.resolve("karate-results.ndjson"));
        assertTrue(ndjson.contains("result is 2"),
            "Should contain evaluated scenario name 'result is 2'");
        assertFalse(ndjson.contains("result is ${1+1}"),
            "Should not contain unevaluated template literal");
    }

    @Test
    void testBacktickScenarioNameWithVariable(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("backtick-var.feature");
        Files.writeString(feature, """
            Feature: Backtick With Variable

            Scenario: `status is ${status}`
            * def status = 'active'
            * match status == 'active'
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .outputNdjson(true)
                .parallel(1);

        assertTrue(result.isPassed());

        // Verify NDJSON contains the evaluated scenario name with variable value
        String ndjson = Files.readString(reportDir.resolve("karate-results.ndjson"));
        assertTrue(ndjson.contains("status is active"),
            "Should contain evaluated scenario name with variable value");
    }

    @Test
    void testBacktickScenarioNameInOutline(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("backtick-outline.feature");
        Files.writeString(feature, """
            Feature: Backtick In Outline

            Scenario Outline: `testing ${name} = ${value * 2}`
            * def result = value * 2
            * match result == value * 2

            Examples:
            | name  | value |
            | foo   | 5     |
            | bar   | 10    |
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .outputNdjson(true)
                .parallel(1);

        assertTrue(result.isPassed());
        assertEquals(2, result.getScenarioPassedCount());

        // Verify NDJSON contains the evaluated scenario names with outline variables
        String ndjson = Files.readString(reportDir.resolve("karate-results.ndjson"));
        assertTrue(ndjson.contains("testing foo = 10"),
            "Should contain evaluated name for first example (5 * 2 = 10)");
        assertTrue(ndjson.contains("testing bar = 20"),
            "Should contain evaluated name for second example (10 * 2 = 20)");
    }

    @Test
    void testBacktickEvalFailureWarningInReport(@TempDir Path tempDir) throws Exception {
        Path feature = tempDir.resolve("backtick-fail.feature");
        Files.writeString(feature, """
            Feature: Backtick Eval Failure

            Scenario: `result is ${undefinedVariable}`
            * def a = 1
            * match a == 1
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputHtmlReport(true)
                .outputNdjson(true)
                .parallel(1);

        // Scenario should still pass (eval failure doesn't fail the test)
        assertTrue(result.isPassed());

        // Original name should be preserved (with backticks) in the report
        String ndjson = Files.readString(reportDir.resolve("karate-results.ndjson"));
        assertTrue(ndjson.contains("`result is ${undefinedVariable}`"),
            "Original name should be preserved when eval fails");
        // Warning should appear in the step log
        assertTrue(ndjson.contains("Failed to evaluate scenario name"),
            "Warning should appear in report log");
    }

}
