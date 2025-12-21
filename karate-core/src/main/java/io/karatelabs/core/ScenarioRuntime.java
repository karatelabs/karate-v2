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
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Step;
import io.karatelabs.io.http.HttpRequestBuilder;
import io.karatelabs.log.JvmLogger;
import io.karatelabs.log.LogContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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

    // Signal/listen mechanism for async process integration
    private volatile Object listenResult;
    private CountDownLatch listenLatch = new CountDownLatch(1);

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

        // Wire up karate.abort() for standalone execution
        karate.setAbortHandler(this::abort);
    }

    private void initEngine() {
        // Wire up karate.* functions FIRST so they're available during config evaluation
        karate.setSetupProvider(this::executeSetup);
        karate.setSetupOnceProvider(this::executeSetupOnce);
        karate.setAbortHandler(this::abort);
        karate.setCallProvider(this::executeJsCall);
        karate.setCallOnceProvider(this::executeJsCallOnce);
        karate.setCallSingleProvider(this::executeCallSingle);
        karate.setInfoProvider(this::getScenarioInfo);
        karate.setScenarioProvider(this::getScenarioData);
        karate.setSignalConsumer(this::setListenResult);

        // Set karate.env before config evaluation
        if (featureRuntime != null && featureRuntime.getSuite() != null) {
            karate.setEnv(featureRuntime.getSuite().getEnv());
        }

        // Evaluate config (only for top-level scenarios, not called features)
        if (featureRuntime != null && featureRuntime.getSuite() != null && featureRuntime.getCaller() == null) {
            evalConfig();
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
    }

    /**
     * Evaluate karate-config.js (and env-specific config) in this scenario's context.
     * This allows callSingle and other karate.* functions to work during config.
     */
    @SuppressWarnings("unchecked")
    private void evalConfig() {
        Suite suite = featureRuntime.getSuite();

        // Evaluate main config
        String configContent = suite.getConfigContent();
        if (configContent != null) {
            evalConfigJs(configContent, "karate-config.js");
        }

        // Evaluate env-specific config
        String configEnvContent = suite.getConfigEnvContent();
        if (configEnvContent != null) {
            String envName = suite.getEnv();
            evalConfigJs(configEnvContent, "karate-config-" + envName + ".js");
        }
    }

    /**
     * Evaluate a config JS and apply its result to the engine.
     * Supports multiple patterns:
     * 1. Function definition only: function fn() { return {...}; } - will call it
     * 2. Self-executing: function fn() { return {...}; } fn(); - returns result directly
     * 3. Object literal: ({ key: value }) - already an object
     */
    @SuppressWarnings("unchecked")
    private void evalConfigJs(String js, String displayName) {
        try {
            Object result;

            // Try wrapping in parentheses first (handles function definitions)
            try {
                Object fn = karate.engine.eval("(" + js + ")");
                if (fn instanceof io.karatelabs.js.JsCallable) {
                    // It's a function - invoke it
                    result = ((io.karatelabs.js.JsCallable) fn).call(null);
                } else {
                    // Already evaluated to a value (e.g., object literal)
                    result = fn;
                }
            } catch (Exception e) {
                // If parentheses failed, try evaluating directly (self-invoking pattern)
                result = karate.engine.eval(js);
            }

            // Apply config variables to engine
            if (result instanceof Map) {
                Map<String, Object> configVars = (Map<String, Object>) result;
                for (var entry : configVars.entrySet()) {
                    karate.engine.put(entry.getKey(), entry.getValue());
                }
                JvmLogger.debug("Evaluated {}: {} variables", displayName, configVars.size());
            } else if (result != null) {
                JvmLogger.warn("{} did not return an object, got: {}", displayName, result.getClass().getSimpleName());
            }
        } catch (Exception e) {
            JvmLogger.warn("Failed to evaluate {}: {}", displayName, e.getMessage());
            throw new RuntimeException("Config evaluation failed: " + displayName + " - " + e.getMessage(), e);
        }
    }

    /**
     * Returns scenario info map for karate.info.
     * Contains: scenarioName, scenarioDescription, featureDir, featureFileName, errorMessage
     */
    private Map<String, Object> getScenarioInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        Resource featureResource = scenario.getFeature().getResource();
        if (featureResource.isFile() && featureResource.getPath() != null) {
            info.put("featureDir", featureResource.getPath().getParent().toString());
            info.put("featureFileName", featureResource.getPath().getFileName().toString());
        }
        info.put("scenarioName", scenario.getName());
        info.put("scenarioDescription", scenario.getDescription());
        String errorMessage = error == null ? null : error.getMessage();
        info.put("errorMessage", errorMessage);
        return info;
    }

    /**
     * Returns scenario data map for karate.scenario.
     * V1 compatible fields: name, sectionIndex, exampleIndex, exampleData, line, description
     */
    private Map<String, Object> getScenarioData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", scenario.getName());
        data.put("description", scenario.getDescription());
        data.put("line", scenario.getLine());
        // Section index: position of this scenario in the feature
        data.put("sectionIndex", scenario.getSection().getIndex());
        // Example index: -1 if not a scenario outline, otherwise the example row index
        data.put("exampleIndex", scenario.getExampleIndex());
        Map<String, Object> exampleData = scenario.getExampleData();
        if (exampleData != null) {
            data.put("exampleData", exampleData);
        }
        return data;
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

    /**
     * Execute a feature via karate.call() and return its result variables.
     * This is used for JavaScript calls like: karate.call('other.feature', { arg: 'value' })
     *
     * Supports call-by-tag syntax:
     * - call('file.feature@name=tagvalue') - call feature, run only scenario with matching tag
     * - call('@tagname') - call scenario in same file by tag
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeJsCall(String path, Object arg) {
        if (featureRuntime == null) {
            throw new RuntimeException("karate.call() requires a feature context");
        }

        // Parse path and tag selector
        String featurePath;
        String tagSelector;
        Feature calledFeature;

        if (path.startsWith("@")) {
            // Same-file tag call: call('@tagname')
            featurePath = null;
            tagSelector = path;  // Keep the @ prefix
            calledFeature = featureRuntime.getFeature();
        } else {
            // Check for tag suffix: file.feature@tag
            int tagPos = path.indexOf(".feature@");
            if (tagPos != -1) {
                featurePath = path.substring(0, tagPos + 8);  // "file.feature"
                tagSelector = "@" + path.substring(tagPos + 9);  // "@tag"
                Resource calledResource = featureRuntime.resolve(featurePath);
                calledFeature = Feature.read(calledResource);
            } else {
                // Normal call without tag
                featurePath = path;
                tagSelector = null;
                Resource calledResource = featureRuntime.resolve(featurePath);
                calledFeature = Feature.read(calledResource);
            }
        }

        // Convert arg to Map if needed
        Map<String, Object> callArg = null;
        if (arg != null) {
            if (arg instanceof Map) {
                callArg = (Map<String, Object>) arg;
            } else {
                throw new RuntimeException("karate.call() arg must be a map/object, got: " + arg.getClass());
            }
        }

        // Create nested FeatureRuntime with isolated scope (always isolated for karate.call())
        FeatureRuntime nestedFr = new FeatureRuntime(
                featureRuntime.getSuite(),
                calledFeature,
                featureRuntime,
                this,
                false,  // Isolated scope
                callArg,
                tagSelector
        );

        // Execute the called feature
        nestedFr.call();

        // Return result variables from the last executed scenario
        if (nestedFr.getLastExecuted() != null) {
            return nestedFr.getLastExecuted().getAllVariables();
        }
        return new HashMap<>();
    }

    /**
     * Execute karate.callonce() - runs a feature once per FeatureRuntime and caches the result.
     * Uses the same cache as the callonce keyword.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeJsCallOnce(String path, Object arg) {
        if (featureRuntime == null) {
            throw new RuntimeException("karate.callonce() requires a feature context");
        }

        // Use the same cache key format as the keyword: "callonce:call read('path')"
        String cacheKey = "callonce:call read('" + path + "')";

        // Get cache from Suite or FeatureRuntime
        Map<String, Object> cache = featureRuntime.getSuite() != null
                ? featureRuntime.getSuite().getCallOnceCache()
                : featureRuntime.CALLONCE_CACHE;

        // Check cache first
        Map<String, Object> cached = (Map<String, Object>) cache.get(cacheKey);
        if (cached != null) {
            return new HashMap<>(cached);
        }

        // Not cached - execute the call
        Map<String, Object> result = executeJsCall(path, arg);

        // Cache the result
        cache.put(cacheKey, new HashMap<>(result));

        return result;
    }

    /**
     * Execute karate.callSingle() - runs a file once per Suite and caches the result.
     * Uses Suite-level locking to ensure thread-safe execution in parallel scenarios.
     *
     * Flow:
     * 1. Check cache (lock-free for fast path)
     * 2. If not cached, acquire Suite lock
     * 3. Double-check cache (another thread may have cached while waiting)
     * 4. Execute and cache result
     * 5. Return deep copy to prevent cross-thread mutation
     *
     * Exceptions are cached and re-thrown on subsequent calls.
     */
    private Object executeCallSingle(String path, Object arg) {
        if (featureRuntime == null || featureRuntime.getSuite() == null) {
            throw new RuntimeException("karate.callSingle() requires a Suite context");
        }

        Suite suite = featureRuntime.getSuite();
        Map<String, Object> cache = suite.getCallSingleCache();
        ReentrantLock lock = suite.getCallSingleLock();

        // Fast path: check if already cached (no locking needed)
        if (cache.containsKey(path)) {
            JvmLogger.trace("[callSingle] cache hit: {}", path);
            return unwrapCachedResult(cache.get(path));
        }

        // Slow path: acquire lock and execute
        long startWait = System.currentTimeMillis();
        JvmLogger.debug("[callSingle] waiting for lock: {}", path);
        lock.lock();
        try {
            // Double-check: another thread may have cached while we waited
            if (cache.containsKey(path)) {
                long waitTime = System.currentTimeMillis() - startWait;
                JvmLogger.info("[callSingle] lock acquired after {}ms, cache hit: {}", waitTime, path);
                return unwrapCachedResult(cache.get(path));
            }

            // This thread is the winner - execute the call
            JvmLogger.info("[callSingle] >> executing: {}", path);
            long startExec = System.currentTimeMillis();

            Object result;
            try {
                result = executeCallSingleInternal(path, arg);
            } catch (Exception e) {
                // Cache the exception so subsequent calls also fail fast
                JvmLogger.warn("[callSingle] caching exception for: {} - {}", path, e.getMessage());
                cache.put(path, new CallSingleException(e));
                throw e;
            }

            // Cache the result
            cache.put(path, result);
            long execTime = System.currentTimeMillis() - startExec;
            JvmLogger.info("[callSingle] << cached in {}ms: {}", execTime, path);

            return deepCopy(result);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal execution of callSingle - reads and evaluates the file.
     */
    private Object executeCallSingleInternal(String path, Object arg) {
        // Read the file using the engine (which has access to the read function)
        Object content = karate.engine.eval("read('" + path.replace("'", "\\'") + "')");

        if (content instanceof Feature) {
            // Feature file - execute it
            Feature calledFeature = (Feature) content;
            @SuppressWarnings("unchecked")
            Map<String, Object> callArg = arg != null ? (Map<String, Object>) arg : null;

            FeatureRuntime nestedFr = new FeatureRuntime(
                    featureRuntime.getSuite(),
                    calledFeature,
                    featureRuntime,
                    this,
                    false,  // Isolated scope
                    callArg,
                    null    // No tag selector
            );
            FeatureResult fr = nestedFr.call();

            // Check if the feature failed
            if (fr.isFailed()) {
                String failureMsg = fr.getScenarioResults().stream()
                        .filter(ScenarioResult::isFailed)
                        .findFirst()
                        .map(ScenarioResult::getFailureMessage)
                        .orElse("callSingle feature failed");
                throw new RuntimeException("callSingle failed: " + path + " - " + failureMsg);
            }

            if (nestedFr.getLastExecuted() != null) {
                return nestedFr.getLastExecuted().getAllVariables();
            }
            return new HashMap<>();
        } else if (content instanceof io.karatelabs.js.JsCallable) {
            // JavaScript function - invoke it with the arg
            io.karatelabs.js.JsCallable fn = (io.karatelabs.js.JsCallable) content;
            return fn.call(null, arg == null ? new Object[0] : new Object[]{arg});
        } else {
            // Return as-is (JSON, text, etc.)
            return content;
        }
    }

    /**
     * Unwrap cached result - throws if it's a cached exception.
     */
    private Object unwrapCachedResult(Object cached) {
        if (cached instanceof CallSingleException) {
            throw new RuntimeException(((CallSingleException) cached).cause.getMessage(),
                    ((CallSingleException) cached).cause);
        }
        return deepCopy(cached);
    }

    /**
     * Deep copy to prevent cross-thread mutation of cached data.
     */
    @SuppressWarnings("unchecked")
    private Object deepCopy(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        // Primitives, strings, etc. are immutable - return as-is
        return value;
    }

    /**
     * Wrapper for cached exceptions to distinguish from null results.
     */
    private static class CallSingleException {
        final Exception cause;
        CallSingleException(Exception cause) {
            this.cause = cause;
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
            // Handle @fail tag - invert pass/fail result
            if (scenario.isFail()) {
                result.applyFailTag();
            }
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

    public io.karatelabs.js.Engine getEngine() {
        return karate.engine;
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

    // ========== Signal/Listen Mechanism ==========

    /**
     * Set the listen result (called by karate.signal()).
     * Triggers any waiting listen() call to complete.
     */
    public void setListenResult(Object result) {
        this.listenResult = result;
        this.listenLatch.countDown();
    }

    /**
     * Wait for a signal with timeout.
     * Returns the signaled result or throws on timeout.
     */
    public Object waitForListenResult(long timeoutMs) {
        try {
            if (listenLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Object result = listenResult;
                // Reset for potential reuse
                listenResult = null;
                listenLatch = new CountDownLatch(1);
                return result;
            }
            throw new RuntimeException("listen timed out after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("listen interrupted", e);
        }
    }

    /**
     * Get the current listen result without waiting.
     */
    public Object getListenResult() {
        return listenResult;
    }

}
