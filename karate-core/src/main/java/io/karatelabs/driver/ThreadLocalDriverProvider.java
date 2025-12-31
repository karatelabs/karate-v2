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
package io.karatelabs.driver;

import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.driver.cdp.CdpDriver;
import io.karatelabs.driver.cdp.CdpDriverOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver provider that maintains one driver per thread.
 * <p>
 * Drivers are reused across scenarios within the same thread and
 * reset between scenarios (navigate to about:blank, clear cookies).
 * <p>
 * This is efficient for parallel test execution where each thread
 * runs multiple scenarios sequentially.
 */
public class ThreadLocalDriverProvider implements DriverProvider {

    private static final Logger logger = LoggerFactory.getLogger(ThreadLocalDriverProvider.class);

    private final ThreadLocal<Driver> threadDriver = new ThreadLocal<>();
    private final Map<Long, Driver> allDrivers = new ConcurrentHashMap<>();

    @Override
    public Driver acquire(ScenarioRuntime runtime, Map<String, Object> config) {
        Driver driver = threadDriver.get();
        if (driver != null) {
            // Reuse existing driver, reset state
            resetDriver(driver);
            logger.debug("Reusing thread-local driver for scenario: {}",
                    runtime.getScenario().getName());
            return driver;
        }

        // Create new driver for this thread
        driver = createDriver(config);
        threadDriver.set(driver);
        allDrivers.put(Thread.currentThread().threadId(), driver);
        logger.info("Created new driver for thread: {}, scenario: {}",
                Thread.currentThread().getName(), runtime.getScenario().getName());
        return driver;
    }

    @Override
    public void release(ScenarioRuntime runtime, Driver driver) {
        // Don't close - keep for next scenario in this thread
        logger.debug("Released driver for scenario: {}", runtime.getScenario().getName());
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down ThreadLocalDriverProvider, closing {} drivers", allDrivers.size());
        for (Driver driver : allDrivers.values()) {
            try {
                driver.quit();
            } catch (Exception e) {
                logger.warn("Error closing driver: {}", e.getMessage());
            }
        }
        allDrivers.clear();
        // Note: ThreadLocal cleanup happens when threads terminate
    }

    /**
     * Reset driver state between scenarios.
     */
    protected void resetDriver(Driver driver) {
        try {
            driver.setUrl("about:blank");
            driver.clearCookies();
        } catch (Exception e) {
            logger.warn("Error resetting driver state: {}", e.getMessage());
        }
    }

    /**
     * Create a new driver from config.
     * Subclasses can override for custom driver creation.
     */
    protected Driver createDriver(Map<String, Object> config) {
        CdpDriverOptions options = CdpDriverOptions.fromMap(config);
        String wsUrl = options.getWebSocketUrl();
        if (wsUrl != null && !wsUrl.isEmpty()) {
            return CdpDriver.connect(wsUrl, options);
        } else {
            return CdpDriver.start(options);
        }
    }

}
