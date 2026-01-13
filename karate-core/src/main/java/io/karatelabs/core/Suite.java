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

import io.karatelabs.common.FileUtils;
import io.karatelabs.common.Resource;
import io.karatelabs.common.ResourceNotFoundException;
import io.karatelabs.driver.DriverProvider;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Tag;
import io.karatelabs.js.Engine;
import io.karatelabs.output.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

public class Suite {

    private static final Logger logger = LogContext.RUNTIME_LOGGER;

    // Features to run
    private final List<Feature> features = new ArrayList<>();

    // Configuration
    private String env;
    private String tagSelector;
    private int threadCount = 1;
    private boolean parallel = false;
    private boolean dryRun = false;
    private String configPath = "classpath:karate-config.js";
    private Path outputDir = Path.of("target/karate-reports");
    private Path workingDir = FileUtils.WORKING_DIR.toPath();
    private boolean writeReport = true;
    private boolean outputHtmlReport = true;
    private boolean outputJsonLines = false;
    private boolean outputJunitXml = false;
    private boolean outputCucumberJson = false;
    private boolean outputKarateJson = false;
    private boolean backupReportDir = true;
    private boolean outputConsoleSummary = true;

    // Config content (loaded at Suite level, evaluated per-scenario)
    private String baseContent;      // karate-base.js (shared functions)
    private String configContent;
    private String configEnvContent;
    // Legacy: evaluated config variables (for backward compatibility with tests)
    private Map<String, Object> configVariables = Collections.emptyMap();

    // System properties (available via karate.properties)
    private Map<String, String> systemProperties;

    // Run event listeners
    private final List<RunListener> listeners = new ArrayList<>();
    private final List<RunListenerFactory> listenerFactories = new ArrayList<>();
    private final ThreadLocal<List<RunListener>> threadListeners = new ThreadLocal<>();

    // Result listeners
    private final List<ResultListener> resultListeners = new ArrayList<>();

    // Caches (shared across features)
    // Note: CALLONCE is now feature-scoped in FeatureRuntime, not suite-scoped
    private final Map<String, Object> CALLSINGLE_CACHE = new ConcurrentHashMap<>();
    private final ReentrantLock callSingleLock = new ReentrantLock();

    // Lock manager for @lock tag support (mutual exclusion across parallel scenarios)
    private final ScenarioLockManager lockManager = new ScenarioLockManager();

    // Shared executor and semaphore for scenario-level parallelism
    // These must be volatile for memory visibility across virtual threads
    private volatile ExecutorService scenarioExecutor;
    private volatile Semaphore scenarioSemaphore;

    // Lane pool for timeline reporting (assigns consistent lane numbers 1-N instead of random thread IDs)
    private volatile java.util.Queue<Integer> availableLanes;
    private final ThreadLocal<Integer> currentLane = new ThreadLocal<>();

    // Results
    private SuiteResult result;

    // Driver provider (manages driver lifecycle across scenarios)
    private DriverProvider driverProvider;

    // Performance testing hook (for Gatling integration)
    private PerfHook perfHook;

    // HTTP client factory (for custom/mock HTTP clients)
    private io.karatelabs.http.HttpClientFactory httpClientFactory;

    // Skip tag filtering (@env, @ignore) - useful for unit tests
    private boolean skipTagFiltering = false;

    private Suite() {
    }

    public static Suite of(String... paths) {
        return of((Path) null, paths);
    }

    public static Suite of(Path workingDir, String... paths) {
        Suite suite = new Suite();
        if (workingDir != null) {
            suite.workingDir = workingDir.toAbsolutePath().normalize();
        }
        for (String path : paths) {
            File file = new File(path);
            if (file.isDirectory()) {
                // Find all .feature files in directory
                addFeaturesFromDirectory(suite, file, suite.workingDir);
            } else if (file.exists() && file.getName().endsWith(".feature")) {
                Feature feature = Feature.read(Resource.from(file.toPath(), suite.workingDir));
                suite.features.add(feature);
            }
        }
        return suite;
    }

    public static Suite of(Feature... features) {
        Suite suite = new Suite();
        for (Feature feature : features) {
            suite.features.add(feature);
        }
        return suite;
    }

    private static void addFeaturesFromDirectory(Suite suite, File dir, Path workingDir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                addFeaturesFromDirectory(suite, file, workingDir);
            } else if (file.getName().endsWith(".feature")) {
                Feature feature = Feature.read(Resource.from(file.toPath(), workingDir));
                suite.features.add(feature);
            }
        }
    }

    // ========== Configuration (Builder Pattern) ==========

    public Suite parallel(int threads) {
        this.threadCount = threads;
        this.parallel = threads > 1;
        return this;
    }

    /**
     * Set tag selector expression. Supports both V1-style JS expressions and cucumber-style tags.
     * <p>
     * Examples:
     * <ul>
     *   <li>JS-style: "anyOf('@foo', '@bar') && not('@ignore')"</li>
     *   <li>Cucumber-style: "@foo" (converted to "anyOf('@foo')")</li>
     *   <li>Cucumber OR: "@foo, @bar" (converted to "anyOf('@foo','@bar')")</li>
     * </ul>
     */
    public Suite tags(String tagExpression) {
        this.tagSelector = TagSelector.fromKarateOptionsTags(tagExpression);
        return this;
    }

    /**
     * Set tag selector from multiple expressions (cucumber-style AND).
     * Each expression is ANDed together.
     * <p>
     * Example: tags("@foo", "@bar") becomes "anyOf('@foo') && anyOf('@bar')"
     */
    public Suite tags(String... tagExpressions) {
        this.tagSelector = TagSelector.fromKarateOptionsTags(tagExpressions);
        return this;
    }

    public Suite env(String env) {
        this.env = env;
        return this;
    }

    /**
     * Add a run event listener. Listeners receive all runtime events.
     * @param listener the listener
     * @return this suite for chaining
     */
    public Suite listener(RunListener listener) {
        this.listeners.add(listener);
        return this;
    }

    /**
     * Add a run listener factory for per-thread listeners.
     * A new listener is created for each execution thread.
     * @param factory the factory
     * @return this suite for chaining
     */
    public Suite listenerFactory(RunListenerFactory factory) {
        this.listenerFactories.add(factory);
        return this;
    }

    public Suite dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public Suite configPath(String configPath) {
        this.configPath = configPath;
        return this;
    }

    public Suite outputDir(Path outputDir) {
        this.outputDir = outputDir;
        return this;
    }

    public Suite outputDir(String outputDir) {
        this.outputDir = Path.of(outputDir);
        return this;
    }

    public Suite workingDir(Path workingDir) {
        if (workingDir != null) {
            this.workingDir = workingDir.toAbsolutePath().normalize();
        }
        return this;
    }

    public Suite workingDir(String workingDir) {
        if (workingDir != null) {
            this.workingDir = Path.of(workingDir).toAbsolutePath().normalize();
        }
        return this;
    }

    public Suite writeReport(boolean writeReport) {
        this.writeReport = writeReport;
        return this;
    }

    public Suite outputHtmlReport(boolean outputHtmlReport) {
        this.outputHtmlReport = outputHtmlReport;
        return this;
    }

    public Suite outputJsonLines(boolean outputJsonLines) {
        this.outputJsonLines = outputJsonLines;
        return this;
    }

    public Suite outputJunitXml(boolean outputJunitXml) {
        this.outputJunitXml = outputJunitXml;
        return this;
    }

    public Suite outputCucumberJson(boolean outputCucumberJson) {
        this.outputCucumberJson = outputCucumberJson;
        return this;
    }

    public Suite outputKarateJson(boolean outputKarateJson) {
        this.outputKarateJson = outputKarateJson;
        return this;
    }

    public Suite backupReportDir(boolean backupReportDir) {
        this.backupReportDir = backupReportDir;
        return this;
    }

    public Suite outputConsoleSummary(boolean outputConsoleSummary) {
        this.outputConsoleSummary = outputConsoleSummary;
        return this;
    }

    /**
     * Skip tag filtering (@env, @ignore) so all scenarios run regardless of tags.
     * Use this for unit tests that need to run scenarios with any tags.
     */
    public Suite skipTagFiltering(boolean skip) {
        this.skipTagFiltering = skip;
        return this;
    }

    /**
     * Check if tag filtering is skipped.
     */
    public boolean isSkipTagFiltering() {
        return skipTagFiltering;
    }

    public Suite resultListener(ResultListener listener) {
        this.resultListeners.add(listener);
        return this;
    }

    /**
     * Set system properties that will be available via karate.properties in scripts.
     * If null, defaults to System.getProperties() at runtime.
     */
    public Suite systemProperties(Map<String, String> props) {
        this.systemProperties = props;
        return this;
    }

    // ========== Execution ==========

    /**
     * Loads karate-base.js, karate-config.js content (and env-specific config) without evaluating.
     * The content is evaluated per-scenario in ScenarioRuntime where callSingle is available.
     */
    private void loadConfig() {
        // Derive base.js path from config path (same directory)
        String basePath;
        if (configPath.endsWith("karate-config.js")) {
            basePath = configPath.replace("karate-config.js", "karate-base.js");
        } else {
            basePath = "classpath:karate-base.js";
        }

        // Load karate-base.js (optional - silent if not found)
        baseContent = tryLoadConfig(basePath, false);
        if (baseContent != null) {
            logger.info("Loaded karate-base.js from {}", basePath);
        }

        // Load main config (warn if not found)
        configContent = tryLoadConfig(configPath, true);
        if (configContent != null) {
            logger.info("Loaded karate-config.js from {}", configPath);
        }

        // Load env-specific config content (optional - silent if not found)
        if (env != null && !env.isEmpty()) {
            String envConfigPath = configPath.replace(".js", "-" + env + ".js");
            configEnvContent = tryLoadConfig(envConfigPath, false);
            if (configEnvContent != null) {
                logger.info("Loaded {} config from {}", env, envConfigPath);
            }
        }
    }

    /**
     * Try to load a config file.
     *
     * @param path the config path
     * @param warnIfMissing if true, log a warning when file is not found
     * @return the file content, or null if not found
     */
    private String tryLoadConfig(String path, boolean warnIfMissing) {
        // Try the explicit path first
        try {
            Resource resource = Resource.path(path);
            if (resource.exists()) {
                return resource.getText();
            }
        } catch (ResourceNotFoundException e) {
            // Not found at explicit path - continue to fallbacks
        } catch (Exception e) {
            logger.warn("Error loading config {}: {}", path, e.getMessage());
            return null;
        }

        // V2 enhancement: Try working directory as fallback
        // This allows users to run Karate from any directory without Java classpath setup
        String fileName = path;
        // Strip classpath: prefix to get the filename
        if (path.startsWith("classpath:")) {
            fileName = path.substring("classpath:".length());
        }
        // Only try working dir for relative paths (not absolute)
        if (!fileName.startsWith("/")) {
            try {
                Path workingDirConfig = workingDir.resolve(fileName);
                if (java.nio.file.Files.exists(workingDirConfig)) {
                    return java.nio.file.Files.readString(workingDirConfig);
                }
            } catch (Exception e) {
                logger.debug("Could not load config from working dir: {}", e.getMessage());
            }
        }

        if (warnIfMissing) {
            logger.trace("Config not found: {}", path);
        }
        return null;
    }

    /**
     * Initialize the suite without running tests.
     * Loads karate-config.js and karate-base.js.
     * This is used by karate-gatling for running features with pre-loaded config.
     */
    public void init() {
        loadConfig();
    }

    public SuiteResult run() {
        result = new SuiteResult();
        result.setStartTime(System.currentTimeMillis());

        // Backup existing report directory if enabled
        if (backupReportDir) {
            backupReportDirIfExists();
        }

        // Auto-register HTML report listener (writes feature HTML async, summary at end)
        if (outputHtmlReport) {
            resultListeners.add(new HtmlReportListener(outputDir, env));
        }

        // Auto-register Cucumber JSON report listener (writes per-feature JSON async)
        if (outputCucumberJson) {
            resultListeners.add(new CucumberJsonReportListener(outputDir));
        }

        // Auto-register JUnit XML report listener (writes per-feature XML async)
        if (outputJunitXml) {
            resultListeners.add(new JunitXmlReportListener(outputDir));
        }

        // Auto-register Karate JSON report listener (writes per-feature JSON async)
        if (outputKarateJson) {
            resultListeners.add(new KarateJsonReportListener(outputDir));
        }

        // Optionally register JSON Lines event stream writer (karate-events.jsonl)
        JsonLinesEventWriter jsonlWriter = null;
        if (outputJsonLines) {
            jsonlWriter = new JsonLinesEventWriter(outputDir, env, threadCount);
            try {
                jsonlWriter.init();
                listeners.add(jsonlWriter);
            } catch (Exception e) {
                logger.warn("Failed to initialize JSONL event stream: {}", e.getMessage());
                jsonlWriter = null;
            }
        }

        try {
            // Load config before anything else
            loadConfig();

            // Notify listeners
            for (ResultListener listener : resultListeners) {
                listener.onSuiteStart(this);
            }

            // Fire SUITE_ENTER event (RuntimeHookAdapter calls beforeSuite)
            fireEvent(SuiteRunEvent.enter(this));

            if (parallel && threadCount > 1) {
                runParallel();
            } else {
                runSequential();
            }

            // Fire SUITE_EXIT event (RuntimeHookAdapter calls afterSuite)
            fireEvent(SuiteRunEvent.exit(this, result));
        } finally {
            result.setEndTime(System.currentTimeMillis());

            // Shutdown driver provider if one exists
            if (driverProvider != null) {
                driverProvider.shutdown();
            }

            // Close JSONL event writer
            if (jsonlWriter != null) {
                try {
                    listeners.remove(jsonlWriter);
                    jsonlWriter.close();
                } catch (Exception e) {
                    logger.warn("Failed to close JSONL event stream: {}", e.getMessage());
                }
            }

            // Notify listeners
            for (ResultListener listener : resultListeners) {
                listener.onSuiteEnd(result);
            }

            // Write reports if enabled
            if (writeReport) {
                writeKarateJsonReport();
            }
            // Note: HTML report is generated by HtmlReportListener
            // Note: Cucumber JSON is generated by CucumberJsonReportListener
            // Note: JUnit XML is generated by JunitXmlReportListener
        }

        return result;
    }

    private void writeKarateJsonReport() {
        try {
            // Create karate-json subfolder
            Path karateJsonDir = outputDir.resolve(KarateJsonReportListener.SUBFOLDER);
            if (!java.nio.file.Files.exists(karateJsonDir)) {
                java.nio.file.Files.createDirectories(karateJsonDir);
            }

            // Write karate-summary.json to karate-json subfolder
            Path summaryPath = karateJsonDir.resolve("karate-summary.json");
            String json = result.toJsonPretty();
            java.nio.file.Files.writeString(summaryPath, json);
            logger.info("Report written to: {}", summaryPath);

            // Note: Per-feature JSON is generated by KarateJsonReportListener when outputKarateJson is enabled
        } catch (Exception e) {
            logger.warn("Failed to write karate report: {}", e.getMessage());
        }
    }

    private void runSequential() {
        initThreadListeners();
        try {
            for (Feature feature : features) {
                // Skip features with @ignore tag at feature level
                if (isFeatureIgnored(feature)) {
                    continue;
                }
                FeatureResult featureResult = runFeatureSafely(feature);
                result.addFeatureResult(featureResult);
                if (outputConsoleSummary) {
                    featureResult.printSummary();
                }
            }
        } finally {
            cleanupThreadListeners();
        }
    }

    /**
     * Run a feature with exception handling.
     * If an unexpected exception occurs, creates a failed FeatureResult instead of crashing.
     */
    private FeatureResult runFeatureSafely(Feature feature) {
        long startTime = System.currentTimeMillis();
        try {
            FeatureRuntime fr = new FeatureRuntime(this, feature);
            return fr.call();
        } catch (Exception e) {
            // Safety net: if FeatureRuntime.call() throws (shouldn't happen with internal handling),
            // create a failed result instead of crashing the runner
            logger.error("Unexpected error running feature '{}': {}", feature.getName(), e.getMessage(), e);
            return FeatureResult.fromException(feature, e, startTime);
        }
    }

    private void runParallel() {
        // Initialize ALL parallel infrastructure BEFORE creating executor
        // This ensures visibility to feature tasks that may start immediately
        // Initialize lane pool for timeline reporting (1 to threadCount)
        availableLanes = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 1; i <= threadCount; i++) {
            availableLanes.add(i);
        }
        // Semaphore limits concurrent scenarios across all features
        scenarioSemaphore = new Semaphore(threadCount);
        logger.info("Parallel execution initialized: threadCount={}, semaphore permits={}",
                threadCount, scenarioSemaphore.availablePermits());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Set executor LAST - this signals to features that parallel mode is ready
            scenarioExecutor = executor;
            List<Future<FeatureResult>> futures = new ArrayList<>();

            for (Feature feature : features) {
                // Skip features with @ignore tag at feature level
                if (isFeatureIgnored(feature)) {
                    continue;
                }
                // Features are dispatched immediately (no semaphore at feature level)
                // Scenario-level semaphore controls actual concurrency
                Future<FeatureResult> future = executor.submit(() -> {
                    initThreadListeners();
                    try {
                        FeatureResult featureResult = runFeatureSafely(feature);
                        if (outputConsoleSummary) {
                            featureResult.printSummary();
                        }
                        return featureResult;
                    } finally {
                        cleanupThreadListeners();
                    }
                });
                futures.add(future);
            }

            // Collect results
            for (Future<FeatureResult> future : futures) {
                try {
                    FeatureResult featureResult = future.get();
                    result.addFeatureResult(featureResult);
                } catch (Exception e) {
                    // This should rarely happen now with runFeatureSafely, but handle gracefully
                    logger.error("Unexpected error collecting feature result: {}", e.getMessage(), e);
                    // The feature result was already added in the task, so we can continue
                }
            }
        } finally {
            scenarioExecutor = null;
            scenarioSemaphore = null;
        }
    }

    /**
     * Check if a feature has @ignore tag at the feature level.
     */
    private boolean isFeatureIgnored(Feature feature) {
        for (Tag tag : feature.getTags()) {
            if (Tag.IGNORE.equals(tag.getName())) {
                return true;
            }
        }
        return false;
    }

    // ========== Event System ==========

    /**
     * Initialize per-thread listeners from factories.
     * Called at the start of each execution thread.
     */
    void initThreadListeners() {
        if (listenerFactories.isEmpty()) {
            return;
        }
        List<RunListener> perThread = new ArrayList<>();
        for (RunListenerFactory factory : listenerFactories) {
            perThread.add(factory.create());
        }
        threadListeners.set(perThread);
    }

    /**
     * Clean up per-thread listeners.
     * Called at the end of each execution thread.
     */
    void cleanupThreadListeners() {
        threadListeners.remove();
    }

    /**
     * Fire an event to all listeners.
     * Returns false if any listener returns false (for ENTER events, this means skip).
     *
     * @param event the event to fire
     * @return true to continue, false to skip
     */
    public boolean fireEvent(RunEvent event) {
        boolean proceed = true;

        // Global listeners (shared across threads)
        for (RunListener listener : listeners) {
            if (!listener.onEvent(event)) {
                proceed = false;
            }
        }

        // Per-thread listeners (created from factories)
        List<RunListener> perThread = threadListeners.get();
        if (perThread != null) {
            for (RunListener listener : perThread) {
                if (!listener.onEvent(event)) {
                    proceed = false;
                }
            }
        }

        return proceed;
    }

    private static final DateTimeFormatter BACKUP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private void backupReportDirIfExists() {
        if (!Files.exists(outputDir)) {
            return;
        }
        String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
        String baseName = outputDir.getFileName() + "_" + timestamp;
        Path backupPath = outputDir.resolveSibling(baseName);
        // If backup path already exists, increment suffix
        int suffix = 1;
        while (Files.exists(backupPath)) {
            backupPath = outputDir.resolveSibling(baseName + "_" + suffix);
            suffix++;
        }
        try {
            Files.move(outputDir, backupPath);
            logger.info("Backed up existing '{}' to: {}", outputDir.getFileName(), backupPath);
        } catch (Exception e) {
            logger.warn("Failed to backup existing dir '{}': {}", outputDir, e.getMessage());
        }
    }

    // ========== Accessors ==========

    public String getEnv() {
        return env;
    }

    public String getTagSelector() {
        return tagSelector;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public boolean isParallel() {
        return parallel;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public Path getWorkingDir() {
        return workingDir;
    }

    public String getOutputDir() {
        return outputDir.toString();
    }

    public List<Feature> getFeatures() {
        return features;
    }

    public Map<String, Object> getCallSingleCache() {
        return CALLSINGLE_CACHE;
    }

    public ReentrantLock getCallSingleLock() {
        return callSingleLock;
    }

    public ScenarioLockManager getLockManager() {
        return lockManager;
    }

    /**
     * Get the shared executor for scenario-level parallelism.
     * Only available during parallel execution.
     */
    public ExecutorService getScenarioExecutor() {
        return scenarioExecutor;
    }

    /**
     * Get the semaphore that limits concurrent scenario execution.
     * Only available during parallel execution.
     */
    public Semaphore getScenarioSemaphore() {
        return scenarioSemaphore;
    }

    /**
     * Acquire a lane for timeline reporting.
     * Call this after acquiring the semaphore and before executing a scenario.
     * The lane is stored in a ThreadLocal for the current thread.
     */
    public void acquireLane() {
        if (availableLanes != null) {
            Integer lane = availableLanes.poll();
            if (lane != null) {
                currentLane.set(lane);
            }
        }
    }

    /**
     * Release the current lane back to the pool.
     * Call this after scenario execution completes.
     */
    public void releaseLane() {
        if (availableLanes != null) {
            Integer lane = currentLane.get();
            if (lane != null) {
                currentLane.remove();
                availableLanes.add(lane);
            }
        }
    }

    /**
     * Get the current lane name for timeline reporting.
     * Returns a consistent name like "1", "2", etc. based on the lane pool.
     * Returns null if not in parallel mode or no lane assigned.
     */
    public String getCurrentLaneName() {
        Integer lane = currentLane.get();
        return lane != null ? String.valueOf(lane) : null;
    }

    public SuiteResult getResult() {
        return result;
    }

    public Map<String, Object> getConfigVariables() {
        return configVariables;
    }

    public String getBaseContent() {
        return baseContent;
    }

    public String getConfigContent() {
        return configContent;
    }

    public String getConfigEnvContent() {
        return configEnvContent;
    }

    public List<RunListener> getListeners() {
        return listeners;
    }

    public List<RunListenerFactory> getListenerFactories() {
        return listenerFactories;
    }

    public List<ResultListener> getResultListeners() {
        return resultListeners;
    }

    /**
     * Get system properties for karate.properties.
     * If none were set explicitly, returns System.getProperties() as a Map.
     */
    public Map<String, String> getSystemProperties() {
        if (systemProperties == null) {
            // Fallback to JVM system properties
            Map<String, String> props = new HashMap<>();
            System.getProperties().forEach((k, v) -> props.put(k.toString(), v.toString()));
            return props;
        }
        // Merge with JVM system properties (explicit properties take precedence)
        Map<String, String> merged = new HashMap<>();
        System.getProperties().forEach((k, v) -> merged.put(k.toString(), v.toString()));
        merged.putAll(systemProperties);
        return merged;
    }

    // ========== Driver Provider ==========

    /**
     * Get the driver provider. May be null if not configured.
     */
    public DriverProvider getDriverProvider() {
        return driverProvider;
    }

    /**
     * Set the driver provider.
     * Called via Runner.driverProvider() or Suite.driverProvider().
     */
    public Suite driverProvider(DriverProvider provider) {
        this.driverProvider = provider;
        return this;
    }

    public PerfHook getPerfHook() {
        return perfHook;
    }

    /**
     * Set the performance hook for Gatling integration.
     * When set, HTTP request timing will be reported via this hook.
     */
    public Suite perfHook(PerfHook hook) {
        this.perfHook = hook;
        return this;
    }

    /**
     * Set the HTTP client factory for custom/mock HTTP clients.
     * When set, this factory is used instead of the default Netty client.
     */
    public Suite httpClientFactory(io.karatelabs.http.HttpClientFactory factory) {
        this.httpClientFactory = factory;
        return this;
    }

    /**
     * Get the HTTP client factory, or null if using the default.
     */
    public io.karatelabs.http.HttpClientFactory getHttpClientFactory() {
        return httpClientFactory;
    }

    /**
     * Check if performance mode is enabled (perfHook is set).
     */
    public boolean isPerfMode() {
        return perfHook != null;
    }

}
