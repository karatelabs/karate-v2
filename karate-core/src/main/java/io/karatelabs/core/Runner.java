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
import io.karatelabs.gherkin.Feature;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Main entry point for running Karate tests programmatically.
 * <p>
 * Example usage:
 * <pre>
 * SuiteResult result = Runner.path("src/test/resources")
 *     .tags("@smoke")
 *     .karateEnv("dev")
 *     .parallel(5);
 * </pre>
 */
public final class Runner {

    private Runner() {
    }

    /**
     * Start building a test run with one or more paths.
     * Paths can be directories or individual .feature files.
     */
    public static Builder path(String... paths) {
        return new Builder().path(paths);
    }

    /**
     * Start building a test run with a list of paths.
     */
    public static Builder path(List<String> paths) {
        return new Builder().path(paths);
    }

    /**
     * Start building a test run with Feature objects.
     */
    public static Builder features(Feature... features) {
        return new Builder().features(features);
    }

    /**
     * Get a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ========== Builder ==========

    public static class Builder {

        private final List<String> paths = new ArrayList<>();
        private final List<Feature> features = new ArrayList<>();
        private final List<RuntimeHook> hooks = new ArrayList<>();
        private final List<ResultListener> resultListeners = new ArrayList<>();

        private String env;
        private String tags;
        private String scenarioName;
        private String configDir;
        private Path outputDir = Path.of("target/karate-reports");
        private Path workingDir = FileUtils.WORKING_DIR.toPath();
        private boolean dryRun;
        private boolean outputHtmlReport = true;
        private boolean outputNdjson;
        private boolean outputJunitXml;
        private boolean outputCucumberJson;

        Builder() {
        }

        /**
         * Add paths to search for .feature files.
         * Can be directories or individual files.
         */
        public Builder path(String... values) {
            paths.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * Add paths to search for .feature files.
         */
        public Builder path(List<String> values) {
            if (values != null) {
                paths.addAll(values);
            }
            return this;
        }

        /**
         * Add Feature objects directly.
         */
        public Builder features(Feature... values) {
            features.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * Add Feature objects directly.
         */
        public Builder features(Collection<Feature> values) {
            if (values != null) {
                features.addAll(values);
            }
            return this;
        }

        /**
         * Set the karate environment (karate.env).
         */
        public Builder karateEnv(String env) {
            this.env = env;
            return this;
        }

        /**
         * Set tag filter expression.
         * Examples: "@smoke", "~@slow", "@smoke,@fast"
         */
        public Builder tags(String... tagExpressions) {
            if (tagExpressions.length == 1) {
                this.tags = tagExpressions[0];
            } else if (tagExpressions.length > 1) {
                this.tags = String.join(",", tagExpressions);
            }
            return this;
        }

        /**
         * Filter by scenario name (regex supported).
         */
        public Builder scenarioName(String name) {
            this.scenarioName = name;
            return this;
        }

        /**
         * Set the directory containing karate-config.js.
         */
        public Builder configDir(String dir) {
            this.configDir = dir;
            return this;
        }

        /**
         * Set the output directory for reports.
         */
        public Builder outputDir(String dir) {
            if (dir != null) {
                this.outputDir = Path.of(dir);
            }
            return this;
        }

        /**
         * Set the output directory for reports.
         */
        public Builder outputDir(Path dir) {
            if (dir != null) {
                this.outputDir = dir;
            }
            return this;
        }

        /**
         * Set the working directory for relative path resolution.
         * This affects how feature file paths are displayed in reports.
         */
        public Builder workingDir(String dir) {
            if (dir != null) {
                this.workingDir = Path.of(dir).toAbsolutePath().normalize();
            }
            return this;
        }

        /**
         * Set the working directory for relative path resolution.
         * This affects how feature file paths are displayed in reports.
         */
        public Builder workingDir(Path dir) {
            if (dir != null) {
                this.workingDir = dir.toAbsolutePath().normalize();
            }
            return this;
        }

        /**
         * Enable/disable HTML report generation.
         */
        public Builder outputHtmlReport(boolean enabled) {
            this.outputHtmlReport = enabled;
            return this;
        }

        /**
         * Enable/disable NDJSON streaming output.
         * Writes feature results to karate-results.ndjson as they complete.
         */
        public Builder outputNdjson(boolean enabled) {
            this.outputNdjson = enabled;
            return this;
        }

        /**
         * Enable/disable JUnit XML report generation.
         */
        public Builder outputJunitXml(boolean enabled) {
            this.outputJunitXml = enabled;
            return this;
        }

        /**
         * Enable/disable Cucumber JSON report generation.
         */
        public Builder outputCucumberJson(boolean enabled) {
            this.outputCucumberJson = enabled;
            return this;
        }

        /**
         * Enable dry-run mode (parse but don't execute).
         */
        public Builder dryRun(boolean enabled) {
            this.dryRun = enabled;
            return this;
        }

        /**
         * Add a runtime hook.
         */
        public Builder hook(RuntimeHook hook) {
            if (hook != null) {
                hooks.add(hook);
            }
            return this;
        }

        /**
         * Add multiple runtime hooks.
         */
        public Builder hooks(Collection<RuntimeHook> values) {
            if (values != null) {
                hooks.addAll(values);
            }
            return this;
        }

        /**
         * Add a result listener for streaming test results.
         */
        public Builder resultListener(ResultListener listener) {
            if (listener != null) {
                resultListeners.add(listener);
            }
            return this;
        }

        /**
         * Add multiple result listeners.
         */
        public Builder resultListeners(Collection<ResultListener> values) {
            if (values != null) {
                resultListeners.addAll(values);
            }
            return this;
        }

        /**
         * Execute the tests with the specified thread count.
         * This is the terminal operation that runs the tests.
         *
         * @param threadCount number of threads (1 for sequential)
         * @return the test results
         */
        public SuiteResult parallel(int threadCount) {
            Suite suite = buildSuite();
            suite.parallel(Math.max(1, threadCount));

            SuiteResult result = suite.run();

            // Print summary
            result.printSummary(env, threadCount);

            return result;
        }

        /**
         * Build the Suite without running it.
         * Useful for advanced scenarios.
         */
        public Suite buildSuite() {
            // Resolve features from paths
            List<Feature> allFeatures = new ArrayList<>(features);
            for (String path : paths) {
                resolveFeatures(path, allFeatures, workingDir);
            }

            // Build suite
            Suite suite = Suite.of(allFeatures.toArray(new Feature[0]));
            suite.workingDir(workingDir);

            // Apply configuration
            if (env != null) {
                suite.env(env);
            }
            if (tags != null) {
                suite.tags(tags);
            }
            if (configDir != null) {
                suite.configPath(configDir.endsWith(".js")
                        ? configDir
                        : configDir + "/karate-config.js");
            }
            if (outputDir != null) {
                suite.outputDir(outputDir);
            }
            suite.dryRun(dryRun);
            suite.outputHtmlReport(outputHtmlReport);
            suite.outputNdjson(outputNdjson);
            suite.outputJunitXml(outputJunitXml);
            suite.outputCucumberJson(outputCucumberJson);

            // Add hooks
            for (RuntimeHook hook : hooks) {
                suite.hook(hook);
            }

            // Add result listeners
            for (ResultListener listener : resultListeners) {
                suite.resultListener(listener);
            }

            return suite;
        }

        private void resolveFeatures(String path, List<Feature> target, Path root) {
            File file = new File(path);

            if (file.isDirectory()) {
                resolveDirectory(file, target, root);
            } else if (file.exists() && file.getName().endsWith(".feature")) {
                Feature feature = Feature.read(Resource.from(file.toPath(), root));
                target.add(feature);
            } else if (path.startsWith("classpath:")) {
                // Handle classpath resources
                String classpathPath = path.substring("classpath:".length());
                if (classpathPath.startsWith("/")) {
                    classpathPath = classpathPath.substring(1);
                }

                if (classpathPath.endsWith(".feature")) {
                    // Single feature file
                    try {
                        Resource resource = Resource.path(path);
                        Feature feature = Feature.read(resource);
                        target.add(feature);
                    } catch (Exception e) {
                        // Resource not found or not a file
                    }
                } else {
                    // Directory - scan for .feature files
                    List<Resource> resources = Resource.scanClasspath(classpathPath, "feature", null, root);
                    for (Resource resource : resources) {
                        try {
                            Feature feature = Feature.read(resource);
                            target.add(feature);
                        } catch (Exception e) {
                            // Skip invalid feature files
                        }
                    }
                }
            }
        }

        private void resolveDirectory(File dir, List<Feature> target, Path root) {
            File[] files = dir.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file.isDirectory()) {
                    resolveDirectory(file, target, root);
                } else if (file.getName().endsWith(".feature")) {
                    Feature feature = Feature.read(Resource.from(file.toPath(), root));
                    target.add(feature);
                }
            }
        }

        @Override
        public String toString() {
            return "Runner.Builder{paths=" + paths + ", features=" + features.size() + "}";
        }
    }

}
