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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SuiteResult {

    private final List<FeatureResult> featureResults = Collections.synchronizedList(new ArrayList<>());
    private long startTime;
    private long endTime;

    public SuiteResult() {
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

    public synchronized void addFeatureResult(FeatureResult fr) {
        featureResults.add(fr);
    }

    public List<FeatureResult> getFeatureResults() {
        return featureResults;
    }

    // ========== Aggregation ==========

    public int getFeatureCount() {
        return featureResults.size();
    }

    public int getFeaturePassedCount() {
        return (int) featureResults.stream().filter(FeatureResult::isPassed).count();
    }

    public int getFeatureFailedCount() {
        return (int) featureResults.stream().filter(FeatureResult::isFailed).count();
    }

    public int getScenarioCount() {
        return featureResults.stream().mapToInt(FeatureResult::getScenarioCount).sum();
    }

    public int getScenarioPassedCount() {
        return featureResults.stream().mapToInt(FeatureResult::getPassedCount).sum();
    }

    public int getScenarioFailedCount() {
        return featureResults.stream().mapToInt(FeatureResult::getFailedCount).sum();
    }

    public boolean isPassed() {
        return featureResults.stream().noneMatch(FeatureResult::isFailed);
    }

    public boolean isFailed() {
        return featureResults.stream().anyMatch(FeatureResult::isFailed);
    }

    public long getDurationMillis() {
        return endTime - startTime;
    }

    // ========== Serialization ==========

    public Map<String, Object> toKarateJson() {
        Map<String, Object> map = new LinkedHashMap<>();

        // Features
        List<Map<String, Object>> features = new ArrayList<>();
        for (FeatureResult fr : featureResults) {
            features.add(fr.toKarateJson());
        }
        map.put("features", features);

        // Summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("feature_count", getFeatureCount());
        summary.put("feature_passed", getFeaturePassedCount());
        summary.put("feature_failed", getFeatureFailedCount());
        summary.put("scenario_count", getScenarioCount());
        summary.put("scenario_passed", getScenarioPassedCount());
        summary.put("scenario_failed", getScenarioFailedCount());
        summary.put("duration_millis", getDurationMillis());
        summary.put("status", isFailed() ? "failed" : "passed");
        map.put("summary", summary);

        return map;
    }

    public String toJson() {
        return io.karatelabs.common.Json.stringifyStrict(toKarateJson());
    }

    public String toJsonPretty() {
        return io.karatelabs.common.Json.of(toKarateJson()).toStringPretty();
    }

}
