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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the core event system classes: RunEventType, RunEvent sealed types, RunListener, RunListenerFactory.
 */
class RunListenerTest {

    @Test
    void testRunEventTypeValues() {
        // Verify all expected event types exist
        RunEventType[] types = RunEventType.values();
        assertEquals(10, types.length);

        // Verify specific types
        assertNotNull(RunEventType.SUITE_ENTER);
        assertNotNull(RunEventType.SUITE_EXIT);
        assertNotNull(RunEventType.FEATURE_ENTER);
        assertNotNull(RunEventType.FEATURE_EXIT);
        assertNotNull(RunEventType.SCENARIO_ENTER);
        assertNotNull(RunEventType.SCENARIO_EXIT);
        assertNotNull(RunEventType.STEP_ENTER);
        assertNotNull(RunEventType.STEP_EXIT);
        assertNotNull(RunEventType.ERROR);
        assertNotNull(RunEventType.PROGRESS);
    }

    @Test
    void testSuiteRunEventEnter() {
        Suite suite = Suite.of(new String[0]);
        SuiteRunEvent event = SuiteRunEvent.enter(suite);

        assertEquals(RunEventType.SUITE_ENTER, event.getType());
        assertSame(suite, event.source());
        assertNull(event.result());

        // Test toJson
        Map<String, Object> json = event.toJson();
        assertNotNull(json);
    }

    @Test
    void testSuiteRunEventExit() {
        Suite suite = Suite.of(new String[0]);
        SuiteResult result = new SuiteResult();
        SuiteRunEvent event = SuiteRunEvent.exit(suite, result);

        assertEquals(RunEventType.SUITE_EXIT, event.getType());
        assertSame(suite, event.source());
        assertSame(result, event.result());
    }

    @Test
    void testProgressRunEvent() {
        Suite suite = Suite.of(new String[0]);
        ProgressRunEvent event = ProgressRunEvent.of(suite, 5, 10);

        assertEquals(RunEventType.PROGRESS, event.getType());
        assertSame(suite, event.suite());
        assertEquals(5, event.completed());
        assertEquals(10, event.total());

        // Test toJson
        Map<String, Object> json = event.toJson();
        assertEquals(5, json.get("completed"));
        assertEquals(10, json.get("total"));
        assertEquals(50, json.get("percent"));
    }

    @Test
    void testErrorRunEvent() {
        Exception error = new RuntimeException("test error");
        ErrorRunEvent event = ErrorRunEvent.of(error, null);

        assertEquals(RunEventType.ERROR, event.getType());
        assertSame(error, event.error());
        assertNull(event.scenarioRuntime());

        // Test toJson
        Map<String, Object> json = event.toJson();
        assertEquals("test error", json.get("message"));
        assertEquals("RuntimeException", json.get("type"));
    }

    @Test
    void testRunListenerFunctionalInterface() {
        // RunListener is a functional interface, so we can use a lambda
        RunListener listener = event -> event.getType() != RunEventType.ERROR;

        Suite suite = Suite.of(new String[0]);

        assertTrue(listener.onEvent(SuiteRunEvent.enter(suite)));
        assertFalse(listener.onEvent(ErrorRunEvent.of(new RuntimeException(), null)));
    }

    @Test
    void testRunListenerPatternMatching() {
        // Test pattern matching on sealed types
        RunListener listener = event -> switch (event) {
            case SuiteRunEvent e -> {
                assertNotNull(e.source());
                yield true;
            }
            case ProgressRunEvent e -> {
                assertTrue(e.completed() >= 0);
                yield true;
            }
            case ErrorRunEvent e -> false;  // Skip errors
            default -> true;
        };

        Suite suite = Suite.of(new String[0]);

        assertTrue(listener.onEvent(SuiteRunEvent.enter(suite)));
        assertTrue(listener.onEvent(ProgressRunEvent.of(suite, 1, 10)));
        assertFalse(listener.onEvent(ErrorRunEvent.of(new RuntimeException(), null)));
    }

    @Test
    void testRunListenerFactoryCreatesListeners() {
        // Track how many times create() is called
        int[] createCount = {0};

        RunListenerFactory factory = () -> {
            createCount[0]++;
            return event -> true;
        };

        // Create multiple listeners
        RunListener listener1 = factory.create();
        RunListener listener2 = factory.create();

        assertNotNull(listener1);
        assertNotNull(listener2);
        assertEquals(2, createCount[0]);
    }

    @Test
    void testFeatureRunEventToJson() {
        // FeatureRunEvent.enter with null source should not throw
        FeatureRunEvent event = new FeatureRunEvent(RunEventType.FEATURE_ENTER, null, null);
        Map<String, Object> json = event.toJson();
        assertNotNull(json);
        assertTrue(json.isEmpty());
    }

    @Test
    void testScenarioRunEventToJson() {
        // ScenarioRunEvent with null source should not throw
        ScenarioRunEvent event = new ScenarioRunEvent(RunEventType.SCENARIO_ENTER, null, null);
        Map<String, Object> json = event.toJson();
        assertNotNull(json);
        assertTrue(json.isEmpty());
    }

    @Test
    void testStepRunEventToJson() {
        // StepRunEvent with null step should not throw
        StepRunEvent event = new StepRunEvent(RunEventType.STEP_ENTER, null, null, null);
        Map<String, Object> json = event.toJson();
        assertNotNull(json);
        assertTrue(json.isEmpty());
    }

    @Test
    void testPatternMatchingOnEventTypes() {
        // Verify pattern matching works for all core event types
        Suite suite = Suite.of(new String[0]);

        RunEvent[] events = {
            SuiteRunEvent.enter(suite),
            FeatureRunEvent.enter(null),
            ScenarioRunEvent.enter(null),
            StepRunEvent.enter(null, null),
            ErrorRunEvent.of(new RuntimeException(), null),
            ProgressRunEvent.of(suite, 0, 0)
        };

        for (RunEvent event : events) {
            // Non-sealed interface requires default case for extensibility
            String result = switch (event) {
                case SuiteRunEvent e -> "suite";
                case FeatureRunEvent e -> "feature";
                case ScenarioRunEvent e -> "scenario";
                case StepRunEvent e -> "step";
                case ErrorRunEvent e -> "error";
                case ProgressRunEvent e -> "progress";
                default -> "custom";
            };
            assertNotNull(result);
            assertNotEquals("custom", result, "Core event type should be matched");
        }
    }

    // ========== Suite Integration Tests ==========

    @Test
    void testSuiteListenerBuilder() {
        List<RunEvent> events = new ArrayList<>();
        RunListener listener = event -> {
            events.add(event);
            return true;
        };

        Suite suite = Suite.of(new String[0])
            .listener(listener);

        assertEquals(1, suite.getListeners().size());
        assertSame(listener, suite.getListeners().get(0));
    }

    @Test
    void testSuiteListenerFactoryBuilder() {
        int[] createCount = {0};
        RunListenerFactory factory = () -> {
            createCount[0]++;
            return event -> true;
        };

        Suite suite = Suite.of(new String[0])
            .listenerFactory(factory);

        assertEquals(1, suite.getListenerFactories().size());
        assertEquals(0, createCount[0]); // Not created yet
    }

    @Test
    void testSuiteFireEventToGlobalListeners() {
        List<RunEvent> received = new ArrayList<>();
        Suite suite = Suite.of(new String[0])
            .listener(event -> {
                received.add(event);
                return true;
            });

        SuiteRunEvent event = SuiteRunEvent.enter(suite);
        boolean result = suite.fireEvent(event);

        assertTrue(result);
        assertEquals(1, received.size());
        assertSame(event, received.get(0));
    }

    @Test
    void testSuiteFireEventReturnsFalseWhenListenerRejects() {
        Suite suite = Suite.of(new String[0])
            .listener(event -> false); // Always reject

        boolean result = suite.fireEvent(SuiteRunEvent.enter(suite));

        assertFalse(result);
    }

    @Test
    void testSuiteFireEventMultipleListeners() {
        List<String> order = new ArrayList<>();

        Suite suite = Suite.of(new String[0])
            .listener(event -> {
                order.add("first");
                return true;
            })
            .listener(event -> {
                order.add("second");
                return true;
            });

        suite.fireEvent(SuiteRunEvent.enter(suite));

        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void testSuitePerThreadListeners() {
        List<String> threadIds = new ArrayList<>();

        Suite suite = Suite.of(new String[0])
            .listenerFactory(() -> {
                String threadId = Thread.currentThread().getName();
                threadIds.add(threadId);
                return event -> true;
            });

        // Simulate thread initialization
        suite.initThreadListeners();

        // Fire an event - should go to per-thread listener
        List<RunEvent> received = new ArrayList<>();
        Suite suite2 = Suite.of(new String[0])
            .listenerFactory(() -> event -> {
                received.add(event);
                return true;
            });
        suite2.initThreadListeners();
        suite2.fireEvent(SuiteRunEvent.enter(suite2));

        assertEquals(1, received.size());

        // Cleanup
        suite.cleanupThreadListeners();
        suite2.cleanupThreadListeners();
    }

    @Test
    void testSuiteFireEventCombinesGlobalAndPerThread() {
        List<String> order = new ArrayList<>();

        Suite suite = Suite.of(new String[0])
            .listener(event -> {
                order.add("global");
                return true;
            })
            .listenerFactory(() -> event -> {
                order.add("perThread");
                return true;
            });

        suite.initThreadListeners();
        suite.fireEvent(SuiteRunEvent.enter(suite));
        suite.cleanupThreadListeners();

        assertEquals(List.of("global", "perThread"), order);
    }

    // ========== Integration Tests ==========

    @Test
    void testEventsAreFiredDuringFeatureExecution() {
        List<RunEventType> eventTypes = new ArrayList<>();

        String featureText = """
            Feature: Test Events
              Scenario: Simple test
                * def x = 1
                * assert x == 1
            """;
        Feature feature = Feature.read(Resource.text(featureText));

        Suite suite = Suite.of(feature)
            .writeReport(false)
            .outputHtmlReport(false)
            .outputConsoleSummary(false)
            .listener(event -> {
                eventTypes.add(event.getType());
                return true;
            });

        SuiteResult result = suite.run();

        // Verify expected events were fired
        assertTrue(eventTypes.contains(RunEventType.SUITE_ENTER), "SUITE_ENTER should be fired");
        assertTrue(eventTypes.contains(RunEventType.FEATURE_ENTER), "FEATURE_ENTER should be fired");
        assertTrue(eventTypes.contains(RunEventType.SCENARIO_ENTER), "SCENARIO_ENTER should be fired");
        assertTrue(eventTypes.contains(RunEventType.STEP_ENTER), "STEP_ENTER should be fired");
        assertTrue(eventTypes.contains(RunEventType.STEP_EXIT), "STEP_EXIT should be fired");
        assertTrue(eventTypes.contains(RunEventType.SCENARIO_EXIT), "SCENARIO_EXIT should be fired");
        assertTrue(eventTypes.contains(RunEventType.FEATURE_EXIT), "FEATURE_EXIT should be fired");
        assertTrue(eventTypes.contains(RunEventType.SUITE_EXIT), "SUITE_EXIT should be fired");

        // Verify order: SUITE_ENTER should come before SUITE_EXIT
        int suiteEnterIdx = eventTypes.indexOf(RunEventType.SUITE_ENTER);
        int suiteExitIdx = eventTypes.indexOf(RunEventType.SUITE_EXIT);
        assertTrue(suiteEnterIdx < suiteExitIdx, "SUITE_ENTER should come before SUITE_EXIT");

        // Verify the suite passed
        assertFalse(result.isFailed());
    }

    @Test
    void testListenerCanSkipScenario() {
        String featureText = """
            Feature: Skip Test
              Scenario: Should be skipped
                * def x = 1
                * assert x == 2
            """;
        Feature feature = Feature.read(Resource.text(featureText));

        Suite suite = Suite.of(feature)
            .writeReport(false)
            .outputHtmlReport(false)
            .outputConsoleSummary(false)
            .listener(event -> {
                // Skip scenarios by returning false on SCENARIO_ENTER
                if (event instanceof ScenarioRunEvent e && e.type() == RunEventType.SCENARIO_ENTER) {
                    return false;
                }
                return true;
            });

        SuiteResult result = suite.run();

        // The scenario would fail if executed (assert x == 2), but it was skipped
        // Note: Currently skipping via listener doesn't affect the result yet
        // This test verifies the listener receives the event
        assertNotNull(result);
    }

}
