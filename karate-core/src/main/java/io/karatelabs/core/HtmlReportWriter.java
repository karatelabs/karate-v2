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
import io.karatelabs.js.Engine;
import io.karatelabs.log.JvmLogger;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.MarkupConfig;
import io.karatelabs.markup.ResourceResolver;

import java.io.IOException;
import java.io.InputStream;
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
 * Generates HTML reports using Bootstrap 5 and Alpine.js.
 * <p>
 * Output structure:
 * <pre>
 * target/karate-reports/
 * ├── index.html              (redirects to karate-summary.html)
 * ├── karate-summary.html     (summary page)
 * ├── karate-tags.html        (tags view)
 * ├── karate-timeline.html    (timeline view)
 * ├── features/
 * │   └── {feature-name}.html (per-feature reports)
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

    private HtmlReportWriter() {
    }

    /**
     * Write HTML reports to the specified output directory.
     *
     * @param result    the suite result to render
     * @param outputDir the directory to write reports
     * @param env       the karate environment (may be null)
     */
    public static void write(SuiteResult result, Path outputDir, String env) {
        try {
            // Create directories
            Path featuresDir = outputDir.resolve("features");
            Path resDir = outputDir.resolve("res");
            Files.createDirectories(featuresDir);
            Files.createDirectories(resDir);

            // Copy static resources
            copyStaticResources(resDir);

            // Initialize templating engine
            Engine engine = new Engine();
            Markup markup = initMarkup(engine);

            // Prepare common variables
            Map<String, Object> commonVars = new LinkedHashMap<>();
            commonVars.put("env", env);
            commonVars.put("reportDate", DATE_FORMAT.format(Instant.ofEpochMilli(result.getStartTime())));
            commonVars.put("karateVersion", "2.0");

            // Generate summary page
            writeSummaryPage(markup, result, outputDir, commonVars);

            // Generate feature pages
            for (FeatureResult fr : result.getFeatureResults()) {
                writeFeaturePage(markup, fr, featuresDir, commonVars);
            }

            // Generate tags page
            writeTagsPage(markup, result, outputDir, commonVars);

            // Generate timeline page
            writeTimelinePage(markup, result, outputDir, commonVars);

            // Generate index.html redirect
            writeIndexRedirect(outputDir);

            JvmLogger.info("HTML report written to: {}", outputDir.resolve("karate-summary.html"));

        } catch (Exception e) {
            JvmLogger.warn("Failed to write HTML report: {}", e.getMessage());
            if (JvmLogger.isDebugEnabled()) {
                JvmLogger.debug("HTML report error details", e);
            }
        }
    }

    private static Markup initMarkup(Engine engine) {
        MarkupConfig config = new MarkupConfig();
        config.setResolver(new ClasspathResourceResolver());
        config.setDevMode(false);
        config.setServerMode(false); // plain HTML mode, no cache-busting
        return Markup.init(engine, config);
    }

    private static void writeSummaryPage(Markup markup, SuiteResult result, Path outputDir, Map<String, Object> commonVars) throws IOException {
        Map<String, Object> vars = new LinkedHashMap<>(commonVars);
        vars.put("result", result);
        vars.put("features", buildFeatureSummaryList(result));
        vars.put("summary", result.toKarateJson().get("summary"));

        String html = markup.processPath("karate-summary.html", vars);
        Files.writeString(outputDir.resolve("karate-summary.html"), html);
    }

    private static void writeFeaturePage(Markup markup, FeatureResult fr, Path featuresDir, Map<String, Object> commonVars) throws IOException {
        Map<String, Object> vars = new LinkedHashMap<>(commonVars);
        vars.put("feature", fr.toKarateJson());
        vars.put("scenarios", fr.getScenarioResults());

        String html = markup.processPath("karate-feature.html", vars);

        // Safe filename
        String fileName = getFeatureFileName(fr) + ".html";
        Files.writeString(featuresDir.resolve(fileName), html);
    }

    private static void writeTagsPage(Markup markup, SuiteResult result, Path outputDir, Map<String, Object> commonVars) throws IOException {
        Map<String, Object> vars = new LinkedHashMap<>(commonVars);
        vars.put("tags", buildTagsData(result));

        String html = markup.processPath("karate-tags.html", vars);
        Files.writeString(outputDir.resolve("karate-tags.html"), html);
    }

    private static void writeTimelinePage(Markup markup, SuiteResult result, Path outputDir, Map<String, Object> commonVars) throws IOException {
        Map<String, Object> vars = new LinkedHashMap<>(commonVars);
        vars.put("timeline", buildTimelineData(result));

        String html = markup.processPath("karate-timeline.html", vars);
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

    // ========== Data Building Helpers ==========

    private static List<Map<String, Object>> buildFeatureSummaryList(SuiteResult result) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (FeatureResult fr : result.getFeatureResults()) {
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("name", fr.getFeature().getName());
            feature.put("relativePath", fr.getDisplayName());
            feature.put("fileName", getFeatureFileName(fr));
            feature.put("scenarioCount", fr.getScenarioCount());
            feature.put("passedCount", fr.getPassedCount());
            feature.put("failedCount", fr.getFailedCount());
            feature.put("durationMillis", fr.getDurationMillis());
            feature.put("passed", fr.isPassed());
            feature.put("failed", fr.isFailed());
            features.add(feature);
        }
        return features;
    }

    private static Map<String, Object> buildTagsData(SuiteResult result) {
        Map<String, List<Map<String, Object>>> tagMap = new LinkedHashMap<>();

        for (FeatureResult fr : result.getFeatureResults()) {
            for (ScenarioResult sr : fr.getScenarioResults()) {
                if (sr.getScenario().getTags() != null) {
                    for (var tag : sr.getScenario().getTags()) {
                        String tagName = tag.toString();
                        tagMap.computeIfAbsent(tagName, k -> new ArrayList<>()).add(Map.of(
                                "featureName", fr.getFeature().getName(),
                                "featureFile", getFeatureFileName(fr),
                                "scenarioName", sr.getScenario().getName(),
                                "passed", sr.isPassed(),
                                "failed", sr.isFailed(),
                                "durationMillis", sr.getDurationMillis()
                        ));
                    }
                }
            }
        }

        // Convert map to list of entries for easier template iteration
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

    private static Map<String, Object> buildTimelineData(SuiteResult result) {
        List<Map<String, Object>> items = new ArrayList<>();
        int id = 0;

        for (FeatureResult fr : result.getFeatureResults()) {
            for (ScenarioResult sr : fr.getScenarioResults()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id++);
                item.put("group", sr.getThreadName() != null ? sr.getThreadName() : "main");
                item.put("content", sr.getScenario().getName());
                item.put("start", sr.getStartTime());
                item.put("end", sr.getEndTime());
                item.put("className", sr.isFailed() ? "failed" : "passed");
                item.put("featureName", fr.getFeature().getName());
                item.put("featureFile", getFeatureFileName(fr));
                items.add(item);
            }
        }

        // Collect unique thread groups
        List<String> groups = items.stream()
                .map(i -> (String) i.get("group"))
                .distinct()
                .toList();

        Map<String, Object> timelineData = new LinkedHashMap<>();
        timelineData.put("items", items);
        timelineData.put("groups", groups);
        timelineData.put("startTime", result.getStartTime());
        timelineData.put("endTime", result.getEndTime());
        return timelineData;
    }

    private static String getFeatureFileName(FeatureResult fr) {
        String name = fr.getFeature().getName();
        if (name == null || name.isEmpty()) {
            name = fr.getDisplayName();
        }
        // Sanitize for filename
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    // ========== Resource Resolver ==========

    private static class ClasspathResourceResolver implements ResourceResolver {

        @Override
        public Resource resolve(String path, Resource caller) {
            String fullPath = RESOURCE_ROOT + path;
            return Resource.path("classpath:" + fullPath);
        }
    }

}
