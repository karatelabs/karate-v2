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

import java.util.Map;

/**
 * Base interface for runtime events fired during test execution.
 * Extensible - plugins and extensions can implement this interface to contribute
 * custom events to the central event bus.
 *
 * <p>Usage with pattern matching:</p>
 * <pre>
 * listener = event -> switch (event) {
 *     case FeatureRunEvent e -> handleFeature(e.source(), e.result());
 *     case ScenarioRunEvent e -> handleScenario(e.source(), e.result());
 *     case StepRunEvent e -> handleStep(e.step(), e.result());
 *     default -> true;  // Handle unknown event types gracefully
 * };
 * </pre>
 */
public interface RunEvent {

    /**
     * Returns the event type.
     */
    RunEventType getType();

    /**
     * Returns the suite associated with this event, or null if not available.
     */
    Suite getSuite();

    /**
     * Returns true if this event is for a top-level execution (not a called feature/scenario).
     */
    boolean isTopLevel();

    /**
     * Returns the call depth (0 for top-level, 1 for first nested call, etc.).
     */
    int getCallDepth();

    /**
     * Serializes this event to a map for JSONL output.
     */
    Map<String, Object> toJson();

    // ========== Helper methods for computing depth ==========

    static int computeCallDepth(FeatureRuntime fr) {
        if (fr == null) {
            return 0;
        }
        int depth = 0;
        while (fr.getCaller() != null) {
            depth++;
            fr = fr.getCaller();
        }
        return depth;
    }

    static boolean computeIsTopLevel(FeatureRuntime fr) {
        return fr == null || fr.getCaller() == null;
    }

}
