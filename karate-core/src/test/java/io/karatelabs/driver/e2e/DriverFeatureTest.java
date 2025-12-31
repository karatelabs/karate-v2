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
package io.karatelabs.driver.e2e;

import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import io.karatelabs.driver.e2e.support.ChromeContainer;
import io.karatelabs.driver.e2e.support.ContainerDriverProvider;
import io.karatelabs.driver.e2e.support.TestPageServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.junit.jupiter.Container;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs Gherkin feature files for driver E2E testing.
 * Uses Testcontainers for Docker-based Chrome and reuses existing test infrastructure.
 */
@org.testcontainers.junit.jupiter.Testcontainers
class DriverFeatureTest {

    private static final Logger logger = LoggerFactory.getLogger(DriverFeatureTest.class);

    // Test server port - use fixed port for exposeHostPorts which must be called before container starts
    private static final int TEST_SERVER_PORT = 18081;

    // Static initialization block runs before @Container field initialization
    static {
        // Workaround for Docker 29.x compatibility
        System.setProperty("api.version", "1.44");

        // Expose the test server port to containers BEFORE container starts
        Testcontainers.exposeHostPorts(TEST_SERVER_PORT);
        logger.info("exposed host port {} to containers for Gherkin tests", TEST_SERVER_PORT);
    }

    @Container
    private static final ChromeContainer chrome = new ChromeContainer();

    private static TestPageServer testServer;

    @BeforeAll
    static void setup() {
        // Start test page server
        testServer = TestPageServer.start(TEST_SERVER_PORT);
        logger.info("test page server started on port: {}", testServer.getPort());

        // Set serverUrl for test pages (webSocketUrl not needed - ContainerDriverProvider handles it)
        String serverUrl = chrome.getHostAccessUrl(TEST_SERVER_PORT);
        System.setProperty("karate.driver.serverUrl", serverUrl);
        logger.info("driver serverUrl: {}", serverUrl);
    }

    @AfterAll
    static void cleanup() {
        if (testServer != null) {
            testServer.stopAsync();
            testServer = null;
        }
        System.clearProperty("karate.driver.serverUrl");
    }

    @Test
    void testDriverFeatures() {
        // ContainerDriverProvider creates tabs in the Chrome container
        // Pool size is auto-detected from parallel(N) - no need to specify it
        ContainerDriverProvider provider = new ContainerDriverProvider(chrome);

        SuiteResult result = Runner.path("classpath:io/karatelabs/driver/features")
                .configDir("classpath:io/karatelabs/driver/features/karate-config.js")
                .outputDir(Path.of("target", "driver-feature-reports"))
                .outputHtmlReport(true)
                .outputConsoleSummary(true)
                .driverProvider(provider)
                .parallel(2);  // Pool size auto-detected from this

        // Log results
        logger.info("Feature count: {}", result.getFeatureCount());
        logger.info("Scenarios passed: {}", result.getScenarioPassedCount());
        logger.info("Scenarios failed: {}", result.getScenarioFailedCount());

        if (result.isFailed()) {
            result.getErrors().forEach(error ->
                logger.error("Test error: {}", error)
            );
        }

        assertTrue(result.isPassed(), "All driver feature tests should pass");
    }

}
