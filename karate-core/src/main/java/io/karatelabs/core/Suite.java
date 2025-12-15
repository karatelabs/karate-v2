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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
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

    // Hooks
    private final List<RuntimeHook> hooks = new ArrayList<>();

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

    // ========== Execution ==========

    public SuiteResult run() {
        result = new SuiteResult();
        result.setStartTime(System.currentTimeMillis());

        try {
            beforeSuite();

            if (parallel && threadCount > 1) {
                runParallel();
            } else {
                runSequential();
            }

            afterSuite();
        } finally {
            result.setEndTime(System.currentTimeMillis());
        }

        return result;
    }

    private void runSequential() {
        for (Feature feature : features) {
            FeatureRuntime fr = new FeatureRuntime(this, feature);
            FeatureResult featureResult = fr.call();
            result.addFeatureResult(featureResult);
        }
    }

    private void runParallel() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FeatureResult>> futures = new ArrayList<>();

            for (Feature feature : features) {
                Future<FeatureResult> future = executor.submit(() -> {
                    FeatureRuntime fr = new FeatureRuntime(this, feature);
                    return fr.call();
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

}
