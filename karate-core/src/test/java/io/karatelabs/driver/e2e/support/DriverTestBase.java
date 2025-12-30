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
package io.karatelabs.driver.e2e.support;

import io.karatelabs.driver.CdpDriver;
import io.karatelabs.driver.CdpDriverOptions;
import io.karatelabs.driver.PageLoadStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.junit.jupiter.Container;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base class for driver E2E tests.
 * Provides Docker-based Chrome via Testcontainers and a test page server.
 */
@org.testcontainers.junit.jupiter.Testcontainers
public abstract class DriverTestBase {

    protected static final Logger logger = LoggerFactory.getLogger(DriverTestBase.class);

    // Test server port - use fixed port for exposeHostPorts which must be called before container starts
    private static final int TEST_SERVER_PORT = 18080;

    // Static initialization block runs before @Container field initialization
    static {
        // Workaround for Docker 29.x compatibility
        // See: https://github.com/testcontainers/testcontainers-java/issues/11212
        System.setProperty("api.version", "1.44");

        // Expose the test server port to containers BEFORE container starts
        Testcontainers.exposeHostPorts(TEST_SERVER_PORT);
        logger.info("exposed host port {} to containers", TEST_SERVER_PORT);
    }

    @Container
    protected static final ChromeContainer chrome = new ChromeContainer();

    protected static TestPageServer testServer;
    protected static CdpDriver driver;

    @BeforeAll
    static void startTestServer() {
        testServer = TestPageServer.start(TEST_SERVER_PORT);
        logger.info("test page server started on port: {}", testServer.getPort());
    }

    @BeforeAll
    static void createDriver() {
        driver = chrome.createDriver(
                CdpDriverOptions.builder()
                        .timeout(30000)
                        .pageLoadStrategy(PageLoadStrategy.DOMCONTENT_AND_FRAMES)
                        .build()
        );
        logger.info("CDP driver connected to Chrome container");
    }

    @AfterAll
    static void cleanup() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
        if (testServer != null) {
            testServer.stopAsync();
            testServer = null;
        }
    }

    /**
     * Get URL for a test page accessible from inside the Docker container.
     * Use this for driver.setUrl() calls.
     */
    protected String testUrl(String path) {
        // Use host.testcontainers.internal to access host services from container
        return chrome.getHostAccessUrl(TEST_SERVER_PORT) + path;
    }

    /**
     * Get URL for a test page accessible from the host.
     * Use this for debugging or local access.
     */
    protected String localUrl(String path) {
        return testServer.getBaseUrl() + path;
    }

    /**
     * Save a debug screenshot to the specified path.
     */
    protected void saveScreenshot(String filename) {
        try {
            byte[] screenshot = driver.screenshot();
            Path path = Path.of("target", "screenshots", filename);
            Files.createDirectories(path.getParent());
            Files.write(path, screenshot);
            logger.info("screenshot saved: {}", path.toAbsolutePath());
        } catch (Exception e) {
            logger.warn("failed to save screenshot: {}", e.getMessage());
        }
    }

    /**
     * Print debug information about the current page state.
     */
    protected void debugSnapshot() {
        logger.info("=== Debug Snapshot ===");
        logger.info("URL: {}", driver.getUrl());
        logger.info("Title: {}", driver.getTitle());
        saveScreenshot("debug-" + System.currentTimeMillis() + ".png");
    }

}
