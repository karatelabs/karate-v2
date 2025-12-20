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

import io.karatelabs.gherkin.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RuntimeHook lifecycle.
 */
class RuntimeHookTest {

    @TempDir
    Path tempDir;

    @Test
    void testHookLifecycle() throws Exception {
        List<String> events = new ArrayList<>();

        RuntimeHook hook = new RuntimeHook() {
            @Override
            public boolean beforeSuite(Suite suite) {
                events.add("beforeSuite");
                return true;
            }

            @Override
            public void afterSuite(Suite suite) {
                events.add("afterSuite");
            }

            @Override
            public boolean beforeFeature(FeatureRuntime fr) {
                events.add("beforeFeature:" + fr.getFeature().getName());
                return true;
            }

            @Override
            public void afterFeature(FeatureRuntime fr) {
                events.add("afterFeature:" + fr.getFeature().getName());
            }

            @Override
            public boolean beforeScenario(ScenarioRuntime sr) {
                events.add("beforeScenario:" + sr.getScenario().getName());
                return true;
            }

            @Override
            public void afterScenario(ScenarioRuntime sr) {
                events.add("afterScenario:" + sr.getScenario().getName());
            }

            @Override
            public boolean beforeStep(Step step, ScenarioRuntime sr) {
                events.add("beforeStep:" + step.getKeyword());
                return true;
            }

            @Override
            public void afterStep(StepResult result, ScenarioRuntime sr) {
                events.add("afterStep:" + result.getStep().getKeyword() + ":" + result.getStatus());
            }
        };

        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Hook Test
            Scenario: Simple scenario
            * def a = 1
            * match a == 1
            """);

        Suite suite = Suite.of(tempDir, featureFile.toString())
                .hook(hook)
                .writeReport(false);
        suite.run();

        // Verify lifecycle order
        assertTrue(events.contains("beforeSuite"));
        assertTrue(events.contains("afterSuite"));
        assertTrue(events.contains("beforeFeature:Hook Test"));
        assertTrue(events.contains("afterFeature:Hook Test"));
        assertTrue(events.contains("beforeScenario:Simple scenario"));
        assertTrue(events.contains("afterScenario:Simple scenario"));
        assertTrue(events.contains("beforeStep:def"));
        assertTrue(events.contains("afterStep:def:PASSED"));
        assertTrue(events.contains("beforeStep:match"));
        assertTrue(events.contains("afterStep:match:PASSED"));

        // Verify order: beforeSuite comes first, afterSuite comes last
        assertEquals("beforeSuite", events.get(0));
        assertEquals("afterSuite", events.get(events.size() - 1));
    }

    @Test
    void testBeforeScenarioCanSkip() throws Exception {
        RuntimeHook skipHook = new RuntimeHook() {
            @Override
            public boolean beforeScenario(ScenarioRuntime sr) {
                // Skip scenarios named "Skip me"
                return !sr.getScenario().getName().equals("Skip me");
            }
        };

        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Skip Test
            Scenario: Run this
            * def a = 1
            Scenario: Skip me
            * assert false
            """);

        Suite suite = Suite.of(tempDir, featureFile.toString())
                .hook(skipHook)
                .writeReport(false);
        SuiteResult result = suite.run();

        // The "Skip me" scenario should not fail because it was skipped
        assertTrue(result.isPassed(), "Suite should pass because skipped scenario wasn't run");
    }

    @Test
    void testBeforeStepCanSkip() throws Exception {
        RuntimeHook skipStepHook = new RuntimeHook() {
            @Override
            public boolean beforeStep(Step step, ScenarioRuntime sr) {
                // Skip all assert steps
                return !"assert".equals(step.getKeyword());
            }
        };

        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Skip Step Test
            Scenario: Test
            * def a = 1
            * assert false
            * def b = 2
            """);

        Suite suite = Suite.of(tempDir, featureFile.toString())
                .hook(skipStepHook)
                .writeReport(false);
        SuiteResult result = suite.run();

        // Should pass because assert was skipped
        assertTrue(result.isPassed());
    }

    @Test
    void testMultipleHooks() throws Exception {
        List<String> events = new ArrayList<>();

        RuntimeHook hook1 = new RuntimeHook() {
            @Override
            public boolean beforeScenario(ScenarioRuntime sr) {
                events.add("hook1:before");
                return true;
            }

            @Override
            public void afterScenario(ScenarioRuntime sr) {
                events.add("hook1:after");
            }
        };

        RuntimeHook hook2 = new RuntimeHook() {
            @Override
            public boolean beforeScenario(ScenarioRuntime sr) {
                events.add("hook2:before");
                return true;
            }

            @Override
            public void afterScenario(ScenarioRuntime sr) {
                events.add("hook2:after");
            }
        };

        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Multiple Hooks
            Scenario: Test
            * def a = 1
            """);

        Suite suite = Suite.of(tempDir, featureFile.toString())
                .hook(hook1)
                .hook(hook2)
                .writeReport(false);
        suite.run();

        // Both hooks should be called
        assertTrue(events.contains("hook1:before"));
        assertTrue(events.contains("hook2:before"));
        assertTrue(events.contains("hook1:after"));
        assertTrue(events.contains("hook2:after"));
    }

}
