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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;

/**
 * Singleton container holder for sharing a single Chrome container across all E2E tests.
 * This significantly speeds up test execution by avoiding container start/stop per test class.
 *
 * Usage:
 * - Call SharedChromeContainer.getInstance() to get the shared container
 * - Container is started lazily on first access
 * - Container is stopped automatically via JVM shutdown hook
 */
public class SharedChromeContainer {

    private static final Logger logger = LoggerFactory.getLogger(SharedChromeContainer.class);

    // Fixed port for test server - must be exposed before container starts
    public static final int TEST_SERVER_PORT = 18080;

    private static volatile SharedChromeContainer instance;
    private static final Object lock = new Object();

    private final ChromeContainer chrome;
    private final TestPageServer testServer;

    private SharedChromeContainer() {
        // Workaround for Docker 29.x compatibility
        System.setProperty("api.version", "1.44");

        // Expose the test server port to containers BEFORE container starts
        Testcontainers.exposeHostPorts(TEST_SERVER_PORT);
        logger.info("exposed host port {} to containers", TEST_SERVER_PORT);

        // Start test server
        testServer = TestPageServer.start(TEST_SERVER_PORT);
        logger.info("shared test page server started on port: {}", testServer.getPort());

        // Start Chrome container
        chrome = new ChromeContainer();
        chrome.start();
        logger.info("shared Chrome container started: {}", chrome.getCdpUrl());

        // Register shutdown hook to clean up
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("shutting down shared Chrome container...");
            try {
                chrome.stop();
            } catch (Exception e) {
                logger.warn("error stopping Chrome container: {}", e.getMessage());
            }
            try {
                testServer.stopAsync();
            } catch (Exception e) {
                logger.warn("error stopping test server: {}", e.getMessage());
            }
        }));
    }

    public static SharedChromeContainer getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SharedChromeContainer();
                }
            }
        }
        return instance;
    }

    public ChromeContainer getChrome() {
        return chrome;
    }

    public TestPageServer getTestServer() {
        return testServer;
    }

    public String getCdpUrl() {
        return chrome.getCdpUrl();
    }

    public String getHostAccessUrl() {
        return chrome.getHostAccessUrl(TEST_SERVER_PORT);
    }

}
