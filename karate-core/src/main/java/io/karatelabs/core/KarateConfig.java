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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents Karate configuration loaded from a JSON file.
 * <p>
 * Example karate.json:
 * <pre>
 * {
 *   "paths": ["src/test/features/"],
 *   "tags": ["@smoke", "~@slow"],
 *   "env": "dev",
 *   "threads": 5,
 *   "scenarioName": ".*login.*",
 *   "configDir": "src/test/resources",
 *   "dryRun": false,
 *   "clean": false,
 *   "output": {
 *     "dir": "target/karate-reports",
 *     "html": true,
 *     "junitXml": false,
 *     "cucumberJson": false,
 *     "ndjson": false
 *   }
 * }
 * </pre>
 */
public class KarateConfig {

    private List<String> paths = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private String env;
    private int threads = 1;
    private String scenarioName;
    private String configDir;
    private boolean dryRun;
    private boolean clean;
    private OutputConfig output = new OutputConfig();

    /**
     * Output configuration nested object.
     */
    public static class OutputConfig {
        private String dir = "target/karate-reports";
        private boolean html = true;
        private boolean junitXml;
        private boolean cucumberJson;
        private boolean ndjson;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public boolean isHtml() {
            return html;
        }

        public void setHtml(boolean html) {
            this.html = html;
        }

        public boolean isJunitXml() {
            return junitXml;
        }

        public void setJunitXml(boolean junitXml) {
            this.junitXml = junitXml;
        }

        public boolean isCucumberJson() {
            return cucumberJson;
        }

        public void setCucumberJson(boolean cucumberJson) {
            this.cucumberJson = cucumberJson;
        }

        public boolean isNdjson() {
            return ndjson;
        }

        public void setNdjson(boolean ndjson) {
            this.ndjson = ndjson;
        }
    }

    /**
     * Load configuration from a JSON file.
     *
     * @param configPath path to the JSON config file
     * @return parsed KarateConfig
     * @throws RuntimeException if file cannot be read or parsed
     */
    public static KarateConfig load(String configPath) {
        return load(Path.of(configPath));
    }

    /**
     * Load configuration from a JSON file.
     *
     * @param configPath path to the JSON config file
     * @return parsed KarateConfig
     * @throws RuntimeException if file cannot be read or parsed
     */
    public static KarateConfig load(Path configPath) {
        try {
            String content = Files.readString(configPath);
            return parse(content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from: " + configPath, e);
        }
    }

    /**
     * Parse configuration from a JSON string.
     *
     * @param json JSON string
     * @return parsed KarateConfig
     * @throws RuntimeException if JSON is invalid
     */
    public static KarateConfig parse(String json) {
        Json j = Json.of(json);
        if (!j.isObject()) {
            throw new RuntimeException("Invalid config: expected JSON object");
        }
        KarateConfig config = new KarateConfig();

        // Parse paths
        j.<List<String>>getOptional("paths").ifPresent(config::setPaths);

        // Parse tags
        j.<List<String>>getOptional("tags").ifPresent(config::setTags);

        // Parse simple fields
        j.<String>getOptional("env").ifPresent(config::setEnv);
        j.<Integer>getOptional("threads").ifPresent(config::setThreads);
        j.<String>getOptional("scenarioName").ifPresent(config::setScenarioName);
        j.<String>getOptional("configDir").ifPresent(config::setConfigDir);
        j.<Boolean>getOptional("dryRun").ifPresent(config::setDryRun);
        j.<Boolean>getOptional("clean").ifPresent(config::setClean);

        // Parse output config
        if (j.pathExists("output")) {
            OutputConfig output = config.getOutput();
            j.<String>getOptional("output.dir").ifPresent(output::setDir);
            j.<Boolean>getOptional("output.html").ifPresent(output::setHtml);
            j.<Boolean>getOptional("output.junitXml").ifPresent(output::setJunitXml);
            j.<Boolean>getOptional("output.cucumberJson").ifPresent(output::setCucumberJson);
            j.<Boolean>getOptional("output.ndjson").ifPresent(output::setNdjson);
        }

        return config;
    }

    /**
     * Apply this configuration to a Runner.Builder.
     * CLI options should override config file values, so call this before applying CLI options.
     *
     * @param builder the Runner.Builder to configure
     * @return the same builder for chaining
     */
    public Runner.Builder applyTo(Runner.Builder builder) {
        if (!paths.isEmpty()) {
            builder.path(paths);
        }
        if (!tags.isEmpty()) {
            builder.tags(tags.toArray(new String[0]));
        }
        if (env != null) {
            builder.karateEnv(env);
        }
        if (scenarioName != null) {
            builder.scenarioName(scenarioName);
        }
        if (configDir != null) {
            builder.configDir(configDir);
        }
        builder.dryRun(dryRun);

        // Output settings
        builder.outputDir(output.dir);
        builder.outputHtmlReport(output.html);
        builder.outputJunitXml(output.junitXml);
        builder.outputCucumberJson(output.cucumberJson);
        builder.outputNdjson(output.ndjson);

        return builder;
    }

    // ========== Getters and Setters ==========

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getConfigDir() {
        return configDir;
    }

    public void setConfigDir(String configDir) {
        this.configDir = configDir;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isClean() {
        return clean;
    }

    public void setClean(boolean clean) {
        this.clean = clean;
    }

    public OutputConfig getOutput() {
        return output;
    }

    public void setOutput(OutputConfig output) {
        this.output = output != null ? output : new OutputConfig();
    }

}
