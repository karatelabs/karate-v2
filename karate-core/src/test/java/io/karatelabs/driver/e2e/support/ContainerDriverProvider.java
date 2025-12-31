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

import io.karatelabs.driver.Driver;
import io.karatelabs.driver.ThreadLocalDriverProvider;
import io.karatelabs.driver.cdp.CdpDriver;
import io.karatelabs.driver.cdp.CdpDriverOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Driver provider for Testcontainers Chrome that creates a new tab per thread.
 * This enables parallel execution within a single Chrome container.
 */
public class ContainerDriverProvider extends ThreadLocalDriverProvider {

    private static final Logger logger = LoggerFactory.getLogger(ContainerDriverProvider.class);

    private final ChromeContainer container;
    private final Object lock = new Object();

    public ContainerDriverProvider(ChromeContainer container) {
        this.container = container;
    }

    @Override
    protected Driver createDriver(Map<String, Object> config) {
        // Synchronize tab creation to avoid overwhelming the container
        String wsUrl;
        synchronized (lock) {
            wsUrl = container.getCdpUrl();
            logger.info("Created new tab in container for thread {}: {}",
                    Thread.currentThread().getName(), wsUrl);
        }
        CdpDriverOptions options = CdpDriverOptions.fromMap(config);
        return CdpDriver.connect(wsUrl, options);
    }

}
