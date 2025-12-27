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

/**
 * Adapter that wraps a legacy {@link RuntimeHook} as a {@link RunListener}.
 * This provides backward compatibility for existing RuntimeHook implementations.
 */
public class RuntimeHookAdapter implements RunListener {

    private final RuntimeHook hook;

    public RuntimeHookAdapter(RuntimeHook hook) {
        this.hook = hook;
    }

    @Override
    public boolean onEvent(RunEvent event) {
        return switch (event) {
            case SuiteRunEvent e -> switch (e.type()) {
                case SUITE_ENTER -> hook.beforeSuite(e.source());
                case SUITE_EXIT -> {
                    hook.afterSuite(e.source());
                    yield true;
                }
                default -> true;
            };
            case FeatureRunEvent e -> switch (e.type()) {
                case FEATURE_ENTER -> hook.beforeFeature(e.source());
                case FEATURE_EXIT -> {
                    hook.afterFeature(e.source());
                    yield true;
                }
                default -> true;
            };
            case ScenarioRunEvent e -> switch (e.type()) {
                case SCENARIO_ENTER -> hook.beforeScenario(e.source());
                case SCENARIO_EXIT -> {
                    hook.afterScenario(e.source());
                    yield true;
                }
                default -> true;
            };
            case StepRunEvent e -> switch (e.type()) {
                case STEP_ENTER -> hook.beforeStep(e.step(), e.scenarioRuntime());
                case STEP_EXIT -> {
                    hook.afterStep(e.result(), e.scenarioRuntime());
                    yield true;
                }
                default -> true;
            };
            case ErrorRunEvent e -> true;
            case ProgressRunEvent e -> true;
            default -> true;  // Unknown event types pass through
        };
    }

    /**
     * Get the wrapped RuntimeHook.
     */
    public RuntimeHook getHook() {
        return hook;
    }

}
