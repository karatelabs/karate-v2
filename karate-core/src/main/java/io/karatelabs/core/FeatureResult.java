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

import io.karatelabs.gherkin.Feature;
import io.karatelabs.output.Console;
import io.karatelabs.gherkin.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FeatureResult {

    private final Feature feature;
    private final List<ScenarioResult> scenarioResults = Collections.synchronizedList(new ArrayList<>());
    private int callDepth;
    private Object callArg;
    private Map<String, Object> resultVariables;
    private long startTime;
    private long endTime;

    public FeatureResult(Feature feature) {
        this.feature = feature;
    }

    public Feature getFeature() {
        return feature;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public int getCallDepth() {
        return callDepth;
    }

    public void setCallDepth(int callDepth) {
        this.callDepth = callDepth;
    }

    public Object getCallArg() {
        return callArg;
    }

    public void setCallArg(Object callArg) {
        this.callArg = callArg;
    }

    public Map<String, Object> getResultVariables() {
        return resultVariables;
    }

    public void setResultVariables(Map<String, Object> resultVariables) {
        this.resultVariables = resultVariables;
    }

    public synchronized void addScenarioResult(ScenarioResult sr) {
        scenarioResults.add(sr);
    }

    public List<ScenarioResult> getScenarioResults() {
        return scenarioResults;
    }

    /**
     * Sort scenario results by section index, example index, and line number.
     * This ensures deterministic ordering in reports regardless of parallel execution order.
     */
    public void sortScenarioResults() {
        synchronized (scenarioResults) {
            Collections.sort(scenarioResults);
        }
    }

    public int getScenarioCount() {
        return scenarioResults.size();
    }

    public int getPassedCount() {
        return (int) scenarioResults.stream().filter(ScenarioResult::isPassed).count();
    }

    public int getFailedCount() {
        return (int) scenarioResults.stream().filter(ScenarioResult::isFailed).count();
    }

    public boolean isPassed() {
        return scenarioResults.stream().noneMatch(ScenarioResult::isFailed);
    }

    public boolean isFailed() {
        return scenarioResults.stream().anyMatch(ScenarioResult::isFailed);
    }

    public boolean isEmpty() {
        return scenarioResults.isEmpty();
    }

    public long getDurationMillis() {
        return endTime - startTime;
    }

    public String getDisplayName() {
        return feature.getResource().getRelativePath();
    }

    public String getFailureMessage() {
        return scenarioResults.stream()
                .filter(ScenarioResult::isFailed)
                .findFirst()
                .map(ScenarioResult::getFailureMessage)
                .orElse(null);
    }

    // ========== Canonical Map Format ==========

    /**
     * Convert to canonical Map format for JSON Lines and HTML reports.
     * This is the single internal format used for all report generation.
     * <p>
     * Format:
     * <pre>
     * {
     *   "path": "target/test-classes/features/users.feature",
     *   "name": "User Management",
     *   "passed": true,
     *   "ms": 1234,
     *   "startTime": 1703347200000,
     *   "scenarios": [...]
     * }
     * </pre>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", getDisplayName());
        data.put("name", feature.getName());
        data.put("passed", isPassed());
        data.put("ms", getDurationMillis());
        data.put("startTime", startTime);

        // Scenarios
        List<Map<String, Object>> scenarioList = new ArrayList<>();
        for (ScenarioResult sr : scenarioResults) {
            scenarioList.add(sr.toMap());
        }
        data.put("scenarios", scenarioList);

        return data;
    }

    // ========== Console Output ==========

    /**
     * Print a summary of this feature's results to the console.
     */
    public void printSummary() {
        String path = getDisplayName();
        int passed = getPassedCount();
        int failed = getFailedCount();
        int total = getScenarioCount();
        double secs = getDurationMillis() / 1000.0;

        String status = failed > 0
                ? Console.fail(failed + " failed")
                : Console.pass("passed");

        String featureLine = failed > 0
                ? Console.red(path)
                : Console.green(path);

        Console.println(Console.line(57));
        Console.println("feature: " + featureLine);
        Console.println(String.format("scenarios: %2d | passed: %2d | %s | time: %.4f",
                total, passed, status, secs));
        Console.println(Console.line(57));
    }

    public Map<String, Object> toKarateJson() {
        Map<String, Object> map = new LinkedHashMap<>();

        // Feature metadata
        map.put("line", feature.getLine());
        map.put("id", feature.getResource().getRelativePath().replace('/', '_').replace('.', '_'));
        map.put("name", feature.getName());
        map.put("description", feature.getDescription());
        map.put("uri", feature.getResource().getRelativePath());

        // Tags
        List<Tag> tags = feature.getTags();
        if (tags != null && !tags.isEmpty()) {
            List<Map<String, Object>> tagList = new ArrayList<>();
            for (Tag tag : tags) {
                Map<String, Object> tagMap = new LinkedHashMap<>();
                tagMap.put("name", tag.toString());
                tagMap.put("line", feature.getLine());
                tagList.add(tagMap);
            }
            map.put("tags", tagList);
        }

        // Scenarios
        List<Map<String, Object>> elements = new ArrayList<>();
        for (ScenarioResult sr : scenarioResults) {
            elements.add(sr.toKarateJson());
        }
        map.put("elements", elements);

        // Summary
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", isFailed() ? "failed" : "passed");
        result.put("duration_millis", getDurationMillis());
        result.put("scenario_count", getScenarioCount());
        result.put("passed_count", getPassedCount());
        result.put("failed_count", getFailedCount());
        map.put("result", result);

        return map;
    }

}
