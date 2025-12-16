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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Scenario Outline expansion and execution.
 * Scenario Outlines allow data-driven tests using Examples tables.
 */
class ScenarioOutlineTest {

    @TempDir
    Path tempDir;

    @Test
    void testBasicOutlineExpansion() throws Exception {
        Path feature = tempDir.resolve("outline-basic.feature");
        Files.writeString(feature, """
            Feature: Basic Outline

            Scenario Outline: Test with <name>
            * def value = '<name>'
            * match value == '<name>'

            Examples:
            | name  |
            | alice |
            | bob   |
            | carol |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        assertEquals(3, result.getScenarioCount());
        assertEquals(3, result.getScenarioPassedCount());
    }

    @Test
    void testOutlineWithMultipleColumns() throws Exception {
        Path feature = tempDir.resolve("outline-multi.feature");
        Files.writeString(feature, """
            Feature: Multi-column Outline

            Scenario Outline: Add <a> + <b> = <sum>
            * def result = <a> + <b>
            * match result == <sum>

            Examples:
            | a! | b! | sum! |
            | 1  | 2  | 3    |
            | 5  | 5  | 10   |
            | 0  | 0  | 0    |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        assertEquals(3, result.getScenarioCount());
    }

    @Test
    void testOutlineWithStringSubstitution() throws Exception {
        Path feature = tempDir.resolve("outline-string.feature");
        Files.writeString(feature, """
            Feature: String Substitution

            Scenario Outline: Greeting <person>
            * def greeting = 'Hello <person>!'
            * match greeting == '<expected>'

            Examples:
            | person | expected      |
            | World  | Hello World!  |
            | Karate | Hello Karate! |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
    }

    @Test
    void testOutlineWithJsonSubstitution() throws Exception {
        Path feature = tempDir.resolve("outline-json.feature");
        Files.writeString(feature, """
            Feature: JSON in Outline

            Scenario Outline: User <name> is <age> years old
            * def user = { name: '<name>', age: <age> }
            * match user.name == '<name>'
            * match user.age == <age>

            Examples:
            | name  | age! |
            | john  | 30   |
            | jane  | 25   |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
    }

    @Test
    void testMultipleExamplesTables() throws Exception {
        Path feature = tempDir.resolve("outline-multi-tables.feature");
        Files.writeString(feature, """
            Feature: Multiple Examples Tables

            Scenario Outline: Test <type> with value <val>
            * def x = <val>
            * match x == <val>

            Examples: Positive values
            | type     | val! |
            | positive | 1    |
            | positive | 10   |

            Examples: Zero and negative
            | type     | val! |
            | zero     | 0    |
            | negative | -5   |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        // 2 rows from first table + 2 rows from second table = 4 scenarios
        assertEquals(4, result.getScenarioCount());
    }

    @Test
    void testOutlineWithBackground() throws Exception {
        Path feature = tempDir.resolve("outline-background.feature");
        Files.writeString(feature, """
            Feature: Outline with Background

            Background:
            * def base = 100

            Scenario Outline: Add <val> to base
            * def result = base + <val>
            * match result == <expected>

            Examples:
            | val! | expected! |
            | 1    | 101       |
            | 50   | 150       |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        assertEquals(2, result.getScenarioCount());
    }

    @Test
    void testOutlineFailure() throws Exception {
        Path feature = tempDir.resolve("outline-fail.feature");
        Files.writeString(feature, """
            Feature: Outline with Failure

            Scenario Outline: Test <val>
            * def actual = <val>
            * match actual == <expected>

            Examples:
            | val! | expected! |
            | 1    | 1         |
            | 2    | 999       |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertFalse(result.isPassed());
        assertEquals(2, result.getScenarioCount());
        assertEquals(1, result.getScenarioPassedCount());
        assertEquals(1, result.getScenarioFailedCount());
    }

    @Test
    void testOutlineWithStringConcatenation() throws Exception {
        // Simpler test that avoids docstring parsing complexity
        Path feature = tempDir.resolve("outline-concat.feature");
        Files.writeString(feature, """
            Feature: Outline with String Concat

            Scenario Outline: Template <id>
            * def body = 'Hello ' + name + '!'
            * match body == 'Hello <name>!'

            Examples:
            | id  | name  |
            | 001 | alice |
            | 002 | bob   |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        assertEquals(2, result.getScenarioCount());
    }

    @Test
    void testOutlineWithJsonObject() throws Exception {
        // Simpler test that avoids table parsing complexity
        Path feature = tempDir.resolve("outline-json2.feature");
        Files.writeString(feature, """
            Feature: Outline with JSON

            Scenario Outline: JSON test <id>
            * def data = { id: id, val: val }
            * match data.id == '<id>'
            * match data.val == <val>

            Examples:
            | id | val! |
            | a  | 1    |
            | b  | 2    |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        assertEquals(2, result.getScenarioCount());
    }

    @Test
    void testOutlineExampleVariablesAccessible() throws Exception {
        Path feature = tempDir.resolve("outline-vars.feature");
        Files.writeString(feature, """
            Feature: Outline Variables

            Scenario Outline: Direct access to example vars
            * def computed = name + '-' + value
            * match computed == '<name>-<value>'

            Examples:
            | name | value |
            | foo  | bar   |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
    }

    @Test
    void testMixedScenarioAndOutline() throws Exception {
        Path feature = tempDir.resolve("mixed.feature");
        Files.writeString(feature, """
            Feature: Mixed Scenarios

            Scenario: Regular scenario
            * def x = 1
            * match x == 1

            Scenario Outline: Outline <val>
            * def y = <val>
            * match y == <val>

            Examples:
            | val! |
            | 2    |
            | 3    |

            Scenario: Another regular scenario
            * def z = 4
            * match z == 4
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        // 1 regular + 2 from outline + 1 regular = 4 scenarios
        assertEquals(4, result.getScenarioCount());
    }

    @Test
    void testTypeHints() throws Exception {
        // Columns ending with ! are evaluated as JS expressions
        Path feature = tempDir.resolve("type-hints.feature");
        Files.writeString(feature, """
            Feature: Type Hints

            Scenario Outline: Type hint <description>
            * match value == expected

            Examples:
            | description       | value!           | expected!              |
            | number            | 42               | 42                     |
            | boolean true      | true             | true                   |
            | boolean false     | false            | false                  |
            | null              | null             | null                   |
            | array             | [1, 2, 3]        | [1, 2, 3]              |
            | object            | { a: 1 }         | { a: 1 }               |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
        assertEquals(6, result.getScenarioCount());
    }

    @Test
    void testStringColumnsWithoutTypeHint() throws Exception {
        // Columns without ! are treated as strings
        Path feature = tempDir.resolve("string-columns.feature");
        Files.writeString(feature, """
            Feature: String Columns

            Scenario Outline: String value <val>
            * match val == '<val>'
            * match typeof val == 'string'

            Examples:
            | val |
            | 123 |
            | abc |
            """);

        Suite suite = Suite.of(feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), getFailureMessage(result));
    }

    private String getFailureMessage(SuiteResult result) {
        if (result.isPassed()) return "none";
        for (FeatureResult fr : result.getFeatureResults()) {
            for (ScenarioResult sr : fr.getScenarioResults()) {
                if (sr.isFailed()) {
                    return sr.getFailureMessage();
                }
            }
        }
        return "unknown";
    }

}
