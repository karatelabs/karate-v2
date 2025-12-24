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

import io.karatelabs.core.FeatureResult;
import io.karatelabs.core.ScenarioResult;
import io.karatelabs.core.SuiteResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates JUnit XML reports compatible with CI systems (Jenkins, GitHub Actions, etc.).
 * <p>
 * Output follows the standard JUnit XML schema:
 * <pre>
 * &lt;testsuites&gt;
 *   &lt;testsuite name="feature" tests="N" failures="N" time="secs"&gt;
 *     &lt;testcase name="scenario" classname="feature/path" time="secs"&gt;
 *       &lt;failure message="error"&gt;stacktrace&lt;/failure&gt;
 *     &lt;/testcase&gt;
 *   &lt;/testsuite&gt;
 * &lt;/testsuites&gt;
 * </pre>
 */
public final class JunitXmlWriter {

    private static final Logger logger = LoggerFactory.getLogger("karate.runtime");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault());

    private JunitXmlWriter() {
    }

    /**
     * Write JUnit XML report to the specified output directory.
     *
     * @param result    the suite result to convert
     * @param outputDir the directory to write the report
     */
    public static void write(SuiteResult result, Path outputDir) {
        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }

            Path xmlPath = outputDir.resolve("karate-junit.xml");
            String xml = toXml(result);
            Files.writeString(xmlPath, xml);
            logger.info("JUnit XML report written to: {}", xmlPath);
        } catch (Exception e) {
            logger.warn("Failed to write JUnit XML report: {}", e.getMessage());
        }
    }

    /**
     * Convert suite result to JUnit XML string.
     *
     * @param result the suite result
     * @return XML string
     */
    public static String toXml(SuiteResult result) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

        // Root testsuites element
        xml.append("<testsuites");
        xml.append(" name=\"karate\"");
        xml.append(" tests=\"").append(result.getScenarioCount()).append("\"");
        xml.append(" failures=\"").append(result.getScenarioFailedCount()).append("\"");
        xml.append(" errors=\"0\"");
        xml.append(" skipped=\"0\"");
        xml.append(" time=\"").append(formatTime(result.getDurationMillis())).append("\"");
        xml.append(" timestamp=\"").append(formatTimestamp(result.getStartTime())).append("\"");
        xml.append(">\n");

        // Each feature becomes a testsuite
        for (FeatureResult fr : result.getFeatureResults()) {
            writeTestsuite(xml, fr);
        }

        xml.append("</testsuites>\n");
        return xml.toString();
    }

    private static void writeTestsuite(StringBuilder xml, FeatureResult fr) {
        String featureName = fr.getFeature().getName();
        if (featureName == null || featureName.isEmpty()) {
            featureName = fr.getDisplayName();
        }

        String featurePath = fr.getDisplayName();

        xml.append("  <testsuite");
        xml.append(" name=\"").append(escape(featureName)).append("\"");
        xml.append(" tests=\"").append(fr.getScenarioCount()).append("\"");
        xml.append(" failures=\"").append(fr.getFailedCount()).append("\"");
        xml.append(" errors=\"0\"");
        xml.append(" skipped=\"0\"");
        xml.append(" time=\"").append(formatTime(fr.getDurationMillis())).append("\"");
        xml.append(" timestamp=\"").append(formatTimestamp(fr.getStartTime())).append("\"");
        xml.append(">\n");

        // Each scenario becomes a testcase
        for (ScenarioResult sr : fr.getScenarioResults()) {
            writeTestcase(xml, sr, featurePath);
        }

        xml.append("  </testsuite>\n");
    }

    private static void writeTestcase(StringBuilder xml, ScenarioResult sr, String featurePath) {
        String scenarioName = sr.getScenario().getName();
        if (scenarioName == null || scenarioName.isEmpty()) {
            scenarioName = "line " + sr.getScenario().getLine();
        }

        // Use feature path as classname (standard convention for JUnit)
        String classname = featurePath.replace('/', '.').replace('\\', '.');
        if (classname.endsWith(".feature")) {
            classname = classname.substring(0, classname.length() - 8);
        }

        xml.append("    <testcase");
        xml.append(" name=\"").append(escape(scenarioName)).append("\"");
        xml.append(" classname=\"").append(escape(classname)).append("\"");
        xml.append(" time=\"").append(formatTime(sr.getDurationMillis())).append("\"");

        if (sr.isFailed()) {
            xml.append(">\n");
            writeFailure(xml, sr);
            xml.append("    </testcase>\n");
        } else {
            xml.append("/>\n");
        }
    }

    private static void writeFailure(StringBuilder xml, ScenarioResult sr) {
        String message = sr.getFailureMessage();
        if (message == null) {
            message = "Unknown failure";
        }

        Throwable error = sr.getError();
        String type = error != null ? error.getClass().getName() : "AssertionError";

        xml.append("      <failure");
        xml.append(" message=\"").append(escape(truncate(message, 1000))).append("\"");
        xml.append(" type=\"").append(escape(type)).append("\"");
        xml.append(">");

        // Include stacktrace if available
        if (error != null) {
            xml.append(escape(getStackTrace(error)));
        } else {
            xml.append(escape(message));
        }

        xml.append("</failure>\n");
    }

    private static String formatTime(long millis) {
        return String.format("%.3f", millis / 1000.0);
    }

    private static String formatTimestamp(long epochMillis) {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

}
