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

import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JunitXmlWriterTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        Console.setColorsEnabled(false);
    }

    @Test
    void testJunitXmlGeneration() throws Exception {
        Path feature = tempDir.resolve("test.feature");
        Files.writeString(feature, """
            Feature: JUnit XML Test

            Scenario: Passing scenario
            * def a = 1
            * match a == 1

            Scenario: Another passing
            * def b = 2
            * match b == 2
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputJunitXml(true)
                .parallel(1);

        assertTrue(result.isPassed());

        // Verify XML file was created
        Path xmlPath = reportDir.resolve("karate-junit.xml");
        assertTrue(Files.exists(xmlPath), "JUnit XML file should exist");

        String xml = Files.readString(xmlPath);

        // Verify XML structure
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<testsuites"));
        assertTrue(xml.contains("name=\"karate\""));
        assertTrue(xml.contains("tests=\"2\""));
        assertTrue(xml.contains("failures=\"0\""));
        assertTrue(xml.contains("<testsuite"));
        assertTrue(xml.contains("name=\"JUnit XML Test\""));
        assertTrue(xml.contains("<testcase"));
        assertTrue(xml.contains("name=\"Passing scenario\""));
        assertTrue(xml.contains("name=\"Another passing\""));
        assertTrue(xml.contains("</testsuites>"));
    }

    @Test
    void testJunitXmlWithFailures() throws Exception {
        Path feature = tempDir.resolve("failing.feature");
        Files.writeString(feature, """
            Feature: Failing Test

            Scenario: Passing scenario
            * def a = 1
            * match a == 1

            Scenario: Failing scenario
            * def b = 2
            * match b == 999
            """);

        Path reportDir = tempDir.resolve("reports");

        SuiteResult result = Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputJunitXml(true)
                .parallel(1);

        assertTrue(result.isFailed());

        Path xmlPath = reportDir.resolve("karate-junit.xml");
        assertTrue(Files.exists(xmlPath));

        String xml = Files.readString(xmlPath);

        // Verify failure counts
        assertTrue(xml.contains("tests=\"2\""));
        assertTrue(xml.contains("failures=\"1\""));

        // Verify failure element
        assertTrue(xml.contains("<failure"));
        assertTrue(xml.contains("message="));
        assertTrue(xml.contains("</failure>"));
    }

    @Test
    void testJunitXmlEscapesSpecialCharacters() throws Exception {
        Path feature = tempDir.resolve("special.feature");
        Files.writeString(feature, """
            Feature: Test with <special> & "characters"

            Scenario: Test with 'quotes'
            * def a = 1
            * match a == 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .outputJunitXml(true)
                .parallel(1);

        Path xmlPath = reportDir.resolve("karate-junit.xml");
        String xml = Files.readString(xmlPath);

        // Verify special characters are escaped
        assertTrue(xml.contains("&lt;special&gt;"));
        assertTrue(xml.contains("&amp;"));
        assertTrue(xml.contains("&quot;characters&quot;"));
        assertTrue(xml.contains("&apos;quotes&apos;"));
    }

    @Test
    void testJunitXmlNotGeneratedByDefault() throws Exception {
        Path feature = tempDir.resolve("simple.feature");
        Files.writeString(feature, """
            Feature: Simple
            Scenario: Test
            * def a = 1
            """);

        Path reportDir = tempDir.resolve("reports");

        Runner.path(feature.toString())
                .workingDir(tempDir)
                .outputDir(reportDir)
                .parallel(1);

        // JSON report should exist
        assertTrue(Files.exists(reportDir.resolve("karate-summary.json")));

        // JUnit XML should NOT exist by default
        assertFalse(Files.exists(reportDir.resolve("karate-junit.xml")));
    }

    @Test
    void testToXmlMethod() {
        // Create a minimal SuiteResult manually
        SuiteResult result = new SuiteResult();
        result.setStartTime(System.currentTimeMillis());
        result.setEndTime(System.currentTimeMillis() + 1000);

        String xml = JunitXmlWriter.toXml(result);

        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<testsuites"));
        assertTrue(xml.contains("tests=\"0\""));
        assertTrue(xml.contains("failures=\"0\""));
        assertTrue(xml.contains("</testsuites>"));
    }

}
