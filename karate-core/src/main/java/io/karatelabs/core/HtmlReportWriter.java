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
import io.karatelabs.log.JvmLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates HTML reports with inlined JSON data and Alpine.js client-side rendering.
 * <p>
 * This implementation uses a single-file HTML architecture where:
 * <ul>
 *   <li>JSON data is inlined in a {@code <script type="application/json">} tag</li>
 *   <li>Alpine.js renders the content client-side</li>
 *   <li>No server-side template rendering is required</li>
 * </ul>
 * <p>
 * Output structure:
 * <pre>
 * target/karate-reports/
 * ├── karate-results.ndjson     (streamed during execution)
 * ├── index.html                (redirects to karate-summary.html)
 * ├── karate-summary.html       (summary page)
 * ├── karate-tags.html          (tags view)
 * ├── karate-timeline.html      (timeline view)
 * ├── features/
 * │   └── {feature-name}.html   (per-feature reports)
 * └── res/
 *     ├── bootstrap.min.css
 *     ├── alpine.min.js
 *     └── karate-report.css
 * </pre>
 */
public final class HtmlReportWriter {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String RESOURCE_ROOT = "io/karatelabs/report/";

    private static final String[] STATIC_RESOURCES = {
            "bootstrap.min.css",
            "bootstrap.bundle.min.js",
            "alpine.min.js",
            "karate-report.css",
            "karate-report.js",
            "karate-logo.svg",
            "favicon.ico"
    };

    private static final String DATA_PLACEHOLDER = "/* KARATE_DATA */";

    private HtmlReportWriter() {
    }

    /**
     * Generate HTML reports from an NDJSON file.
     * This is the primary entry point when using streaming via {@link NdjsonReportListener}.
     *
     * @param ndjsonPath the path to the NDJSON file
     * @param outputDir  the directory to write reports
     */
    public static void writeFromNdjson(Path ndjsonPath, Path outputDir) {
        try {
            // Parse NDJSON file
            NdjsonData data = parseNdjson(ndjsonPath);

            // Generate reports
            writeReports(data.suiteData, data.features, outputDir);

            JvmLogger.info("HTML report written to: {}", outputDir.resolve("karate-summary.html"));

        } catch (Exception e) {
            JvmLogger.warn("Failed to write HTML report from NDJSON: {}", e.getMessage());
            if (JvmLogger.isDebugEnabled()) {
                JvmLogger.debug("HTML report error details", e);
            }
        }
    }

    /**
     * Generate HTML reports from a SuiteResult.
     * This is the backward-compatible entry point for direct invocation.
     *
     * @param result    the suite result to render
     * @param outputDir the directory to write reports
     * @param env       the karate environment (may be null)
     */
    public static void write(SuiteResult result, Path outputDir, String env) {
        try {
            // Build data structures from SuiteResult
            Map<String, Object> suiteData = buildSuiteData(result, env);
            List<Map<String, Object>> features = buildFeaturesList(result);

            // Generate reports
            writeReports(suiteData, features, outputDir);

            JvmLogger.info("HTML report written to: {}", outputDir.resolve("karate-summary.html"));

        } catch (Exception e) {
            JvmLogger.warn("Failed to write HTML report: {}", e.getMessage());
            if (JvmLogger.isDebugEnabled()) {
                JvmLogger.debug("HTML report error details", e);
            }
        }
    }

    /**
     * Core report generation - same for both NDJSON and SuiteResult paths.
     */
    private static void writeReports(Map<String, Object> suiteData,
                                     List<Map<String, Object>> features,
                                     Path outputDir) throws IOException {
        // Create directories
        Path featuresDir = outputDir.resolve("features");
        Path resDir = outputDir.resolve("res");
        Files.createDirectories(featuresDir);
        Files.createDirectories(resDir);

        // Copy static resources
        copyStaticResources(resDir);

        // Generate summary page
        writeSummaryHtml(suiteData, features, outputDir);

        // Generate feature pages
        for (Map<String, Object> feature : features) {
            writeFeatureHtml(feature, featuresDir);
        }

        // Generate tags page
        writeTagsHtml(suiteData, features, outputDir);

        // Generate timeline page
        writeTimelineHtml(suiteData, features, outputDir);

        // Generate index redirect
        writeIndexRedirect(outputDir);
    }

    // ========== HTML Generation ==========

    private static void writeSummaryHtml(Map<String, Object> suiteData,
                                         List<Map<String, Object>> features,
                                         Path outputDir) throws IOException {
        Map<String, Object> pageData = new LinkedHashMap<>(suiteData);
        pageData.put("features", buildFeatureSummaryList(features));

        String template = loadTemplate("karate-summary.html");
        String html = inlineJson(template, pageData);
        Files.writeString(outputDir.resolve("karate-summary.html"), html);
    }

    private static void writeFeatureHtml(Map<String, Object> feature, Path featuresDir) throws IOException {
        String template = loadTemplate("karate-feature.html");
        String html = inlineJson(template, feature);

        String fileName = getFeatureFileName(feature) + ".html";
        Files.writeString(featuresDir.resolve(fileName), html);
    }

    private static void writeTagsHtml(Map<String, Object> suiteData,
                                      List<Map<String, Object>> features,
                                      Path outputDir) throws IOException {
        Map<String, Object> pageData = new LinkedHashMap<>(suiteData);
        pageData.put("tags", buildTagsData(features));

        String template = loadTemplate("karate-tags.html");
        String html = inlineJson(template, pageData);
        Files.writeString(outputDir.resolve("karate-tags.html"), html);
    }

    private static void writeTimelineHtml(Map<String, Object> suiteData,
                                          List<Map<String, Object>> features,
                                          Path outputDir) throws IOException {
        Map<String, Object> pageData = new LinkedHashMap<>(suiteData);
        pageData.put("timeline", buildTimelineData(features));

        String template = loadTemplate("karate-timeline.html");
        String html = inlineJson(template, pageData);
        Files.writeString(outputDir.resolve("karate-timeline.html"), html);
    }

    private static void writeIndexRedirect(Path outputDir) throws IOException {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta http-equiv="refresh" content="0; url=karate-summary.html">
                </head>
                <body>
                    <a href="karate-summary.html">Redirecting to Karate Summary Report...</a>
                </body>
                </html>
                """;
        Files.writeString(outputDir.resolve("index.html"), html);
    }

    /**
     * Inline JSON data into an HTML template.
     * Replaces the placeholder with the JSON string.
     */
    private static String inlineJson(String template, Object data) {
        String json = Json.of(data).toStringPretty();
        return template.replace(DATA_PLACEHOLDER, json);
    }

    // ========== Template and Resource Loading ==========

    private static String loadTemplate(String name) throws IOException {
        String path = RESOURCE_ROOT + name;
        try (InputStream is = HtmlReportWriter.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Template not found: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        }
    }

    private static void copyStaticResources(Path resDir) throws IOException {
        for (String resourceName : STATIC_RESOURCES) {
            String resourcePath = RESOURCE_ROOT + "res/" + resourceName;
            try (InputStream is = HtmlReportWriter.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    Files.copy(is, resDir.resolve(resourceName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    JvmLogger.debug("Static resource not found: {}", resourcePath);
                }
            }
        }
    }

    // ========== NDJSON Parsing ==========

    private static NdjsonData parseNdjson(Path ndjsonPath) throws IOException {
        NdjsonData data = new NdjsonData();
        List<String> lines = Files.readAllLines(ndjsonPath, StandardCharsets.UTF_8);

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) Json.of(line).value();
            String type = (String) obj.get("t");

            if ("suite".equals(type)) {
                data.suiteData = buildSuiteDataFromNdjson(obj);
            } else if ("feature".equals(type)) {
                data.features.add(obj);
            } else if ("suite_end".equals(type)) {
                // Update suite data with final counts
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("feature_count", data.features.size());
                summary.put("feature_passed", obj.get("featuresPassed"));
                summary.put("feature_failed", obj.get("featuresFailed"));
                summary.put("scenario_passed", obj.get("scenariosPassed"));
                summary.put("scenario_failed", obj.get("scenariosFailed"));
                summary.put("duration_millis", obj.get("ms"));

                int scenarioCount = 0;
                for (Map<String, Object> f : data.features) {
                    @SuppressWarnings("unchecked")
                    List<?> scenarios = (List<?>) f.get("scenarios");
                    scenarioCount += scenarios != null ? scenarios.size() : 0;
                }
                summary.put("scenario_count", scenarioCount);
                summary.put("status", ((Number) obj.get("featuresFailed")).intValue() > 0 ? "failed" : "passed");

                data.suiteData.put("summary", summary);
            }
        }

        return data;
    }

    private static Map<String, Object> buildSuiteDataFromNdjson(Map<String, Object> suiteHeader) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("env", suiteHeader.get("env"));
        data.put("threads", suiteHeader.get("threads"));
        data.put("karateVersion", suiteHeader.get("version"));
        data.put("reportDate", suiteHeader.get("time"));
        return data;
    }

    // ========== Data Building from SuiteResult ==========

    private static Map<String, Object> buildSuiteData(SuiteResult result, String env) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("env", env);
        data.put("reportDate", DATE_FORMAT.format(Instant.ofEpochMilli(result.getStartTime())));
        data.put("karateVersion", "2.0");

        // Summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("feature_count", result.getFeatureCount());
        summary.put("feature_passed", result.getFeaturePassedCount());
        summary.put("feature_failed", result.getFeatureFailedCount());
        summary.put("scenario_count", result.getScenarioCount());
        summary.put("scenario_passed", result.getScenarioPassedCount());
        summary.put("scenario_failed", result.getScenarioFailedCount());
        summary.put("duration_millis", result.getDurationMillis());
        summary.put("status", result.isFailed() ? "failed" : "passed");
        data.put("summary", summary);

        return data;
    }

    private static List<Map<String, Object>> buildFeaturesList(SuiteResult result) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (FeatureResult fr : result.getFeatureResults()) {
            features.add(buildFeatureData(fr));
        }
        return features;
    }

    private static Map<String, Object> buildFeatureData(FeatureResult fr) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", fr.getDisplayName());
        data.put("name", fr.getFeature().getName());
        data.put("passed", fr.isPassed());
        data.put("ms", fr.getDurationMillis());

        // Scenarios
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (ScenarioResult sr : fr.getScenarioResults()) {
            scenarios.add(buildScenarioData(sr));
        }
        data.put("scenarios", scenarios);

        return data;
    }

    private static Map<String, Object> buildScenarioData(ScenarioResult sr) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", sr.getScenario().getName());
        data.put("line", sr.getScenario().getLine());
        data.put("passed", sr.isPassed());
        data.put("ms", sr.getDurationMillis());

        if (sr.getThreadName() != null) {
            data.put("thread", sr.getThreadName());
        }

        // Tags
        var tags = sr.getScenario().getTags();
        if (tags != null && !tags.isEmpty()) {
            List<String> tagNames = new ArrayList<>();
            for (var tag : tags) {
                tagNames.add(tag.toString());
            }
            data.put("tags", tagNames);
        }

        // Steps
        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepResult step : sr.getStepResults()) {
            steps.add(buildStepData(step));
        }
        data.put("steps", steps);

        // Error
        if (sr.isFailed() && sr.getFailureMessage() != null) {
            data.put("error", sr.getFailureMessage());
        }

        return data;
    }

    private static Map<String, Object> buildStepData(StepResult step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", step.getStep().getText());
        data.put("status", step.getStatus().name().toLowerCase());
        data.put("ms", step.getDurationNanos() / 1_000_000);

        if (step.getLog() != null && !step.getLog().isEmpty()) {
            data.put("logs", step.getLog());
        }

        if (step.getError() != null) {
            data.put("error", step.getError().getMessage());
        }

        return data;
    }

    // ========== Data Building for Summary Page ==========

    private static List<Map<String, Object>> buildFeatureSummaryList(List<Map<String, Object>> features) {
        List<Map<String, Object>> summaryList = new ArrayList<>();
        for (Map<String, Object> feature : features) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", feature.get("name"));
            summary.put("relativePath", feature.get("path"));
            summary.put("fileName", getFeatureFileName(feature));
            summary.put("passed", feature.get("passed"));
            summary.put("failed", !((Boolean) feature.get("passed")));
            summary.put("durationMillis", feature.get("ms"));

            // Count scenarios
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) feature.get("scenarios");
            int total = scenarios != null ? scenarios.size() : 0;
            int passed = 0;
            int failed = 0;
            if (scenarios != null) {
                for (Map<String, Object> s : scenarios) {
                    if (Boolean.TRUE.equals(s.get("passed"))) {
                        passed++;
                    } else {
                        failed++;
                    }
                }
            }
            summary.put("scenarioCount", total);
            summary.put("passedCount", passed);
            summary.put("failedCount", failed);

            summaryList.add(summary);
        }
        return summaryList;
    }

    // ========== Data Building for Tags Page ==========

    private static Map<String, Object> buildTagsData(List<Map<String, Object>> features) {
        Map<String, List<Map<String, Object>>> tagMap = new LinkedHashMap<>();

        for (Map<String, Object> feature : features) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) feature.get("scenarios");
            if (scenarios == null) continue;

            for (Map<String, Object> scenario : scenarios) {
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) scenario.get("tags");
                if (tags == null) continue;

                for (String tag : tags) {
                    tagMap.computeIfAbsent(tag, k -> new ArrayList<>()).add(Map.of(
                            "featureName", feature.get("name"),
                            "featureFile", getFeatureFileName(feature),
                            "scenarioName", scenario.get("name"),
                            "passed", scenario.get("passed"),
                            "failed", !((Boolean) scenario.get("passed")),
                            "durationMillis", scenario.get("ms")
                    ));
                }
            }
        }

        // Convert to list for template
        List<Map<String, Object>> tagList = new ArrayList<>();
        for (var entry : tagMap.entrySet()) {
            Map<String, Object> tagEntry = new LinkedHashMap<>();
            tagEntry.put("name", entry.getKey());
            tagEntry.put("scenarios", entry.getValue());
            tagList.add(tagEntry);
        }

        Map<String, Object> tagsData = new LinkedHashMap<>();
        tagsData.put("tagList", tagList);
        tagsData.put("tagCount", tagMap.size());
        return tagsData;
    }

    // ========== Data Building for Timeline Page ==========

    private static Map<String, Object> buildTimelineData(List<Map<String, Object>> features) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        int id = 0;
        long minStart = Long.MAX_VALUE;
        long maxEnd = 0;

        for (Map<String, Object> feature : features) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) feature.get("scenarios");
            if (scenarios == null) continue;

            for (Map<String, Object> scenario : scenarios) {
                String thread = scenario.get("thread") != null ? (String) scenario.get("thread") : "main";
                if (!groups.contains(thread)) {
                    groups.add(thread);
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id++);
                item.put("group", thread);
                item.put("content", scenario.get("name"));
                item.put("className", Boolean.TRUE.equals(scenario.get("passed")) ? "passed" : "failed");
                item.put("featureName", feature.get("name"));
                item.put("featureFile", getFeatureFileName(feature));
                item.put("ms", scenario.get("ms"));
                items.add(item);
            }
        }

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("items", items);
        timeline.put("groups", groups);
        return timeline;
    }

    // ========== Utility ==========

    private static String getFeatureFileName(Map<String, Object> feature) {
        String name = (String) feature.get("name");
        if (name == null || name.isEmpty()) {
            name = (String) feature.get("path");
        }
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    /**
     * Internal data holder for NDJSON parsing.
     */
    private static class NdjsonData {
        Map<String, Object> suiteData = new LinkedHashMap<>();
        List<Map<String, Object>> features = new ArrayList<>();
    }

}
