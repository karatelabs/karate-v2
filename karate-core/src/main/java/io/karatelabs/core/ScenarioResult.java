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

import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScenarioResult implements Comparable<ScenarioResult> {

    private final Scenario scenario;
    private final List<StepResult> stepResults = new ArrayList<>();
    private long startTime;
    private long endTime;
    private String threadName;

    public ScenarioResult(Scenario scenario) {
        this.scenario = scenario;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public Scenario getScenario() {
        return scenario;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public void addStepResult(StepResult sr) {
        stepResults.add(sr);
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public String getThreadName() {
        return threadName;
    }

    public boolean isPassed() {
        return stepResults.stream().noneMatch(StepResult::isFailed);
    }

    public boolean isFailed() {
        return stepResults.stream().anyMatch(StepResult::isFailed);
    }

    public long getDurationMillis() {
        return endTime - startTime;
    }

    public long getDurationNanos() {
        return stepResults.stream()
                .mapToLong(StepResult::getDurationNanos)
                .sum();
    }

    public String getFailureMessage() {
        return stepResults.stream()
                .filter(StepResult::isFailed)
                .findFirst()
                .map(StepResult::getErrorMessage)
                .orElse(null);
    }

    public Throwable getError() {
        return stepResults.stream()
                .filter(StepResult::isFailed)
                .findFirst()
                .map(StepResult::getError)
                .orElse(null);
    }

    public int getPassedCount() {
        return (int) stepResults.stream().filter(StepResult::isPassed).count();
    }

    public int getFailedCount() {
        return (int) stepResults.stream().filter(StepResult::isFailed).count();
    }

    public int getSkippedCount() {
        return (int) stepResults.stream().filter(StepResult::isSkipped).count();
    }

    public Map<String, Object> toKarateJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("line", scenario.getLine());
        map.put("id", scenario.getUniqueId());
        map.put("name", scenario.getName());
        map.put("description", scenario.getDescription());

        List<Tag> tags = scenario.getTags();
        if (tags != null && !tags.isEmpty()) {
            List<Map<String, Object>> tagList = new ArrayList<>();
            for (Tag tag : tags) {
                Map<String, Object> tagMap = new LinkedHashMap<>();
                tagMap.put("name", tag.toString());
                tagMap.put("line", scenario.getLine());
                tagList.add(tagMap);
            }
            map.put("tags", tagList);
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepResult sr : stepResults) {
            steps.add(sr.toKarateJson());
        }
        map.put("steps", steps);

        // Summary
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", isFailed() ? "failed" : "passed");
        result.put("duration_millis", getDurationMillis());
        result.put("duration_nanos", getDurationNanos());
        if (threadName != null) {
            result.put("thread_name", threadName);
        }
        if (isFailed()) {
            result.put("error_message", getFailureMessage());
        }
        map.put("result", result);

        return map;
    }

    @Override
    public int compareTo(ScenarioResult other) {
        if (other == null) {
            return 1;
        }
        // Compare by section index first
        int sectionCmp = Integer.compare(
                this.scenario.getSection().getIndex(),
                other.scenario.getSection().getIndex()
        );
        if (sectionCmp != 0) {
            return sectionCmp;
        }
        // Then by example index (-1 means not an outline example)
        int exampleCmp = Integer.compare(
                this.scenario.getExampleIndex(),
                other.scenario.getExampleIndex()
        );
        if (exampleCmp != 0) {
            return exampleCmp;
        }
        // Finally by line number
        return Integer.compare(
                this.scenario.getLine(),
                other.scenario.getLine()
        );
    }

}
