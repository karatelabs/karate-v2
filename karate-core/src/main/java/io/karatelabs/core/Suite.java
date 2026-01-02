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
    private boolean backupReportDir = false;
    private boolean outputConsoleSummary = true;

    // Config content (loaded at Suite level, evaluated per-scenario)
    private String baseContent;      // karate-base.js (shared functions)
    private String configContent;
    private String configEnvContent;
    // Legacy: evaluated config variables (for backward compatibility with tests)
    private Map<String, Object> configVariables = Collections.emptyMap();

    // System properties (available via karate.properties)
    private Map<String, String> systemProperties;

    // Hooks (legacy)
    private final List<RuntimeHook> hooks = new ArrayList<>();

    // Run event listeners (new unified event system)
    private final List<RunListener> listeners = new ArrayList<>();
    private final List<RunListenerFactory> listenerFactories = new ArrayList<>();
    private final ThreadLocal<List<RunListener>> threadListeners = new ThreadLocal<>();

    // Result listeners
    private final List<ResultListener> resultListeners = new ArrayList<>();

    // Caches (shared across features)
    // Note: CALLONCE is now feature-scoped in FeatureRuntime, not suite-scoped
    private final Map<String, Object> CALLSINGLE_CACHE = new ConcurrentHashMap<>();
    private final ReentrantLock callSingleLock = new ReentrantLock();

    // Results
    private SuiteResult result;

    // Driver provider (manages driver lifecycle across scenarios)
    private DriverProvider driverProvider;

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
     * Add a runtime hook (legacy API). The hook is wrapped as a RunListener
     * and receives events through the unified event system.
     * @param hook the hook
     * @return this suite for chaining
     */
    public Suite hook(RuntimeHook hook) {
        this.hooks.add(hook);  // Keep for getHooks() backward compatibility
        this.listeners.add(new RuntimeHookAdapter(hook));  // Wrap as listener
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

    public Suite backupReportDir(boolean backupReportDir) {
        this.backupReportDir = backupReportDir;
        return this;
    }

    public Suite outputConsoleSummary(boolean outputConsoleSummary) {
        this.outputConsoleSummary = outputConsoleSummary;
        return this;
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
            logger.warn("Config not found: {}", path);
        }
        return null;
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
            // Create output directory if it doesn't exist
            if (!java.nio.file.Files.exists(outputDir)) {
                java.nio.file.Files.createDirectories(outputDir);
            }

            // Write karate-summary.json
            Path summaryPath = outputDir.resolve("karate-summary.json");
            String json = result.toJsonPretty();
            java.nio.file.Files.writeString(summaryPath, json);
            logger.info("Report written to: {}", summaryPath);

            // Write individual feature results
            for (FeatureResult fr : result.getFeatureResults()) {
                String featureName = fr.getFeature().getName();
                if (featureName == null || featureName.isEmpty()) {
                    featureName = "feature";
                }
                // Sanitize filename
                String safeName = featureName.replaceAll("[^a-zA-Z0-9_-]", "_");
                Path featurePath = outputDir.resolve(safeName + ".json");
                String featureJson = io.karatelabs.common.Json.of(fr.toJson()).toStringPretty();
                java.nio.file.Files.writeString(featurePath, featureJson);
            }
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
                FeatureRuntime fr = new FeatureRuntime(this, feature);
                FeatureResult featureResult = fr.call();
                result.addFeatureResult(featureResult);
                if (outputConsoleSummary) {
                    featureResult.printSummary();
                }
            }
        } finally {
            cleanupThreadListeners();
        }
    }

    private void runParallel() {
        // Use semaphore to limit concurrent executions to threadCount
        Semaphore semaphore = new Semaphore(threadCount);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FeatureResult>> futures = new ArrayList<>();

            for (Feature feature : features) {
                // Skip features with @ignore tag at feature level
                if (isFeatureIgnored(feature)) {
                    continue;
                }
                Future<FeatureResult> future = executor.submit(() -> {
                    semaphore.acquire();
                    initThreadListeners();
                    try {
                        FeatureRuntime fr = new FeatureRuntime(this, feature);
                        FeatureResult featureResult = fr.call();
                        if (outputConsoleSummary) {
                            featureResult.printSummary();
                        }
                        return featureResult;
                    } finally {
                        cleanupThreadListeners();
                        semaphore.release();
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
                    // Handle execution exception
                    throw new RuntimeException("Feature execution failed", e);
                }
            }
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

    public List<RuntimeHook> getHooks() {
        return hooks;
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

}
