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
 * Tests for call and callonce feature execution.
 */
class StepCallTest {

    @TempDir
    Path tempDir;

    @Test
    void testCallFeatureWithResult() throws Exception {
        // Create called feature
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called Feature
            Scenario: Return data
            * def result = { value: 42 }
            """);

        // Create caller feature
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller Feature
            Scenario: Call another feature
            * def response = call read('called.feature')
            * match response.result == { value: 42 }
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Suite should pass: " + getFailureMessage(result));
    }

    @Test
    void testCallFeatureWithArguments() throws Exception {
        // Create called feature that uses arguments
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called with Args
            Scenario: Use arguments
            * def doubled = inputValue * 2
            """);

        // Create caller feature
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller with Args
            Scenario: Pass arguments
            * def response = call read('called.feature') { inputValue: 21 }
            * match response.doubled == 42
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Suite should pass: " + getFailureMessage(result));
    }

    @Test
    void testCallonce() throws Exception {
        // Create a counter file to track calls
        Path counterFeature = tempDir.resolve("counter.feature");
        Files.writeString(counterFeature, """
            Feature: Counter
            Scenario: Increment
            * def callCount = typeof callCount == 'undefined' ? 1 : callCount + 1
            """);

        // Create feature that calls the counter twice with callonce
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Callonce Test
            Scenario: First scenario
            * def response = callonce read('counter.feature')

            Scenario: Second scenario
            * def response2 = callonce read('counter.feature')
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        // Both scenarios should pass
        assertTrue(result.isPassed());
        assertEquals(2, result.getScenarioCount());
    }

    @Test
    void testCallSimple() throws Exception {
        // Create a simple feature that sets variables
        Path calledFeature = tempDir.resolve("simple.feature");
        Files.writeString(calledFeature, """
            Feature: Simple
            Scenario: Set value
            * def x = 100
            """);

        // Create caller
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Call Simple
            Scenario: Call and check
            * call read('simple.feature')
            * def a = 1
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed());
    }

    @Test
    void testNestedCall() throws Exception {
        // Create innermost feature
        Path inner = tempDir.resolve("inner.feature");
        Files.writeString(inner, """
            Feature: Inner
            Scenario: Set inner value
            * def innerValue = 'from-inner'
            """);

        // Create middle feature that calls inner
        Path middle = tempDir.resolve("middle.feature");
        Files.writeString(middle, """
            Feature: Middle
            Scenario: Call inner
            * def middleResponse = call read('inner.feature')
            * def middleValue = 'from-middle'
            """);

        // Create outer feature that calls middle
        Path outer = tempDir.resolve("outer.feature");
        Files.writeString(outer, """
            Feature: Outer
            Scenario: Call middle
            * def outerResponse = call read('middle.feature')
            * match outerResponse.middleValue == 'from-middle'
            """);

        Suite suite = Suite.of(tempDir, outer.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Nested calls should work: " + getFailureMessage(result));
    }

    @Test
    void testKarateCallWithArguments() throws Exception {
        // Create called feature that uses arguments
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called
            Scenario: Use arg
            * match foo == 'bar'
            """);

        // Create caller that uses karate.call() from JavaScript
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller
            Scenario: Call via JS
            * def foo = null
            * karate.call('called.feature', { foo: 'bar' })
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "karate.call() with args should work: " + getFailureMessage(result));
    }

    @Test
    void testKarateCallWithoutArguments() throws Exception {
        // Create called feature
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called
            Scenario: Simple
            * def x = 1
            """);

        // Create caller that uses karate.call() without arguments
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller
            Scenario: Call via JS
            * def result = karate.call('called.feature')
            * match result.x == 1
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "karate.call() without args should work: " + getFailureMessage(result));
    }

    @Test
    void testCallFeatureVariableWithArrayLoop() throws Exception {
        // Create called feature that uses 'foo' argument
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called
            Scenario:
            * def res = foo
            """);

        // Create caller that reads feature into variable and calls with array
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller
            Scenario: Call with array loop
            * def called = read('called.feature')
            * def data = [{ foo: 'first' }, { foo: 'second' }]
            * def result = call called data
            * def extracted = karate.jsonPath(result, '$[*].res')
            * match extracted == ['first', 'second']
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Call with feature var and array loop should work: " + getFailureMessage(result));
    }

    @Test
    void testCallByTag() throws Exception {
        // Create called feature with multiple scenarios, each with a tag
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Tagged Scenarios
            @name=first
            Scenario: First
            * def bar = 1

            @name=second
            Scenario: Second
            * def bar = 2

            @name=third
            Scenario: Third
            * def bar = 3
            """);

        // Create caller that calls specific scenario by tag
        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Call by Tag
            Scenario: Call second scenario
            * def foo = call read('called.feature@name=second')
            * match foo.bar == 2
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Call by tag should work: " + getFailureMessage(result));
    }

    @Test
    void testCallByTagSameFile() throws Exception {
        // Create feature that calls a tagged scenario in the same file
        Path feature = tempDir.resolve("sameFile.feature");
        Files.writeString(feature, """
            Feature: Same File Tag Call

            Scenario: Caller
            * def foo = call read('@target')
            * match foo.bar == 42

            @ignore @target
            Scenario: Target
            * def bar = 42
            """);

        Suite suite = Suite.of(tempDir, feature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Same-file tag call should work: " + getFailureMessage(result));
    }

    @Test
    void testArgVariableInCalledFeature() throws Exception {
        // Tests that __arg is available in called feature
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called
            Scenario:
            * match __arg == { foo: 'bar' }
            """);

        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller
            Scenario:
            * def params = { foo: 'bar' }
            * call read('called.feature') params
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "__arg should be available: " + getFailureMessage(result));
    }

    @Test
    void testArgVariableWithAssignment() throws Exception {
        // Tests that __arg is available even when call result is assigned to a variable
        // See https://github.com/karatelabs/karate/pull/1436
        Path calledFeature = tempDir.resolve("called.feature");
        Files.writeString(calledFeature, """
            Feature: Called
            Scenario:
            * match __arg == { foo: 'bar' }
            * def result = 'done'
            """);

        Path callerFeature = tempDir.resolve("caller.feature");
        Files.writeString(callerFeature, """
            Feature: Caller
            Scenario:
            * def args = { foo: 'bar' }
            * def response = call read('called.feature') args
            * match response.result == 'done'
            """);

        Suite suite = Suite.of(tempDir, callerFeature.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "__arg should be available with assignment: " + getFailureMessage(result));
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
