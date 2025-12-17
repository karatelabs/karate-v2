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

import io.karatelabs.log.JvmLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ResultListener} that generates HTML reports asynchronously.
 * <p>
 * This listener writes feature HTML files as features complete using a single-thread
 * executor, then generates summary pages at suite end. This approach:
 * <ul>
 *   <li>Keeps only small summary data in memory</li>
 *   <li>Does not block test execution during report generation</li>
 *   <li>Makes partial results available as tests complete</li>
 * </ul>
 */
public class HtmlReportListener implements ResultListener {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path outputDir;
    private final String env;
    private final ExecutorService executor;
    private final List<FeatureSummary> summaries = new CopyOnWriteArrayList<>();

    private long suiteStartTime;
    private int threadCount;
    private boolean resourcesCopied = false;

    /**
     * Create a new HTML report listener.
     *
     * @param outputDir the directory to write reports
     * @param env       the karate environment (may be null)
     */
    public HtmlReportListener(Path outputDir, String env) {
        this.outputDir = outputDir;
        this.env = env;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "karate-html-report");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onSuiteStart(Suite suite) {
        suiteStartTime = System.currentTimeMillis();
        threadCount = suite.getThreadCount();

        // Create directories eagerly
        try {
            Files.createDirectories(outputDir.resolve("features"));
            Files.createDirectories(outputDir.resolve("res"));
        } catch (Exception e) {
            JvmLogger.warn("Failed to create report directories: {}", e.getMessage());
        }
    }

    @Override
    public void onFeatureEnd(FeatureResult result) {
        // Sort scenarios for deterministic ordering in reports
        result.sortScenarioResults();

        // Collect summary in memory (small)
        summaries.add(new FeatureSummary(result));

        // Queue feature HTML generation (async)
        executor.submit(() -> {
            try {
                ensureResourcesCopied();
                HtmlReportWriter.writeFeatureHtml(result, outputDir);
            } catch (Exception e) {
                JvmLogger.warn("Failed to write feature HTML for {}: {}", result.getDisplayName(), e.getMessage());
            }
        });
    }

    @Override
    public void onSuiteEnd(SuiteResult result) {
        try {
            // Ensure resources are copied
            ensureResourcesCopied();

            // Write summary pages
            HtmlReportWriter.writeSummaryPages(summaries, result, outputDir, env);

            // Write timeline page
            HtmlReportWriter.writeTimelineHtml(summaries, result, outputDir, env, threadCount);

            JvmLogger.info("HTML report written to: {}", outputDir.resolve("karate-summary.html"));

        } catch (Exception e) {
            JvmLogger.warn("Failed to write HTML summary: {}", e.getMessage());
        } finally {
            // Wait for all feature HTML writes to complete
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    JvmLogger.warn("HTML report executor did not complete in time");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private synchronized void ensureResourcesCopied() {
        if (!resourcesCopied) {
            try {
                HtmlReportWriter.copyStaticResources(outputDir.resolve("res"));
                resourcesCopied = true;
            } catch (Exception e) {
                JvmLogger.warn("Failed to copy static resources: {}", e.getMessage());
            }
        }
    }

    /**
     * Small in-memory summary of a scenario result for timeline.
     */
    public static class ScenarioSummary {
        private final String name;
        private final String refId;
        private final String featureName;
        private final boolean passed;
        private final long startTime;
        private final long endTime;
        private final String threadName;

        public ScenarioSummary(ScenarioResult sr, String featureName) {
            this.name = sr.getScenario().getName();
            this.refId = sr.getScenario().getRefId();
            this.featureName = featureName;
            this.passed = sr.isPassed();
            this.startTime = sr.getStartTime();
            this.endTime = sr.getEndTime();
            this.threadName = sr.getThreadName();
        }

        public String getName() { return name; }
        public String getRefId() { return refId; }
        public String getFeatureName() { return featureName; }
        public boolean isPassed() { return passed; }
        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }
        public String getThreadName() { return threadName; }
    }

    /**
     * Small in-memory summary of a feature result.
     * Contains only the data needed for summary pages, not full step details.
     */
    public static class FeatureSummary {
        private final String name;
        private final String relativePath;
        private final String fileName;
        private final boolean passed;
        private final int passedCount;
        private final int failedCount;
        private final int scenarioCount;
        private final double durationMillis;
        private final long startTime;
        private final String threadName;
        private final Set<String> tags;
        private final List<ScenarioSummary> scenarios;

        public FeatureSummary(FeatureResult result) {
            this.name = result.getFeature().getName();
            this.relativePath = result.getDisplayName();
            this.fileName = pathToFileName(relativePath);
            this.passed = result.isPassed();
            this.passedCount = result.getPassedCount();
            this.failedCount = result.getFailedCount();
            this.scenarioCount = result.getScenarioResults().size();
            this.durationMillis = result.getDurationMillis();
            this.startTime = result.getStartTime();

            // Collect thread name from first scenario and scenario summaries for timeline
            String thread = null;
            this.tags = new HashSet<>();
            this.scenarios = new ArrayList<>();
            // Get just the filename without extension for timeline display
            String featureFileName = result.getFeature().getResource().getFileNameWithoutExtension();
            // Extract just the filename from any path prefix
            int lastSlash = featureFileName.lastIndexOf('/');
            if (lastSlash >= 0) {
                featureFileName = featureFileName.substring(lastSlash + 1);
            }
            lastSlash = featureFileName.lastIndexOf('\\');
            if (lastSlash >= 0) {
                featureFileName = featureFileName.substring(lastSlash + 1);
            }
            for (ScenarioResult sr : result.getScenarioResults()) {
                if (thread == null && sr.getThreadName() != null) {
                    thread = sr.getThreadName();
                }
                var scenarioTags = sr.getScenario().getTags();
                if (scenarioTags != null) {
                    for (var tag : scenarioTags) {
                        tags.add(tag.toString());
                    }
                }
                // Collect scenario summary for timeline
                scenarios.add(new ScenarioSummary(sr, featureFileName));
            }
            this.threadName = thread;
        }

        public String getName() {
            return name;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getFileName() {
            return fileName;
        }

        public boolean isPassed() {
            return passed;
        }

        public int getPassedCount() {
            return passedCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public int getScenarioCount() {
            return scenarioCount;
        }

        public double getDurationMillis() {
            return durationMillis;
        }

        public long getStartTime() {
            return startTime;
        }

        public String getThreadName() {
            return threadName;
        }

        public Set<String> getTags() {
            return tags;
        }

        public List<ScenarioSummary> getScenarios() {
            return scenarios;
        }

        /**
         * Convert a relative path to a file name using dot-based flattening.
         * Example: "users/list.feature" → "users.list"
         */
        private static String pathToFileName(String path) {
            if (path == null || path.isEmpty()) {
                return "unknown";
            }
            // Remove .feature extension and replace path separators with dots
            return path.replace(".feature", "")
                    .replace("/", ".")
                    .replace("\\", ".")
                    .replaceAll("[^a-zA-Z0-9_.-]", "_")
                    .toLowerCase();
        }
    }

}
