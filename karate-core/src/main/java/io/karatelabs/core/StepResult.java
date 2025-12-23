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

import io.karatelabs.gherkin.Step;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StepResult {

    public enum Status {
        PASSED, FAILED, SKIPPED
    }

    private final Step step;
    private final Status status;
    private final long startTime;
    private final long durationNanos;
    private final Throwable error;
    private String log;
    private List<Embed> embeds;

    private StepResult(Step step, Status status, long startTime, long durationNanos, Throwable error) {
        this.step = step;
        this.status = status;
        this.startTime = startTime;
        this.durationNanos = durationNanos;
        this.error = error;
    }

    public static StepResult passed(Step step, long startTime, long durationNanos) {
        return new StepResult(step, Status.PASSED, startTime, durationNanos, null);
    }

    public static StepResult failed(Step step, long startTime, long durationNanos, Throwable error) {
        return new StepResult(step, Status.FAILED, startTime, durationNanos, error);
    }

    public static StepResult skipped(Step step, long startTime) {
        return new StepResult(step, Status.SKIPPED, startTime, 0, null);
    }

    /**
     * Create a fake success step result (for @fail tag handling).
     */
    public static StepResult fakeSuccess(String message, long startTime) {
        StepResult sr = new StepResult(null, Status.PASSED, startTime, 0, null);
        sr.log = message;
        return sr;
    }

    /**
     * Create a fake failure step result (for @fail tag handling).
     */
    public static StepResult fakeFailure(String message, long startTime, Throwable error) {
        StepResult sr = new StepResult(null, Status.FAILED, startTime, 0, error);
        sr.log = message;
        return sr;
    }

    public Step getStep() {
        return step;
    }

    public Status getStatus() {
        return status;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public double getDurationMillis() {
        return durationNanos / 1_000_000.0;
    }

    public Throwable getError() {
        return error;
    }

    public String getLog() {
        return log;
    }

    public void setLog(String log) {
        this.log = log;
    }

    public List<Embed> getEmbeds() {
        return embeds;
    }

    public void addEmbed(Embed embed) {
        if (embeds == null) {
            embeds = new ArrayList<>();
        }
        embeds.add(embed);
    }

    public boolean isPassed() {
        return status == Status.PASSED;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isSkipped() {
        return status == Status.SKIPPED;
    }

    public String getErrorMessage() {
        return error != null ? error.getMessage() : null;
    }

    /**
     * Convert to canonical Map format for NDJSON and HTML reports.
     * This is the single internal format used for all report generation.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (step != null) {
            data.put("prefix", step.getPrefix());
            data.put("keyword", step.getKeyword());
            data.put("text", step.getText());
            data.put("line", step.getLine());
        } else {
            // Fake step (e.g., for @fail tag)
            data.put("prefix", "*");
            data.put("keyword", "*");
            data.put("text", log != null ? log : "");
            data.put("line", 0);
        }
        data.put("status", status.name().toLowerCase());
        data.put("ms", durationNanos / 1_000_000);

        // Log indicator for UI
        boolean hasLogs = log != null && !log.isEmpty();
        data.put("hasLogs", hasLogs);
        if (hasLogs) {
            data.put("logs", log);
        }

        // Error
        if (error != null) {
            data.put("error", error.getMessage());
        }

        // Embeds (images, HTML, etc.)
        boolean hasEmbeds = embeds != null && !embeds.isEmpty();
        data.put("hasEmbeds", hasEmbeds);
        if (hasEmbeds) {
            List<Map<String, Object>> embedList = new ArrayList<>();
            for (Embed embed : embeds) {
                embedList.add(embed.toMap());
            }
            data.put("embeds", embedList);
        }

        return data;
    }

    public Map<String, Object> toKarateJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (step != null) {
            map.put("line", step.getLine());
            map.put("keyword", step.getKeyword());
            map.put("name", step.getText());
        } else {
            // Fake step (e.g., for @fail tag)
            map.put("line", 0);
            map.put("keyword", "*");
            map.put("name", log != null ? log : "");
        }
        map.put("result", resultToMap());
        if (step != null && step.getDocString() != null) {
            map.put("doc_string", step.getDocString());
        }
        if (step != null && step.getTable() != null) {
            map.put("table", step.getTable().toKarateJson());
        }
        if (log != null && !log.isEmpty()) {
            map.put("log", log);
        }
        if (embeds != null && !embeds.isEmpty()) {
            List<Map<String, Object>> embedList = new ArrayList<>();
            for (Embed embed : embeds) {
                embedList.add(embed.toMap());
            }
            map.put("embeds", embedList);
        }
        return map;
    }

    private Map<String, Object> resultToMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status.name().toLowerCase());
        map.put("duration_nanos", durationNanos);
        if (error != null) {
            map.put("error_message", error.getMessage());
        }
        return map;
    }

    public static class Embed {
        private final byte[] data;
        private final String mimeType;
        private final String name;
        private String fileName;  // Set by HtmlReportWriter when writing to file

        public Embed(byte[] data, String mimeType, String name) {
            this.data = data;
            this.mimeType = mimeType;
            this.name = name;
        }

        public byte[] getData() {
            return data;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getName() {
            return name;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileName() {
            return fileName;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mime_type", mimeType);
            if (fileName != null) {
                map.put("file", fileName);  // File reference for HTML reports
            } else {
                map.put("data", java.util.Base64.getEncoder().encodeToString(data));  // Inline for other formats
            }
            if (name != null) {
                map.put("name", name);
            }
            return map;
        }
    }

}
