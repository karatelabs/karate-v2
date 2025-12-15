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
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Step;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.log.LogContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class ScenarioRuntime implements Callable<ScenarioResult> {

    private final FeatureRuntime featureRuntime;
    private final Scenario scenario;
    private final KarateJs karate;
    private final StepExecutor executor;
    private final ScenarioResult result;
    private final Map<String, Object> configSettings;

    private Step currentStep;
    private boolean stopped;
    private boolean aborted;
    private Throwable error;

    public ScenarioRuntime(FeatureRuntime featureRuntime, Scenario scenario) {
        this.featureRuntime = featureRuntime;
        this.scenario = scenario;

        // KarateJs owns the Engine and HTTP infrastructure
        Resource featureResource = scenario.getFeature().getResource();
        this.karate = new KarateJs(featureResource);

        this.executor = new StepExecutor(this);
        this.result = new ScenarioResult(scenario);
        this.configSettings = new HashMap<>();

        initEngine();
    }

    /**
     * Constructor for standalone execution without FeatureRuntime.
     */
    public ScenarioRuntime(KarateJs karate, Scenario scenario) {
        this.featureRuntime = null;
        this.scenario = scenario;
        this.karate = karate;
        this.executor = new StepExecutor(this);
        this.result = new ScenarioResult(scenario);
        this.configSettings = new HashMap<>();
    }

    private void initEngine() {
        // KarateJs already sets up "karate" object
        // Add scenario-specific variables

        // Apply config variables from Suite (base layer)
        if (featureRuntime != null && featureRuntime.getSuite() != null) {
            Map<String, Object> configVars = featureRuntime.getSuite().getConfigVariables();
            for (var entry : configVars.entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
        }

        // Inherit parent variables if called from another feature
        if (featureRuntime != null && featureRuntime.getCaller() != null) {
            inheritVariables();
        }

        // Apply call arguments if present
        if (featureRuntime != null && featureRuntime.getCallArg() != null) {
            for (var entry : featureRuntime.getCallArg().entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
        }

        // Set example data for outline scenarios
        if (scenario.getExampleData() != null) {
            for (var entry : scenario.getExampleData().entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void inheritVariables() {
        FeatureRuntime caller = featureRuntime.getCaller();
        if (caller != null && caller.getLastExecuted() != null) {
            Map<String, Object> parentVars = caller.getLastExecuted().getAllVariables();
            for (var entry : parentVars.entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public ScenarioResult call() {
        LogContext.set(new LogContext());
        result.setStartTime(System.currentTimeMillis());
        result.setThreadName(Thread.currentThread().getName());

        try {
            beforeScenario();

            List<Step> steps = scenario.getStepsIncludingBackground();
            for (Step step : steps) {
                if (stopped || aborted) {
                    // Mark remaining steps as skipped
                    StepResult sr = StepResult.skipped(step, System.currentTimeMillis());
                    result.addStepResult(sr);
                    continue;
                }

                currentStep = step;
                StepResult sr = executor.execute(step);
                result.addStepResult(sr);

                if (sr.isFailed()) {
                    stopped = true;
                    error = sr.getError();
                }
            }

        } catch (Throwable t) {
            error = t;
            stopped = true;
        } finally {
            afterScenario();
            result.setEndTime(System.currentTimeMillis());
            LogContext.clear();
        }

        return result;
    }

    private void beforeScenario() {
        if (featureRuntime != null && featureRuntime.getSuite() != null) {
            for (RuntimeHook hook : featureRuntime.getSuite().getHooks()) {
                if (!hook.beforeScenario(this)) {
                    // Hook returned false - skip this scenario
                    stopped = true;
                    return;
                }
            }
        }
    }

    private void afterScenario() {
        if (featureRuntime != null && featureRuntime.getSuite() != null) {
            for (RuntimeHook hook : featureRuntime.getSuite().getHooks()) {
                hook.afterScenario(this);
            }
        }
    }

    // ========== Execution Context ==========

    public Object eval(String expression) {
        return karate.engine.eval(expression);
    }

    public void setVariable(String name, Object value) {
        karate.engine.put(name, value);
    }

    public Object getVariable(String name) {
        return karate.engine.get(name);
    }

    public Map<String, Object> getAllVariables() {
        return karate.engine.getBindings();
    }

    public HttpRequestBuilder getHttp() {
        return karate.http;
    }

    public KarateJs getKarate() {
        return karate;
    }

    public void configure(String key, Object value) {
        configSettings.put(key, value);
        // Apply configuration to relevant components
        switch (key) {
            case "ssl" -> {
                // SSL configuration
            }
            case "followRedirects" -> {
                // Redirect configuration
            }
            case "connectTimeout" -> {
                // Connection timeout
            }
            case "readTimeout" -> {
                // Read timeout
            }
            // Add more configure options as needed
        }
    }

    public Object getConfig(String key) {
        return configSettings.get(key);
    }

    // ========== State Access ==========

    public Scenario getScenario() {
        return scenario;
    }

    public FeatureRuntime getFeatureRuntime() {
        return featureRuntime;
    }

    public ScenarioResult getResult() {
        return result;
    }

    public Step getCurrentStep() {
        return currentStep;
    }

    public boolean isStopped() {
        return stopped;
    }

    public boolean isAborted() {
        return aborted;
    }

    public Throwable getError() {
        return error;
    }

    public void abort() {
        this.aborted = true;
    }

    public void stop() {
        this.stopped = true;
    }

}
