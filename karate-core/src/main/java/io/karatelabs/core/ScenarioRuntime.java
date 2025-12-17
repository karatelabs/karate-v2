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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private boolean skipBackground;
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
            Map<String, Object> exampleData = scenario.getExampleData();
            for (var entry : exampleData.entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
            // Set __row to the full example data map
            karate.engine.put("__row", exampleData);
            // Set __num to the example index (0-based)
            karate.engine.put("__num", scenario.getExampleIndex());
        }

        // Wire up karate.setup() and karate.setupOnce() functions
        karate.setSetupProvider(this::executeSetup);
        karate.setSetupOnceProvider(this::executeSetupOnce);
    }

    /**
     * Execute the @setup scenario and return all its variables.
     */
    private Map<String, Object> executeSetup(String name) {
        if (featureRuntime == null) {
            throw new RuntimeException("karate.setup() requires a feature context");
        }
        Scenario setupScenario = scenario.getFeature().getSetup(name);
        if (setupScenario == null) {
            String message = "no scenario found with @setup tag";
            if (name != null) {
                message = message + " and name '" + name + "'";
            }
            throw new RuntimeException(message);
        }
        // Run the setup scenario without background
        ScenarioRuntime sr = new ScenarioRuntime(featureRuntime, setupScenario);
        sr.setSkipBackground(true);
        sr.call();
        return sr.getAllVariables();
    }

    /**
     * Execute the @setup scenario with caching (only runs once per feature).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSetupOnce(String name) {
        if (featureRuntime == null) {
            throw new RuntimeException("karate.setupOnce() requires a feature context");
        }
        String cacheKey = name == null ? "__default__" : name;
        Map<String, Object> cached = (Map<String, Object>) featureRuntime.SETUPONCE_CACHE.get(cacheKey);
        if (cached != null) {
            // Return a shallow copy to prevent modifications affecting other scenarios
            return new HashMap<>(cached);
        }
        synchronized (featureRuntime.SETUPONCE_CACHE) {
            // Double-check after acquiring lock
            cached = (Map<String, Object>) featureRuntime.SETUPONCE_CACHE.get(cacheKey);
            if (cached != null) {
                return new HashMap<>(cached);
            }
            Map<String, Object> result = executeSetup(name);
            featureRuntime.SETUPONCE_CACHE.put(cacheKey, result);
            return new HashMap<>(result);
        }
    }

    private void inheritVariables() {
        boolean sharedScope = featureRuntime.isSharedScope();
        // First check for callerScenario (the currently executing scenario that made the call)
        ScenarioRuntime callerScenario = featureRuntime.getCallerScenario();
        if (callerScenario != null) {
            Map<String, Object> parentVars = callerScenario.getAllVariables();
            for (var entry : parentVars.entrySet()) {
                Object value = entry.getValue();
                if (!sharedScope) {
                    // Isolated scope - shallow copy maps and lists so mutations don't affect parent
                    value = shallowCopy(value);
                }
                karate.engine.put(entry.getKey(), value);
            }
            return;
        }
        // Fallback to lastExecuted for other cases (e.g., sequential scenarios in same feature)
        FeatureRuntime caller = featureRuntime.getCaller();
        if (caller != null && caller.getLastExecuted() != null) {
            Map<String, Object> parentVars = caller.getLastExecuted().getAllVariables();
            for (var entry : parentVars.entrySet()) {
                Object value = entry.getValue();
                if (!sharedScope) {
                    value = shallowCopy(value);
                }
                karate.engine.put(entry.getKey(), value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object shallowCopy(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        } else if (value instanceof List) {
            return new ArrayList<>((List<Object>) value);
        }
        return value;
    }

    @Override
    public ScenarioResult call() {
        LogContext.set(new LogContext());
        result.setStartTime(System.currentTimeMillis());
        // For virtual threads, use the thread ID to distinguish lanes in timeline
        Thread currentThread = Thread.currentThread();
        String threadName = currentThread.getName();
        if (threadName == null || threadName.isEmpty() || "main".equals(threadName) || threadName.isBlank()) {
            threadName = "thread-" + currentThread.threadId();
        }
        result.setThreadName(threadName);

        try {
            beforeScenario();

            List<Step> steps = skipBackground ? scenario.getSteps() : scenario.getStepsIncludingBackground();
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
            case "ssl", "proxy", "readTimeout", "connectTimeout", "followRedirects" -> {
                // HTTP client configuration - delegate to client
                karate.client.config(key, value);
            }
            case "headers" -> {
                // Set default headers on HTTP request builder
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> headers = (Map<String, Object>) value;
                    karate.http.headers(headers);
                }
            }
            case "charset" -> {
                // Set default charset for HTTP requests
                if (value instanceof String) {
                    karate.http.charset((String) value);
                }
            }
            // Additional configure options can be added as needed
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

    public void setSkipBackground(boolean skipBackground) {
        this.skipBackground = skipBackground;
    }

    public boolean isSkipBackground() {
        return skipBackground;
    }

}
