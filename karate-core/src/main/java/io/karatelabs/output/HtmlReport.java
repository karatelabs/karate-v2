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
package io.karatelabs.output;

import io.karatelabs.common.Json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent API for HTML report aggregation across multiple test runs.
 * <p>
 * This class allows merging JSON Lines files from different test runs to create
 * a combined HTML report. This is useful for aggregating results from:
 * <ul>
 *   <li>Parallel test shards</li>
 *   <li>Retry runs</li>
 *   <li>Different environments</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * HtmlReport.aggregate()
 *     .json("target/run1/karate-results.jsonl")
 *     .json("target/run2/karate-results.jsonl")
 *     .outputDir("target/combined-report")
 *     .generate();
 * </pre>
 */
public final class HtmlReport {

    private static final Logger logger = LoggerFactory.getLogger("karate.runtime");

    private HtmlReport() {
    }

    /**
     * Start building an aggregate report.
     *
     * @return a new aggregate builder
     */
    public static AggregateBuilder aggregate() {
        return new AggregateBuilder();
    }

    /**
     * Builder for aggregating multiple JSON Lines files into a single HTML report.
     */
    public static class AggregateBuilder {
        private final List<Path> jsonlFiles = new ArrayList<>();
        private Path outputDir;

        /**
         * Add a JSON Lines file to aggregate.
         *
         * @param path path to the JSON Lines file
         * @return this builder
         */
        public AggregateBuilder json(String path) {
            jsonlFiles.add(Path.of(path));
            return this;
        }

        /**
         * Add a JSON Lines file to aggregate.
         *
         * @param path path to the JSON Lines file
         * @return this builder
         */
        public AggregateBuilder json(Path path) {
            jsonlFiles.add(path);
            return this;
        }

        /**
         * Set the output directory for the aggregated report.
         *
         * @param dir output directory path
         * @return this builder
         */
        public AggregateBuilder outputDir(String dir) {
            this.outputDir = Path.of(dir);
            return this;
        }

        /**
         * Set the output directory for the aggregated report.
         *
         * @param dir output directory path
         * @return this builder
         */
        public AggregateBuilder outputDir(Path dir) {
            this.outputDir = dir;
            return this;
        }

        /**
         * Generate the aggregated HTML report.
         *
         * @throws IllegalStateException if no JSON Lines files or output directory specified
         */
        public void generate() {
            if (jsonlFiles.isEmpty()) {
                throw new IllegalStateException("No JSON Lines files specified for aggregation");
            }
            if (outputDir == null) {
                throw new IllegalStateException("Output directory not specified");
            }

            try {
                // Parse and merge all JSON Lines files
                List<Map<String, Object>> allFeatures = new ArrayList<>();
                Map<String, Object> suiteData = new LinkedHashMap<>();
                int totalFeaturesPassed = 0;
                int totalFeaturesFailed = 0;
                int totalScenariosPassed = 0;
                int totalScenariosFailed = 0;
                long totalDuration = 0;

                for (Path jsonlFile : jsonlFiles) {
                    if (!Files.exists(jsonlFile)) {
                        logger.warn("JSON Lines file not found, skipping: {}", jsonlFile);
                        continue;
                    }

                    List<String> lines = Files.readAllLines(jsonlFile, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> obj = (Map<String, Object>) Json.of(line).value();
                        String type = (String) obj.get("t");

                        if ("suite".equals(type)) {
                            // Take metadata from the first suite header
                            if (suiteData.isEmpty()) {
                                suiteData.put("env", obj.get("env"));
                                suiteData.put("threads", obj.get("threads"));
                                suiteData.put("karateVersion", obj.get("version"));
                                suiteData.put("reportDate", obj.get("time"));
                            }
                        } else if ("feature".equals(type)) {
                            allFeatures.add(obj);
                        } else if ("suite_end".equals(type)) {
                            totalFeaturesPassed += ((Number) obj.getOrDefault("featuresPassed", 0)).intValue();
                            totalFeaturesFailed += ((Number) obj.getOrDefault("featuresFailed", 0)).intValue();
                            totalScenariosPassed += ((Number) obj.getOrDefault("scenariosPassed", 0)).intValue();
                            totalScenariosFailed += ((Number) obj.getOrDefault("scenariosFailed", 0)).intValue();
                            totalDuration += ((Number) obj.getOrDefault("ms", 0)).longValue();
                        }
                    }
                }

                // Build aggregated summary
                int totalScenarios = 0;
                for (Map<String, Object> feature : allFeatures) {
                    @SuppressWarnings("unchecked")
                    List<?> scenarios = (List<?>) feature.get("scenarios");
                    totalScenarios += scenarios != null ? scenarios.size() : 0;
                }

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("feature_count", allFeatures.size());
                summary.put("feature_passed", totalFeaturesPassed);
                summary.put("feature_failed", totalFeaturesFailed);
                summary.put("scenario_count", totalScenarios);
                summary.put("scenario_passed", totalScenariosPassed);
                summary.put("scenario_failed", totalScenariosFailed);
                summary.put("duration_millis", totalDuration);
                summary.put("status", totalFeaturesFailed > 0 ? "failed" : "passed");

                suiteData.put("summary", summary);

                // Write merged JSON Lines
                Path mergedJsonl = outputDir.resolve("karate-results.jsonl");
                Files.createDirectories(outputDir);
                writeMergedJsonLines(mergedJsonl, suiteData, allFeatures, summary);

                // Generate HTML from merged data
                HtmlReportWriter.writeFromJsonLines(mergedJsonl, outputDir);

                logger.info("Aggregated report generated at: {}", outputDir);

            } catch (IOException e) {
                throw new RuntimeException("Failed to generate aggregated report: " + e.getMessage(), e);
            }
        }

        private void writeMergedJsonLines(Path path, Map<String, Object> suiteData,
                                        List<Map<String, Object>> features,
                                        Map<String, Object> summary) throws IOException {
            StringBuilder sb = new StringBuilder();

            // Suite header
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("t", "suite");
            header.put("time", suiteData.get("reportDate"));
            header.put("threads", suiteData.get("threads"));
            if (suiteData.get("env") != null) {
                header.put("env", suiteData.get("env"));
            }
            header.put("version", suiteData.get("karateVersion"));
            sb.append(Json.stringifyStrict(header)).append("\n");

            // Feature lines
            for (Map<String, Object> feature : features) {
                sb.append(Json.stringifyStrict(feature)).append("\n");
            }

            // Suite end
            Map<String, Object> suiteEnd = new LinkedHashMap<>();
            suiteEnd.put("t", "suite_end");
            suiteEnd.put("featuresPassed", summary.get("feature_passed"));
            suiteEnd.put("featuresFailed", summary.get("feature_failed"));
            suiteEnd.put("scenariosPassed", summary.get("scenario_passed"));
            suiteEnd.put("scenariosFailed", summary.get("scenario_failed"));
            suiteEnd.put("ms", summary.get("duration_millis"));
            sb.append(Json.stringifyStrict(suiteEnd)).append("\n");

            Files.writeString(path, sb.toString());
        }
    }

}
