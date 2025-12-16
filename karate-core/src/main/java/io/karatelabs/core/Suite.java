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
import io.karatelabs.common.ResourceNotFoundException;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.js.Engine;
import io.karatelabs.log.JvmLogger;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Suite {

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
    private boolean writeReport = true;
    private boolean outputHtmlReport = true;
    private boolean outputJunitXml = false;
    private boolean outputCucumberJson = false;

    // Config variables from karate-config.js
    private Map<String, Object> configVariables = Collections.emptyMap();

    // Hooks
    private final List<RuntimeHook> hooks = new ArrayList<>();

    // Result listeners
    private final List<ResultListener> resultListeners = new ArrayList<>();

    // Caches (shared across features)
    private final Map<String, Object> CALLONCE_CACHE = new ConcurrentHashMap<>();

    // Results
    private SuiteResult result;

    private Suite() {
    }

    public static Suite of(String... paths) {
        Suite suite = new Suite();
        for (String path : paths) {
            File file = new File(path);
            if (file.isDirectory()) {
                // Find all .feature files in directory
                addFeaturesFromDirectory(suite, file);
            } else if (file.exists() && file.getName().endsWith(".feature")) {
                Feature feature = Feature.read(Resource.from(file.toPath()));
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

    private static void addFeaturesFromDirectory(Suite suite, File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                addFeaturesFromDirectory(suite, file);
            } else if (file.getName().endsWith(".feature")) {
                Feature feature = Feature.read(Resource.from(file.toPath()));
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

    public Suite tags(String tagExpression) {
        this.tagSelector = tagExpression;
        return this;
    }

    public Suite env(String env) {
        this.env = env;
        return this;
    }

    public Suite hook(RuntimeHook hook) {
        this.hooks.add(hook);
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

    public Suite writeReport(boolean writeReport) {
        this.writeReport = writeReport;
        return this;
    }

    public Suite outputHtmlReport(boolean outputHtmlReport) {
        this.outputHtmlReport = outputHtmlReport;
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

    public Suite resultListener(ResultListener listener) {
        this.resultListeners.add(listener);
        return this;
    }

    // ========== Execution ==========

    /**
     * Loads karate-config.js (and env-specific config) and executes it.
     * The returned object from the config function becomes the base variables for all scenarios.
     */
    @SuppressWarnings("unchecked")
    private void loadConfig() {
        Engine engine = new Engine();

        // Set karate.env in the engine
        if (env != null) {
            engine.put("karate", new ConfigKarateBridge(env));
        } else {
            engine.put("karate", new ConfigKarateBridge(null));
        }

        // Try to load main config
        String mainConfigContent = tryLoadConfig(configPath);
        if (mainConfigContent != null) {
            try {
                Object result = engine.eval(mainConfigContent);
                if (result instanceof Map) {
                    configVariables = new HashMap<>((Map<String, Object>) result);
                    JvmLogger.debug("Loaded config from {}: {} variables", configPath, configVariables.size());
                }
            } catch (Exception e) {
                JvmLogger.warn("Failed to evaluate {}: {}", configPath, e.getMessage());
            }
        }

        // Try to load env-specific config (e.g., karate-config-dev.js)
        if (env != null && !env.isEmpty()) {
            String envConfigPath = configPath.replace(".js", "-" + env + ".js");
            String envConfigContent = tryLoadConfig(envConfigPath);
            if (envConfigContent != null) {
                try {
                    // Put existing config vars into engine so env config can access them
                    for (Map.Entry<String, Object> entry : configVariables.entrySet()) {
                        engine.put(entry.getKey(), entry.getValue());
                    }
                    Object result = engine.eval(envConfigContent);
                    if (result instanceof Map) {
                        configVariables.putAll((Map<String, Object>) result);
                        JvmLogger.debug("Loaded env config from {}", envConfigPath);
                    }
                } catch (Exception e) {
                    JvmLogger.warn("Failed to evaluate {}: {}", envConfigPath, e.getMessage());
                }
            }
        }
    }

    private String tryLoadConfig(String path) {
        try {
            Resource resource = Resource.path(path);
            return resource.getText();
        } catch (ResourceNotFoundException e) {
            JvmLogger.debug("Config not found: {}", path);
            return null;
        } catch (Exception e) {
            JvmLogger.warn("Error loading config {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Minimal karate bridge for config evaluation - just provides karate.env
     */
    private static class ConfigKarateBridge implements io.karatelabs.js.SimpleObject {
        private final String env;

        ConfigKarateBridge(String env) {
            this.env = env;
        }

        @Override
        public Object jsGet(String key) {
            if ("env".equals(key)) {
                return env;
            }
            return null;
        }
    }

    public SuiteResult run() {
        result = new SuiteResult();
        result.setStartTime(System.currentTimeMillis());

        // Auto-register NDJSON listener for HTML reports
        if (outputHtmlReport) {
            resultListeners.add(new NdjsonReportListener(outputDir, env));
        }

        try {
            // Load config before anything else
            loadConfig();

            // Notify listeners
            for (ResultListener listener : resultListeners) {
                listener.onSuiteStart(this);
            }

            beforeSuite();

            if (parallel && threadCount > 1) {
                runParallel();
            } else {
                runSequential();
            }

            afterSuite();
        } finally {
            result.setEndTime(System.currentTimeMillis());

            // Notify listeners
            for (ResultListener listener : resultListeners) {
                listener.onSuiteEnd(result);
            }

            // Write reports if enabled
            if (writeReport) {
                writeKarateJsonReport();
            }
            // Note: HTML report is now generated by NdjsonReportListener.onSuiteEnd()
            if (outputJunitXml) {
                JunitXmlWriter.write(result, outputDir);
            }
            if (outputCucumberJson) {
                CucumberJsonWriter.write(result, outputDir);
            }
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
            JvmLogger.info("Report written to: {}", summaryPath);

            // Write individual feature results
            for (FeatureResult fr : result.getFeatureResults()) {
                String featureName = fr.getFeature().getName();
                if (featureName == null || featureName.isEmpty()) {
                    featureName = "feature";
                }
                // Sanitize filename
                String safeName = featureName.replaceAll("[^a-zA-Z0-9_-]", "_");
                Path featurePath = outputDir.resolve(safeName + ".json");
                String featureJson = io.karatelabs.common.Json.of(fr.toKarateJson()).toStringPretty();
                java.nio.file.Files.writeString(featurePath, featureJson);
            }
        } catch (Exception e) {
            JvmLogger.warn("Failed to write karate report: {}", e.getMessage());
        }
    }

    private void runSequential() {
        for (Feature feature : features) {
            FeatureRuntime fr = new FeatureRuntime(this, feature);
            FeatureResult featureResult = fr.call();
            result.addFeatureResult(featureResult);
            featureResult.printSummary();
        }
    }

    private void runParallel() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FeatureResult>> futures = new ArrayList<>();

            for (Feature feature : features) {
                Future<FeatureResult> future = executor.submit(() -> {
                    FeatureRuntime fr = new FeatureRuntime(this, feature);
                    FeatureResult featureResult = fr.call();
                    featureResult.printSummary();
                    return featureResult;
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

    private void beforeSuite() {
        for (RuntimeHook hook : hooks) {
            hook.beforeSuite(this);
        }
    }

    private void afterSuite() {
        for (RuntimeHook hook : hooks) {
            hook.afterSuite(this);
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

    public List<Feature> getFeatures() {
        return features;
    }

    public Map<String, Object> getCallOnceCache() {
        return CALLONCE_CACHE;
    }

    public SuiteResult getResult() {
        return result;
    }

    public Map<String, Object> getConfigVariables() {
        return configVariables;
    }

    public List<RuntimeHook> getHooks() {
        return hooks;
    }

    public List<ResultListener> getResultListeners() {
        return resultListeners;
    }

}
