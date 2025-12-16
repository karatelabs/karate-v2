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
import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HTML report generation.
 * <p>
 * Reports are written to target/karate-report-dev for easy inspection during development.
 * Run this test to regenerate HTML reports after making template changes.
 */
class HtmlReportWriterTest {

    private static final Path OUTPUT_DIR = Path.of("target/karate-report-dev");

    @BeforeEach
    void setup() {
        Console.setColorsEnabled(false);
    }

    @Test
    void testHtmlReportGeneration() {
        // Run features from test-classes directory
        String testResourcesDir = "target/test-classes/io/karatelabs/report";
        SuiteResult result = Runner.path(testResourcesDir)
                .outputDir(OUTPUT_DIR)
                .parallel(1);

        // Verify the run
        assertEquals(2, result.getFeatureCount());
        assertTrue(result.getScenarioPassedCount() >= 6);
        assertEquals(1, result.getScenarioFailedCount()); // "This one fails" scenario

        // Verify HTML reports were generated
        assertTrue(Files.exists(OUTPUT_DIR.resolve("karate-summary.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("karate-tags.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("karate-timeline.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("index.html")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("features")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("res/bootstrap.min.css")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("res/favicon.ico")));

        System.out.println("\n=== HTML Reports Generated ===");
        System.out.println("Open: " + OUTPUT_DIR.toAbsolutePath().resolve("karate-summary.html"));
    }

    @Test
    void testHtmlReportWithEnv() {
        Path outputDir = Path.of("target/karate-report-dev-env");
        String testResourcesDir = "target/test-classes/io/karatelabs/report";

        SuiteResult result = Runner.path(testResourcesDir)
                .karateEnv("staging")
                .outputDir(outputDir)
                .parallel(1);

        assertTrue(Files.exists(outputDir.resolve("karate-summary.html")));

        System.out.println("\n=== HTML Reports (with env) Generated ===");
        System.out.println("Open: " + outputDir.toAbsolutePath().resolve("karate-summary.html"));
    }

}
