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

import io.karatelabs.common.Json;
import io.karatelabs.common.Resource;
import io.karatelabs.common.StringUtils;
import io.karatelabs.driver.Driver;
import io.karatelabs.driver.cdp.CdpDriver;
import io.karatelabs.driver.cdp.CdpDriverOptions;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.FeatureSection;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.ScenarioOutline;
import io.karatelabs.gherkin.Step;
import io.karatelabs.gherkin.Tag;
import io.karatelabs.http.HttpRequestBuilder;
import io.karatelabs.output.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ScenarioRuntime implements Callable<ScenarioResult>, KarateJsContext {

    private static final Logger logger = LogContext.RUNTIME_LOGGER;

    private final FeatureRuntime featureRuntime;
    private final Scenario scenario;
    private final KarateJs karate;
    private final StepExecutor executor;
    private final ScenarioResult result;
    private final KarateConfig config;

    private Step currentStep;
    private boolean stopped;
    private boolean aborted;
    private boolean skipBackground;
    private Throwable error;
    private boolean hasIgnoredFailure;
    private Throwable firstIgnoredError;

    // Signal/listen mechanism for async process integration
    private volatile Object listenResult;
    private CountDownLatch listenLatch = new CountDownLatch(1);

    // Browser driver (lazily initialized)
    private Driver driver;

    // Performance testing - tracks the previous HTTP request's perf event
    // Events are held until the next HTTP request or scenario end, so that
    // assertion failures can be attributed to the preceding HTTP request
    private PerfEvent prevPerfEvent;
    private boolean driverFromProvider;  // true if driver came from provider (use release, not quit)

    // Cookie jar for auto-sending responseCookies on subsequent requests (V1 compatibility)
    private final Map<String, Map<String, Object>> cookieJar = new LinkedHashMap<>();

    public ScenarioRuntime(FeatureRuntime featureRuntime, Scenario scenario) {
        this.featureRuntime = featureRuntime;
        this.scenario = scenario;

        // KarateJs owns the Engine and HTTP infrastructure
        Resource featureResource = scenario.getFeature().getResource();
        this.karate = new KarateJs(featureResource);

        this.executor = new StepExecutor(this);
        this.result = new ScenarioResult(scenario);
        this.config = new KarateConfig();

        // Initialize HTTP builder with default charset from config (V1 compatibility)
        if (config.getCharset() != null) {
            karate.http.charset(config.getCharset().name());
        }

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
        this.config = new KarateConfig();

        // Initialize HTTP builder with default charset from config (V1 compatibility)
        if (config.getCharset() != null) {
            karate.http.charset(config.getCharset().name());
        }

        // Wire up KarateJsContext - all karate.* API methods use this
        karate.setContext(this);
    }

    private void initEngine() {
        // Wire up KarateJsContext - all karate.* API methods use this
        karate.setContext(this);

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
            Map<String, Object> callArg = featureRuntime.getCallArg();
            // Set __arg to the full argument map (V1 compatibility)
            karate.engine.putRootBinding("__arg", callArg);
            // Also spread individual keys as variables
            for (var entry : callArg.entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
        }

        // Set __loop for loop calls (V1 compatibility)
        if (featureRuntime != null && featureRuntime.getLoopIndex() >= 0) {
            karate.engine.putRootBinding("__loop", featureRuntime.getLoopIndex());
        }

        // Set example data for outline scenarios
        if (scenario.getExampleData() != null) {
            Map<String, Object> exampleData = scenario.getExampleData();
            for (var entry : exampleData.entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
            // Set __row to the full example data map
            karate.engine.putRootBinding("__row", exampleData);
            // Set __num to the example index (0-based)
            karate.engine.putRootBinding("__num", scenario.getExampleIndex());
        }
    }

    /**
     * Evaluate karate-base.js, karate-config.js (and env-specific config) in this scenario's context.
     * This allows callSingle and other karate.* functions to work during config.
     */
    @SuppressWarnings("unchecked")
    private void evalConfig() {
        Suite suite = featureRuntime.getSuite();

        // Evaluate karate-base.js first (shared functions available to configs)
        String baseContent = suite.getBaseContent();
        if (baseContent != null) {
            evalConfigJs(baseContent, "karate-base.js");
        }

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
                logger.debug("Evaluated {}: {} variables", displayName, configVars.size());
            } else if (result != null) {
                logger.warn("{} did not return an object, got: {}", displayName, result.getClass().getSimpleName());
            }
        } catch (Exception e) {
            logger.warn("Failed to evaluate {}: {}", displayName, e.getMessage());
            throw new RuntimeException("Config evaluation failed: " + displayName + " - " + e.getMessage(), e);
        }
    }

    /**
     * Execute the @setup scenario and return all its variables.
     */
    public Map<String, Object> executeSetup(String name) {
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
    public Map<String, Object> executeSetupOnce(String name) {
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
    public Map<String, Object> executeJsCall(String path, Object arg) {
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
     * Uses double-check locking to ensure thread-safe execution in parallel scenarios.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeJsCallOnce(String path, Object arg) {
        if (featureRuntime == null) {
            throw new RuntimeException("karate.callonce() requires a feature context");
        }

        // Use the same cache key format as the keyword: "callonce:call read('path')"
        String cacheKey = "callonce:call read('" + path + "')";

        // Use feature-level cache (not suite-level) - callOnce is scoped per feature
        Map<String, Object> cache = featureRuntime.CALLONCE_CACHE;
        java.util.concurrent.locks.ReentrantLock lock = featureRuntime.getCallOnceLock();

        // Fast path - check cache without lock
        Map<String, Object> cached = (Map<String, Object>) cache.get(cacheKey);
        if (cached != null) {
            // Deep copy to prevent cross-scenario mutation
            return (Map<String, Object>) deepCopy(cached);
        }

        // Slow path - acquire lock for execution
        lock.lock();
        try {
            // Double-check after acquiring lock
            Map<String, Object> rechecked = (Map<String, Object>) cache.get(cacheKey);
            if (rechecked != null) {
                return (Map<String, Object>) deepCopy(rechecked);
            }

            // Not cached - execute the call
            Map<String, Object> result = executeJsCall(path, arg);

            // Cache a deep copy to prevent the caller from mutating the cache
            cache.put(cacheKey, (Map<String, Object>) deepCopy(result));

            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Execute karate.callSingle() - runs a file once per Suite and caches the result.
     * Uses Suite-level locking to ensure thread-safe execution in parallel scenarios.
     *
     * Flow:
     * 1. Check in-memory cache (lock-free for fast path)
     * 2. If callSingleCache is configured, check disk cache
     * 3. If not cached, acquire Suite lock
     * 4. Double-check cache (another thread may have cached while waiting)
     * 5. Execute and cache result (in-memory and optionally to disk)
     * 6. Return deep copy to prevent cross-thread mutation
     *
     * Disk caching (configure callSingleCache):
     * - { minutes: 15 } - cache to disk for 15 minutes (default dir: target)
     * - { minutes: 15, dir: 'some/folder' } - custom cache directory
     * - Only JSON-like results (Map/List) are persisted to disk
     *
     * Exceptions are cached and re-thrown on subsequent calls.
     */
    public Object executeCallSingle(String path, Object arg) {
        if (featureRuntime == null || featureRuntime.getSuite() == null) {
            throw new RuntimeException("karate.callSingle() requires a Suite context");
        }

        Suite suite = featureRuntime.getSuite();
        Map<String, Object> cache = suite.getCallSingleCache();
        ReentrantLock lock = suite.getCallSingleLock();

        // Get disk cache settings from config
        int cacheMinutes = config.getCallSingleCacheMinutes();
        String cacheDir = config.getCallSingleCacheDir();
        if (cacheDir == null || cacheDir.isEmpty()) {
            cacheDir = "target";  // Default cache directory
        }

        // Fast path: check if already in memory cache (no locking needed)
        if (cache.containsKey(path)) {
            logger.trace("[callSingle] memory cache hit: {}", path);
            return unwrapCachedResult(cache.get(path));
        }

        // Slow path: acquire lock and execute
        long startWait = System.currentTimeMillis();
        logger.debug("[callSingle] waiting for lock: {}", path);
        lock.lock();
        try {
            // Double-check: another thread may have cached while we waited
            if (cache.containsKey(path)) {
                long waitTime = System.currentTimeMillis() - startWait;
                logger.info("[callSingle] lock acquired after {}ms, memory cache hit: {}", waitTime, path);
                return unwrapCachedResult(cache.get(path));
            }

            Object result = null;
            File cacheFile = null;

            // Check disk cache if configured
            if (cacheMinutes > 0) {
                String cleanedName = StringUtils.toIdString(path);
                cacheFile = new File(cacheDir, cleanedName + ".txt");
                long staleThreshold = System.currentTimeMillis() - (cacheMinutes * 60L * 1000L);

                if (cacheFile.exists()) {
                    long lastModified = cacheFile.lastModified();
                    if (lastModified > staleThreshold) {
                        try {
                            String json = Files.readString(cacheFile.toPath());
                            result = Json.parseLenient(json);
                            logger.info("[callSingle] disk cache hit: {}", cacheFile);
                        } catch (IOException e) {
                            logger.warn("[callSingle] disk cache read failed: {} - {}", cacheFile, e.getMessage());
                        }
                    } else {
                        logger.info("[callSingle] disk cache stale: {} (modified {}ms ago, threshold {}min)",
                                cacheFile, System.currentTimeMillis() - lastModified, cacheMinutes);
                    }
                } else {
                    logger.debug("[callSingle] disk cache miss, will create: {}", cacheFile);
                }
            }

            // Execute if not found in disk cache
            if (result == null) {
                logger.info("[callSingle] >> executing: {}", path);
                long startExec = System.currentTimeMillis();

                try {
                    result = executeCallSingleInternal(path, arg);
                } catch (Exception e) {
                    // Cache the exception so subsequent calls also fail fast
                    logger.warn("[callSingle] caching exception for: {} - {}", path, e.getMessage());
                    cache.put(path, new CallSingleException(e));
                    throw e;
                }

                long execTime = System.currentTimeMillis() - startExec;
                logger.info("[callSingle] << executed in {}ms: {}", execTime, path);

                // Write to disk cache if configured and result is JSON-like
                if (cacheMinutes > 0 && cacheFile != null) {
                    if (Json.isMapOrList(result)) {
                        try {
                            cacheFile.getParentFile().mkdirs();
                            String json = StringUtils.formatJson(result, false, false, false);
                            Files.writeString(cacheFile.toPath(), json);
                            logger.info("[callSingle] disk cache write: {}", cacheFile);
                        } catch (IOException e) {
                            logger.warn("[callSingle] disk cache write failed: {} - {}", cacheFile, e.getMessage());
                        }
                    } else {
                        logger.warn("[callSingle] disk cache skipped (not JSON-like): {}", path);
                    }
                }
            }

            // Cache in memory
            cache.put(path, result);
            logger.debug("[callSingle] memory cached: {}", path);

            return deepCopy(result);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal execution of callSingle - reads and evaluates the file.
     * Supports:
     * - ?suffix syntax for cache key differentiation (suffix is stripped for file read)
     * - @tag syntax for scenario selection (e.g., file.feature@tagname)
     */
    private Object executeCallSingleInternal(String path, Object arg) {
        // Strip ?suffix from path for file reading (suffix is only for cache key differentiation)
        // e.g., "get-token.feature?admin" -> read "get-token.feature", cache as "get-token.feature?admin"
        String filePath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        // Parse @tag selector from path using shared utility
        StepUtils.ParsedFeaturePath parsed = StepUtils.parseFeaturePath(filePath);
        filePath = parsed.path() != null ? parsed.path() : filePath;
        String tagSelector = parsed.tagSelector();

        // Read the file using the engine (which has access to the read function)
        Object content = karate.engine.eval("read('" + filePath.replace("'", "\\'") + "')");

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
                    tagSelector
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
            // Fire SCENARIO_ENTER event
            Suite suite = featureRuntime != null ? featureRuntime.getSuite() : null;
            if (suite != null) {
                boolean proceed = suite.fireEvent(ScenarioRunEvent.enter(this));
                if (!proceed) {
                    // Listener returned false - skip this scenario
                    stopped = true;
                }
            }

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
                    if (config.isContinueOnStepFailure()) {
                        // Log the failure but continue execution
                        if (!hasIgnoredFailure) {
                            hasIgnoredFailure = true;
                            firstIgnoredError = sr.getError();
                        }
                    } else {
                        stopped = true;
                        error = sr.getError();
                    }
                }
            }

            // If we have ignored failures and reached the end, fail the scenario
            if (hasIgnoredFailure && error == null) {
                error = firstIgnoredError;
            }

            // Evaluate backtick scenario name for reports (e.g., `result is ${1+1}`)
            // Must happen BEFORE SCENARIO_EXIT so the event contains the evaluated name
            evaluateScenarioName();

            // Fire SCENARIO_EXIT event
            if (suite != null) {
                suite.fireEvent(ScenarioRunEvent.exit(this, result));
            }

        } catch (Throwable t) {
            error = t;
            stopped = true;
        } finally {
            // Note: SCENARIO_EXIT event was already fired above

            // Report the last perf event (with any failure message including file:line info)
            // This must happen before the scenario ends so Gatling receives all HTTP metrics
            logLastPerfEvent(result.getFailureMessageForDisplay());

            // Close driver if it was initialized
            closeDriver();

            // Handle @fail tag - invert pass/fail result
            if (scenario.isFail()) {
                result.applyFailTag();
            }
            result.setEndTime(System.currentTimeMillis());
            LogContext.clear();
        }

        return result;
    }

    /**
     * Evaluate scenario name if it starts with a backtick (JS template literal).
     * This allows dynamic scenario names in reports, e.g., `result is ${1+1}`.
     * If evaluation fails, the original name is kept and a warning is logged
     * to both the console and the test report (appended to last step's log).
     */
    private void evaluateScenarioName() {
        String name = scenario.getName();
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (!trimmed.startsWith("`")) {
            return;
        }
        try {
            Object evaluated = karate.engine.eval(trimmed);
            if (evaluated != null) {
                scenario.setName(evaluated.toString());
            }
        } catch (Exception e) {
            String warning = "[WARN] Failed to evaluate scenario name '" + trimmed + "': " + e.getMessage();
            logger.warn(warning);
            // Append to last step's log for report visibility
            List<StepResult> steps = result.getStepResults();
            if (!steps.isEmpty()) {
                StepResult lastStep = steps.get(steps.size() - 1);
                lastStep.appendLog(warning);
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

    /**
     * Set a "hidden" variable that is accessible in JS but excluded from getAllVariables().
     * Used for internal implementation details like responseBytes, responseType, etc.
     */
    public void setHiddenVariable(String name, Object value) {
        karate.engine.putRootBinding(name, value);
    }

    public Object getVariable(String name) {
        return karate.engine.get(name);
    }

    public Map<String, Object> getAllVariables() {
        return new LinkedHashMap<>(karate.engine.getBindings());
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

    /**
     * Get the cookie jar for auto-sending cookies on subsequent requests.
     * Returns a map of cookie name to cookie details (value, path, domain, etc.).
     */
    public Map<String, Map<String, Object>> getCookieJar() {
        return cookieJar;
    }

    /**
     * Add cookies from responseCookies to the cookie jar for auto-send.
     * Called after each HTTP response to persist cookies.
     */
    @SuppressWarnings("unchecked")
    public void updateCookieJar(Object responseCookies) {
        if (responseCookies instanceof Map<?, ?> cookies) {
            for (Map.Entry<?, ?> entry : cookies.entrySet()) {
                String name = entry.getKey().toString();
                Object cookieData = entry.getValue();
                if (cookieData instanceof Map) {
                    cookieJar.put(name, (Map<String, Object>) cookieData);
                }
            }
        }
    }

    /**
     * Clear the cookie jar (for clearCookies functionality).
     */
    public void clearCookieJar() {
        cookieJar.clear();
    }

    public void configure(String key, Object value) {
        // Delegate to KarateConfig for type-safe storage
        boolean requiresHttpClientRebuild = config.configure(key, value);

        // Apply to HTTP client if needed
        if (requiresHttpClientRebuild) {
            karate.client.config(key, value);
        }

        // Additional side effects for specific keys
        if ("headers".equals(key) && value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) value;
            karate.http.headers(headers);
        }
        if ("cookies".equals(key) && value == null) {
            // V1 compatibility: configure cookies = null should also clear the cookie jar
            clearCookieJar();
        }
        if ("charset".equals(key)) {
            // Handle both String charset and null (to disable auto-charset)
            karate.http.charset(value == null ? null : value.toString());
        }

        // When continueOnStepFailure is set to false, check for deferred failures
        if ("continueOnStepFailure".equals(key) && !toBoolean(value) && hasIgnoredFailure) {
            stopped = true;
            error = firstIgnoredError;
        }
    }

    private static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    // ========== KarateJsContext Interface ==========

    @Override
    public ScenarioRuntime getRuntime() {
        return this;
    }

    @Override
    public KarateConfig getConfig() {
        return config;
    }

    @Override
    public Resource getWorkingDir() {
        return scenario.getFeature().getResource();
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

    // ========== Driver Support ==========

    /**
     * Get the browser driver, initializing it lazily if needed.
     * Requires driver to be configured via `configure driver = { ... }`.
     *
     * @return the Driver instance
     * @throws RuntimeException if driver is not configured
     */
    public Driver getDriver() {
        if (driver == null || driver.isTerminated()) {
            driver = initDriver();
        }
        return driver;
    }

    /**
     * Check if a driver has been initialized.
     */
    public boolean hasDriver() {
        return driver != null;
    }

    /**
     * Initialize the browser driver from configuration.
     */
    @SuppressWarnings("unchecked")
    private Driver initDriver() {
        Object driverConfig = config.getDriverConfig();
        if (driverConfig == null) {
            throw new RuntimeException("driver not configured - use: * configure driver = { type: 'chrome' }");
        }

        Map<String, Object> configMap = null;
        if (driverConfig instanceof Map) {
            configMap = (Map<String, Object>) driverConfig;
        } else {
            configMap = new java.util.HashMap<>();
        }

        // Check if a driver provider is configured at Suite level
        io.karatelabs.driver.DriverProvider provider = null;
        if (featureRuntime != null && featureRuntime.getSuite() != null) {
            provider = featureRuntime.getSuite().getDriverProvider();
        }

        Driver driver;
        if (provider != null) {
            // Use provider to acquire driver
            driver = provider.acquire(this, configMap);
            driverFromProvider = true;
            logger.info("Acquired driver from provider for scenario: {}", scenario.getName());
        } else {
            // Create driver directly (default behavior)
            CdpDriverOptions options = CdpDriverOptions.fromMap(configMap);
            String wsUrl = options.getWebSocketUrl();
            if (wsUrl != null && !wsUrl.isEmpty()) {
                logger.info("Connecting to existing browser: {}", wsUrl);
                driver = CdpDriver.connect(wsUrl, options);
            } else {
                logger.info("Starting browser with options: headless={}", options.isHeadless());
                driver = CdpDriver.start(options);
            }
            driverFromProvider = false;
        }

        // Bind driver to JS engine as root binding (hidden from getAllVariables)
        karate.engine.putRootBinding("driver", driver);

        // Bind driver action methods as global functions (V1 compatibility)
        bindDriverActions(driver);

        return driver;
    }

    /**
     * Bind driver action methods as global functions for V1 syntax compatibility.
     * This allows writing `* click('#button')` instead of `* driver.click('#button')`.
     */
    private void bindDriverActions(Driver d) {
        io.karatelabs.js.Engine engine = karate.engine;

        // Element actions (return Element for chaining)
        engine.putRootBinding("click", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.click(args[0].toString());
        });
        engine.putRootBinding("input", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.input(args[0].toString(), args.length > 1 ? args[1].toString() : "");
        });
        engine.putRootBinding("clear", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.clear(args[0].toString());
        });
        engine.putRootBinding("focus", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.focus(args[0].toString());
        });
        engine.putRootBinding("scroll", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.scroll(args[0].toString());
        });
        engine.putRootBinding("highlight", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.highlight(args[0].toString());
        });
        engine.putRootBinding("select", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            String locator = args[0].toString();
            Object value = args.length > 1 ? args[1] : null;
            if (value instanceof Number n) {
                return d.select(locator, n.intValue());
            }
            return d.select(locator, value != null ? value.toString() : "");
        });

        // Element state (return primitives)
        engine.putRootBinding("text", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.text(args[0].toString());
        });
        engine.putRootBinding("html", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.html(args[0].toString());
        });
        engine.putRootBinding("value", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            if (args.length == 1) {
                // Getter: value('#input')
                return d.value(args[0].toString());
            } else {
                // Setter: value('#input', 'text')
                return d.value(args[0].toString(), args[1].toString());
            }
        });
        engine.putRootBinding("attribute", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.attribute(args[0].toString(), args[1].toString());
        });
        engine.putRootBinding("exists", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.exists(args[0].toString());
        });
        engine.putRootBinding("enabled", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.enabled(args[0].toString());
        });
        engine.putRootBinding("position", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.position(args[0].toString());
        });

        // Wait methods
        engine.putRootBinding("waitFor", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.waitFor(args[0].toString());
        });
        engine.putRootBinding("waitForText", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.waitForText(args[0].toString(), args[1].toString());
        });
        engine.putRootBinding("waitForEnabled", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.waitForEnabled(args[0].toString());
        });
        engine.putRootBinding("waitForUrl", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.waitForUrl(args[0].toString());
        });
        engine.putRootBinding("waitUntil", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            if (args.length == 1) {
                // waitUntil(expression) - wait for JS expression to be truthy
                return d.waitUntil(args[0].toString());
            } else {
                // waitUntil(locator, expression) - wait for element expression
                return d.waitUntil(args[0].toString(), args[1].toString());
            }
        });

        // Locators
        engine.putRootBinding("locate", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.locate(args[0].toString());
        });
        engine.putRootBinding("locateAll", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.locateAll(args[0].toString());
        });
        engine.putRootBinding("optional", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.optional(args[0].toString());
        });

        // Frame switching
        engine.putRootBinding("switchFrame", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            Object arg = args.length > 0 ? args[0] : null;
            if (arg == null) {
                d.switchFrame((String) null);
            } else if (arg instanceof Number n) {
                d.switchFrame(n.intValue());
            } else {
                d.switchFrame(arg.toString());
            }
            return null;
        });

        // Script execution
        engine.putRootBinding("script", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            if (args.length == 1) {
                return d.script(args[0].toString());
            } else {
                return d.script(args[0].toString(), args[1].toString());
            }
        });
        engine.putRootBinding("scriptAll", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.scriptAll(args[0].toString(), args[1].toString());
        });

        // Navigation
        engine.putRootBinding("refresh", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            d.refresh();
            return null;
        });
        engine.putRootBinding("back", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            d.back();
            return null;
        });
        engine.putRootBinding("forward", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            d.forward();
            return null;
        });

        // Screenshots
        engine.putRootBinding("screenshot", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            if (args.length == 0) {
                return d.screenshot();
            } else if (args[0] instanceof Boolean b) {
                return d.screenshot(b);
            }
            return d.screenshot();
        });

        // Cookies
        engine.putRootBinding("clearCookies", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            d.clearCookies();
            return null;
        });
        engine.putRootBinding("deleteCookie", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            d.deleteCookie(args[0].toString());
            return null;
        });
        @SuppressWarnings("unchecked")
        io.karatelabs.js.JsCallable cookieFn = (ctx, args) -> {
            Object arg = args[0];
            if (arg instanceof String s) {
                return d.cookie(s);
            } else if (arg instanceof Map) {
                d.cookie((Map<String, Object>) arg);
                return null;
            }
            return null;
        };
        engine.putRootBinding("cookie", cookieFn);

        // Dialog handling
        engine.putRootBinding("dialog", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            boolean accept = args.length > 0 && Boolean.TRUE.equals(args[0]);
            if (args.length > 1 && args[1] != null) {
                d.dialog(accept, args[1].toString());
            } else {
                d.dialog(accept);
            }
            return null;
        });

        // Dialog handler registration (wraps JsCallable to DialogHandler)
        engine.putRootBinding("onDialog", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            if (args.length == 0 || args[0] == null) {
                d.onDialog(null);
            } else if (args[0] instanceof io.karatelabs.js.JsCallable) {
                io.karatelabs.js.JsCallable jsHandler = (io.karatelabs.js.JsCallable) args[0];
                d.onDialog(dialog -> {
                    jsHandler.call(ctx, dialog);
                });
            }
            return null;
        });

        // Mouse and keys
        engine.putRootBinding("mouse", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            if (args.length == 0) {
                return d.mouse();
            } else if (args.length == 1) {
                return d.mouse(args[0].toString());
            } else {
                return d.mouse((Number) args[0], (Number) args[1]);
            }
        });
        engine.putRootBinding("keys", (io.karatelabs.js.JsCallable) (ctx, args) -> {
            return d.keys();
        });

        // Key constants (e.g., Key.ENTER, Key.TAB)
        engine.putRootBinding("Key", new io.karatelabs.js.JavaType(io.karatelabs.driver.Keys.class));
    }

    /**
     * Close or release the driver if it was initialized.
     * If driver came from a provider, release it back to the provider.
     * Otherwise, close it directly.
     */
    private void closeDriver() {
        if (driver == null) {
            return;
        }

        if (driverFromProvider) {
            // Release back to provider
            io.karatelabs.driver.DriverProvider provider = null;
            if (featureRuntime != null && featureRuntime.getSuite() != null) {
                provider = featureRuntime.getSuite().getDriverProvider();
            }
            if (provider != null) {
                try {
                    provider.release(this, driver);
                    logger.debug("Released driver to provider for scenario: {}", scenario.getName());
                } catch (Exception e) {
                    logger.warn("Error releasing driver to provider: {}", e.getMessage());
                }
            }
        } else {
            // Close directly
            try {
                driver.quit();
            } catch (Exception e) {
                logger.warn("Error closing driver: {}", e.getMessage());
            }
        }
        driver = null;
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

    // ========== Performance Testing (Gatling Integration) ==========

    /**
     * Check if performance mode is enabled.
     * When true, HTTP request timing is reported via PerfHook.
     */
    public boolean isPerfMode() {
        return featureRuntime != null
                && featureRuntime.getSuite() != null
                && featureRuntime.getSuite().isPerfMode();
    }

    /**
     * Get the PerfHook for reporting performance events.
     *
     * @return the PerfHook or null if not in perf mode
     */
    public PerfHook getPerfHook() {
        if (featureRuntime != null && featureRuntime.getSuite() != null) {
            return featureRuntime.getSuite().getPerfHook();
        }
        return null;
    }

    /**
     * Capture a performance event for the HTTP request just completed.
     * <p>
     * This implements the "deferred reporting" pattern from v1:
     * - The previous event is reported first (if any)
     * - The new event is held until the next HTTP request or scenario end
     * - This allows assertion failures to be attributed to the preceding HTTP request
     *
     * @param event the performance event to capture
     */
    public void capturePerfEvent(PerfEvent event) {
        // Report the previous event (if any) without failure
        logLastPerfEvent(null);
        // Hold this event for later
        prevPerfEvent = event;
    }

    /**
     * Log (report) the last captured performance event.
     * <p>
     * Called:
     * 1. Before each new HTTP request (to report the previous one)
     * 2. At scenario end (to report the final request, with any failure message)
     * <p>
     * If failureMessage is non-null, the event is marked as failed.
     *
     * @param failureMessage the failure message (null if successful)
     */
    public void logLastPerfEvent(String failureMessage) {
        if (prevPerfEvent == null) {
            return;
        }
        if (!isPerfMode()) {
            prevPerfEvent = null;
            return;
        }
        // Mark as failed if there's a failure message
        if (failureMessage != null) {
            prevPerfEvent.setFailed(true);
            prevPerfEvent.setMessage(failureMessage);
        }
        // Report to PerfHook
        PerfHook hook = getPerfHook();
        if (hook != null) {
            hook.reportPerfEvent(prevPerfEvent);
        }
        prevPerfEvent = null;
    }

    /**
     * Capture a custom performance event (for non-HTTP operations like DB, gRPC).
     * <p>
     * Unlike HTTP events, custom events are reported immediately.
     *
     * @param name      the event name
     * @param startTime start time in epoch milliseconds
     * @param endTime   end time in epoch milliseconds
     */
    public void captureCustomPerfEvent(String name, long startTime, long endTime) {
        if (!isPerfMode()) {
            return;
        }
        PerfEvent event = new PerfEvent(startTime, endTime, name, 200);
        PerfHook hook = getPerfHook();
        if (hook != null) {
            hook.reportPerfEvent(event);
        }
    }

}
