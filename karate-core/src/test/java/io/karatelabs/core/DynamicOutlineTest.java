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

/**
 * Tests for Dynamic Scenario Outline feature.
 * Dynamic outlines allow Examples data to be generated at runtime via:
 * 1. @setup tagged scenario + karate.setup().data
 * 2. karate.setupOnce() for cached setup
 * 3. Any JS expression that returns a list of maps
 */
class DynamicOutlineTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        // Clean state before each test
    }

    @Test
    void testDynamicOutlineWithSetup() throws Exception {
        Path feature = tempDir.resolve("dynamic-setup.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Setup

            @setup
            Scenario: Generate test data
            * def data = [{a: 1, expected: 1}, {a: 2, expected: 2}]

            Scenario Outline: Test <a>
            * def actual = <a>
            * match actual == <expected>

            Examples:
            | karate.setup().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        assertEquals(2, result.getPassedCount(), "Should have 2 passing scenarios from dynamic data");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithSetupOnce() throws Exception {
        Path feature = tempDir.resolve("dynamic-setuponce.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with SetupOnce

            @setup
            Scenario: Generate test data
            * def data = [{x: 10}, {x: 20}, {x: 30}]

            Scenario Outline: First outline <x>
            * def result = <x> * 2
            * match result == <x> * 2

            Examples:
            | karate.setupOnce().data |

            Scenario Outline: Second outline <x>
            * def result = <x> + 5
            * match result == <x> + 5

            Examples:
            | karate.setupOnce().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Both outlines use the same cached data (3 items each = 6 total scenarios)
        assertEquals(6, result.getPassedCount(), "Should have 6 passing scenarios (3+3 from cached setup)");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithInlineExpression() throws Exception {
        Path feature = tempDir.resolve("dynamic-inline.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Inline Expression

            Scenario Outline: Test inline data <name>
            * def greeting = 'Hello, ' + '<name>'
            * match greeting == 'Hello, ' + '<name>'

            Examples:
            | [{name: 'Alice'}, {name: 'Bob'}, {name: 'Charlie'}] |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        assertEquals(3, result.getPassedCount(), "Should have 3 passing scenarios from inline array");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithFailure() throws Exception {
        Path feature = tempDir.resolve("dynamic-failure.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Failure

            @setup
            Scenario: Generate test data
            * def data = [{val: 1, exp: 1}, {val: 2, exp: 999}]

            Scenario Outline: Test value <val>
            * match <val> == <exp>

            Examples:
            | karate.setup().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Debug output
        for (ScenarioResult sr : result.getScenarioResults()) {
            System.out.println("Scenario: " + sr.getScenario().getName() + " - " + (sr.isPassed() ? "PASS" : "FAIL"));
            for (StepResult stepResult : sr.getStepResults()) {
                System.out.println("  Step: " + stepResult.getStep().getKeyword() + " '" + stepResult.getStep().getText() + "' - " + stepResult.getStatus());
                if (stepResult.isFailed() && stepResult.getError() != null) {
                    System.out.println("    Error: " + stepResult.getError().getMessage());
                }
            }
        }

        assertEquals(1, result.getPassedCount(), "First scenario should pass (1 == 1)");
        assertEquals(1, result.getFailedCount(), "Second scenario should fail (2 != 999)");
    }

    @Test
    void testSetupScenarioNotExecutedDirectly() throws Exception {
        Path feature = tempDir.resolve("setup-not-direct.feature");
        Files.writeString(feature, """
            Feature: Setup Scenario Not Executed Directly

            @setup
            Scenario: This should not run directly
            * def data = [{val: 1}]
            * def setupRan = true

            Scenario: Regular scenario
            * def regular = true
            * match regular == true
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Only the regular scenario should run, not the @setup scenario
        assertEquals(1, result.getPassedCount(), "Only regular scenario should run");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithNamedSetup() throws Exception {
        Path feature = tempDir.resolve("dynamic-named-setup.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Named Setup

            @setup=users
            Scenario: Generate user data
            * def data = [{name: 'user1'}, {name: 'user2'}]

            @setup=products
            Scenario: Generate product data
            * def data = [{name: 'product1'}, {name: 'product2'}, {name: 'product3'}]

            Scenario Outline: Test user <name>
            * match '<name>' contains 'user'

            Examples:
            | karate.setup('users').data |

            Scenario Outline: Test product <name>
            * match '<name>' contains 'product'

            Examples:
            | karate.setup('products').data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // 2 user scenarios + 3 product scenarios = 5 total
        assertEquals(5, result.getPassedCount(), "Should have 5 passing scenarios (2 users + 3 products)");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithBackground() throws Exception {
        Path feature = tempDir.resolve("dynamic-background.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Background

            Background:
            * def baseValue = 100

            @setup
            Scenario: Generate test data
            * def data = [{multiplier: 1}, {multiplier: 2}]

            Scenario Outline: Test multiplier <multiplier>
            * def result = baseValue * <multiplier>
            * match result == 100 * <multiplier>

            Examples:
            | karate.setup().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        assertEquals(2, result.getPassedCount(), "Background should run before each dynamic scenario");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineEmptyData() throws Exception {
        Path feature = tempDir.resolve("dynamic-empty.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Empty Data

            @setup
            Scenario: Generate empty data
            * def data = []

            Scenario Outline: This should not run
            * def val = <a>

            Examples:
            | karate.setup().data |

            Scenario: Regular scenario after empty outline
            * def ran = true
            * match ran == true
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Empty data means no outline scenarios run, but regular scenario should
        assertEquals(1, result.getPassedCount(), "Only regular scenario should run");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithComplexData() throws Exception {
        Path feature = tempDir.resolve("dynamic-complex.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Complex Data

            @setup
            Scenario: Generate complex test data
            * def data =
              \"\"\"
              [
                { id: 1, name: 'item1', tags: ['a', 'b'] },
                { id: 2, name: 'item2', tags: ['c'] }
              ]
              \"\"\"

            Scenario Outline: Test item <id>
            * match <id> == <id>
            * match '<name>' == '<name>'

            Examples:
            | karate.setup().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        assertEquals(2, result.getPassedCount(), "Should handle complex nested data");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testMissingSetupScenario() throws Exception {
        Path feature = tempDir.resolve("missing-setup.feature");
        Files.writeString(feature, """
            Feature: Missing Setup Scenario

            Scenario Outline: Test <val>
            * def x = <val>

            Examples:
            | karate.setup().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);

        // Should throw an error because @setup scenario is missing
        assertThrows(RuntimeException.class, fr::call, "Should throw error when @setup scenario is missing");
    }

    // ========== Generator Function Tests ==========

    @Test
    void testDynamicOutlineWithGeneratorFunction() throws Exception {
        Path feature = tempDir.resolve("dynamic-generator.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Generator Function

            @setup
            Scenario: Define generator
            * def generator = function(i){ if (i == 3) return null; return { name: 'item' + i, index: i } }

            Scenario Outline: Test generated item <name>
            * match '<name>' == 'item' + <index>
            * match __num == <index>

            Examples:
            | karate.setup().generator |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Generator returns items at index 0, 1, 2 (stops at 3 with null)
        assertEquals(3, result.getPassedCount(), "Should have 3 passing scenarios from generator function");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithGeneratorReturningNonMap() throws Exception {
        Path feature = tempDir.resolve("dynamic-generator-nonmap.feature");
        Files.writeString(feature, """
            Feature: Dynamic Outline with Generator Returning Non-Map

            @setup
            Scenario: Define generator that returns false to stop
            * def generator = function(i){ if (i >= 2) return false; return { val: i * 10 } }

            Scenario Outline: Test value <val>
            * match <val> == __num * 10

            Examples:
            | karate.setup().generator |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Generator returns items at index 0, 1 (stops at 2 with false)
        assertEquals(2, result.getPassedCount(), "Should have 2 passing scenarios (stops when non-map returned)");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineGeneratorMatchingV1Syntax() throws Exception {
        // This test matches the v1 outline-generator.feature example
        Path feature = tempDir.resolve("dynamic-generator-v1.feature");
        Files.writeString(feature, """
            Feature: Generator Function (v1 Compatible)

            @setup
            Scenario: Setup generator
            * def generator = function(i){ if (i == 5) return null; return { name: 'cat' + i, age: i } }

            Scenario Outline: Test generated cat
            * match __num == age
            * match __row.name == 'cat' + age

            Examples:
            | karate.setup().generator |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Generator creates 5 items (index 0-4)
        assertEquals(5, result.getPassedCount(), "Should have 5 passing scenarios matching v1 behavior");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineGeneratorWithSetupOnce() throws Exception {
        Path feature = tempDir.resolve("dynamic-generator-cached.feature");
        Files.writeString(feature, """
            Feature: Generator Function with SetupOnce

            @setup
            Scenario: Define generator
            * def generator = function(i){ if (i == 2) return null; return { seq: i } }

            Scenario Outline: First use of generator
            * match <seq> == __num

            Examples:
            | karate.setupOnce().generator |

            Scenario Outline: Second use (cached)
            * match <seq> == __num

            Examples:
            | karate.setupOnce().generator |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Generator evaluated once, cached result used twice: 2 + 2 = 4 scenarios
        assertEquals(4, result.getPassedCount(), "Should have 4 passing scenarios (2 from each outline using cached data)");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineGeneratorReturningEmptyImmediately() throws Exception {
        Path feature = tempDir.resolve("dynamic-generator-empty.feature");
        Files.writeString(feature, """
            Feature: Generator Returns Null Immediately

            @setup
            Scenario: Generator that returns null on first call
            * def generator = function(i){ return null }

            Scenario Outline: Should not run
            * def x = <val>

            Examples:
            | karate.setup().generator |

            Scenario: Regular scenario
            * def ran = true
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        // Generator returns null immediately, so no outline scenarios run
        assertEquals(1, result.getPassedCount(), "Only regular scenario should run");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void testDynamicOutlineWithUnnamedScenario() throws Exception {
        // Tests that Scenario Outline without a name works correctly
        // Regression test for NPE in Scenario.replace() when name is null
        Path feature = tempDir.resolve("dynamic-unnamed.feature");
        Files.writeString(feature, """
            Feature:

            @setup
            Scenario:
            * def data = [{ name: 'one' }, { name: 'two' }]

            Scenario Outline:
            * match name == "#present"

            Examples:
            | karate.setup().data |
            """);

        Feature f = Feature.read(Resource.from(feature, tempDir));
        FeatureRuntime fr = new FeatureRuntime(null, f);
        FeatureResult result = fr.call();

        assertEquals(2, result.getPassedCount(), "Should have 2 passing scenarios from dynamic data");
        assertEquals(0, result.getFailedCount());
    }

}
