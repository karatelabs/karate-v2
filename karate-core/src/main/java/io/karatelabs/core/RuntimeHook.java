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

/**
 * Interface for runtime hooks that can intercept execution at various points.
 * All methods return boolean to indicate whether execution should continue.
 */
public interface RuntimeHook {

    /**
     * Called before the suite starts execution.
     * @return true to continue, false to abort
     */
    default boolean beforeSuite(Suite suite) {
        return true;
    }

    /**
     * Called after the suite completes execution.
     */
    default void afterSuite(Suite suite) {
    }

    /**
     * Called before a feature starts execution.
     * @return true to continue, false to skip this feature
     */
    default boolean beforeFeature(FeatureRuntime fr) {
        return true;
    }

    /**
     * Called after a feature completes execution.
     */
    default void afterFeature(FeatureRuntime fr) {
    }

    /**
     * Called before a scenario starts execution.
     * @return true to continue, false to skip this scenario
     */
    default boolean beforeScenario(ScenarioRuntime sr) {
        return true;
    }

    /**
     * Called after a scenario completes execution.
     */
    default void afterScenario(ScenarioRuntime sr) {
    }

    /**
     * Called before a step starts execution.
     * @return true to continue, false to skip this step
     */
    default boolean beforeStep(Step step, ScenarioRuntime sr) {
        return true;
    }

    /**
     * Called after a step completes execution.
     */
    default void afterStep(StepResult result, ScenarioRuntime sr) {
    }

}
