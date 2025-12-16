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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link ResultListener} that streams test results to an NDJSON (newline-delimited JSON) file.
 * <p>
 * This listener writes feature results as they complete during test execution, enabling:
 * <ul>
 *   <li>Memory efficiency - results written to disk immediately, not held in memory</li>
 *   <li>Streaming - can tail the file during execution for live progress</li>
 *   <li>Easy aggregation - multiple NDJSON files can be concatenated</li>
 * </ul>
 * <p>
 * NDJSON format:
 * <pre>
 * {"t":"suite","time":"2025-12-16T10:30:00Z","threads":5,"env":"dev","version":"2.0.0"}
 * {"t":"feature","path":"features/users.feature","name":"User Management","scenarios":[...],"passed":true,"ms":1234}
 * {"t":"suite_end","featuresPassed":10,"featuresFailed":2,"scenariosPassed":42,"scenariosFailed":3,"ms":12345}
 * </pre>
 * <p>
 * At suite end, this listener generates HTML reports from the accumulated NDJSON data.
 */
public class NdjsonReportListener implements ResultListener {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final Path outputDir;
    private final Path ndjsonPath;
    private final String env;
    private BufferedWriter writer;
    private long suiteStartTime;
    private int threadCount;

    /**
     * Create a new NDJSON report listener.
     *
     * @param outputDir the directory to write reports
     * @param env       the karate environment (may be null)
     */
    public NdjsonReportListener(Path outputDir, String env) {
        this.outputDir = outputDir;
        this.ndjsonPath = outputDir.resolve("karate-results.ndjson");
        this.env = env;
    }

    @Override
    public void onSuiteStart(Suite suite) {
        try {
            // Create output directory
            Files.createDirectories(outputDir);

            // Open writer for NDJSON file
            writer = Files.newBufferedWriter(ndjsonPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            // Capture suite info
            suiteStartTime = System.currentTimeMillis();
            threadCount = suite.getThreadCount();

            // Write suite header line
            Map<String, Object> suiteHeader = new LinkedHashMap<>();
            suiteHeader.put("t", "suite");
            suiteHeader.put("time", ISO_FORMAT.format(Instant.ofEpochMilli(suiteStartTime).atOffset(ZoneOffset.UTC)));
            suiteHeader.put("threads", threadCount);
            if (env != null && !env.isEmpty()) {
                suiteHeader.put("env", env);
            }
            suiteHeader.put("version", "2.0.0");

            writeLine(Json.stringifyStrict(suiteHeader));

            JvmLogger.debug("NDJSON report started: {}", ndjsonPath);

        } catch (IOException e) {
            JvmLogger.warn("Failed to start NDJSON report: {}", e.getMessage());
        }
    }

    @Override
    public void onFeatureEnd(FeatureResult result) {
        if (writer == null) {
            return;
        }

        try {
            Map<String, Object> featureLine = buildFeatureLine(result);
            writeLine(Json.stringifyStrict(featureLine));
        } catch (Exception e) {
            JvmLogger.warn("Failed to write feature to NDJSON: {}", e.getMessage());
        }
    }

    @Override
    public void onSuiteEnd(SuiteResult result) {
        if (writer == null) {
            return;
        }

        try {
            // Write suite_end line
            Map<String, Object> suiteEnd = new LinkedHashMap<>();
            suiteEnd.put("t", "suite_end");
            suiteEnd.put("featuresPassed", result.getFeaturePassedCount());
            suiteEnd.put("featuresFailed", result.getFeatureFailedCount());
            suiteEnd.put("scenariosPassed", result.getScenarioPassedCount());
            suiteEnd.put("scenariosFailed", result.getScenarioFailedCount());
            suiteEnd.put("ms", result.getDurationMillis());

            writeLine(Json.stringifyStrict(suiteEnd));

            // Close writer
            writer.close();
            writer = null;

            JvmLogger.info("NDJSON report written to: {}", ndjsonPath);

            // Generate HTML reports from NDJSON
            HtmlReportWriter.writeFromNdjson(ndjsonPath, outputDir);

        } catch (IOException e) {
            JvmLogger.warn("Failed to complete NDJSON report: {}", e.getMessage());
        }
    }

    /**
     * Build the NDJSON line for a feature result.
     */
    private Map<String, Object> buildFeatureLine(FeatureResult result) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "feature");
        line.put("path", result.getDisplayName());
        line.put("name", result.getFeature().getName());

        // Build scenarios array
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (ScenarioResult sr : result.getScenarioResults()) {
            scenarios.add(buildScenarioData(sr));
        }
        line.put("scenarios", scenarios);

        line.put("passed", result.isPassed());
        line.put("ms", result.getDurationMillis());

        return line;
    }

    /**
     * Build scenario data for NDJSON.
     */
    private Map<String, Object> buildScenarioData(ScenarioResult sr) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("name", sr.getScenario().getName());
        scenario.put("line", sr.getScenario().getLine());

        // Tags
        var tags = sr.getScenario().getTags();
        if (tags != null && !tags.isEmpty()) {
            List<String> tagNames = new ArrayList<>();
            for (var tag : tags) {
                tagNames.add(tag.toString());
            }
            scenario.put("tags", tagNames);
        }

        scenario.put("passed", sr.isPassed());
        scenario.put("ms", sr.getDurationMillis());

        if (sr.getThreadName() != null) {
            scenario.put("thread", sr.getThreadName());
        }

        // Steps
        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepResult step : sr.getStepResults()) {
            steps.add(buildStepData(step));
        }
        scenario.put("steps", steps);

        // Error info if failed
        if (sr.isFailed() && sr.getFailureMessage() != null) {
            scenario.put("error", sr.getFailureMessage());
        }

        return scenario;
    }

    /**
     * Build step data for NDJSON.
     */
    private Map<String, Object> buildStepData(StepResult step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", step.getStep().getText());
        data.put("status", step.getStatus().name().toLowerCase());
        data.put("ms", step.getDurationNanos() / 1_000_000);

        // Include log if present
        if (step.getLog() != null && !step.getLog().isEmpty()) {
            data.put("logs", step.getLog());
        }

        // Include error if present
        if (step.getError() != null) {
            data.put("error", step.getError().getMessage());
        }

        return data;
    }

    /**
     * Thread-safe write to NDJSON file.
     */
    private synchronized void writeLine(String json) throws IOException {
        if (writer != null) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    /**
     * Get the path to the NDJSON file.
     */
    public Path getNdjsonPath() {
        return ndjsonPath;
    }

}
