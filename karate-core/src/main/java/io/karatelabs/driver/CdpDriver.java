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

import io.karatelabs.output.LogContext;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal CDP-based browser driver.
 * Phase 2: start, connect, quit, setUrl, getUrl, getTitle, script, screenshot.
 */
public class CdpDriver {

    private static final Logger logger = LogContext.RUNTIME_LOGGER;

    // Track active drivers for cleanup
    private static final Set<CdpDriver> ACTIVE = ConcurrentHashMap.newKeySet();

    private final CdpClient cdp;
    private final CdpDriverOptions options;
    private final BrowserLauncher launcher; // null if connected to existing browser

    // Page load state
    private volatile boolean domContentEventFired;
    private final Set<String> framesStillLoading = ConcurrentHashMap.newKeySet();
    private String mainFrameId;

    // Lifecycle
    private volatile boolean terminated = false;

    private CdpDriver(BrowserLauncher launcher, CdpDriverOptions options) {
        this.launcher = launcher;
        this.options = options;
        this.cdp = CdpClient.connect(launcher.getWebSocketUrl(), options.getTimeoutDuration());
        ACTIVE.add(this);
        initialize();
    }

    private CdpDriver(String webSocketUrl, CdpDriverOptions options) {
        this.launcher = null;
        this.options = options;
        this.cdp = CdpClient.connect(webSocketUrl, options.getTimeoutDuration());
        ACTIVE.add(this);
        initialize();
    }

    /**
     * Launch a new browser and create driver.
     */
    public static CdpDriver start(CdpDriverOptions options) {
        BrowserLauncher launcher = BrowserLauncher.start(options);
        return new CdpDriver(launcher, options);
    }

    /**
     * Launch a new browser with default options.
     */
    public static CdpDriver start() {
        return start(CdpDriverOptions.builder().build());
    }

    /**
     * Launch a headless browser.
     */
    public static CdpDriver startHeadless() {
        return start(CdpDriverOptions.builder().headless(true).build());
    }

    /**
     * Connect to an existing browser via WebSocket URL.
     */
    public static CdpDriver connect(String webSocketUrl) {
        return connect(webSocketUrl, CdpDriverOptions.builder().build());
    }

    /**
     * Connect to an existing browser with options.
     */
    public static CdpDriver connect(String webSocketUrl, CdpDriverOptions options) {
        return new CdpDriver(webSocketUrl, options);
    }

    /**
     * Close all active drivers.
     */
    public static void closeAll() {
        ACTIVE.forEach(CdpDriver::quit);
    }

    private void initialize() {
        logger.debug("initializing CDP driver");

        // Enable required domains
        cdp.method("Page.enable").send();
        cdp.method("Runtime.enable").send();

        // Setup event handlers
        setupEventHandlers();

        // Get main frame ID
        CdpResponse response = cdp.method("Page.getFrameTree").send();
        mainFrameId = response.getResult("frameTree.frame.id");
        logger.debug("main frame ID: {}", mainFrameId);
    }

    private void setupEventHandlers() {
        cdp.on("Page.domContentEventFired", event -> {
            domContentEventFired = true;
            logger.trace("domContentEventFired");
        });

        cdp.on("Page.frameStartedLoading", event -> {
            String frameId = event.get("frameId");
            if (frameId != null && frameId.equals(mainFrameId)) {
                domContentEventFired = false;
                framesStillLoading.clear();
            } else if (frameId != null) {
                framesStillLoading.add(frameId);
            }
            logger.trace("frameStartedLoading: {}", frameId);
        });

        cdp.on("Page.frameStoppedLoading", event -> {
            String frameId = event.get("frameId");
            if (frameId != null) {
                framesStillLoading.remove(frameId);
            }
            logger.trace("frameStoppedLoading: {}", frameId);
        });
    }

    // ========== Navigation ==========

    /**
     * Navigate to URL and wait for page load.
     */
    public void setUrl(String url) {
        logger.debug("navigating to: {}", url);

        // Reset page state
        domContentEventFired = false;
        framesStillLoading.clear();

        // Navigate
        cdp.method("Page.navigate")
                .param("url", url)
                .send();

        // Wait for page load based on strategy
        waitForPageLoad(options.getPageLoadStrategy());
    }

    /**
     * Wait for page to load based on strategy.
     */
    public void waitForPageLoad(PageLoadStrategy strategy) {
        waitForPageLoad(strategy, options.getTimeoutDuration());
    }

    /**
     * Wait for page to load with custom timeout.
     */
    public void waitForPageLoad(PageLoadStrategy strategy, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = 50;

        while (System.currentTimeMillis() < deadline) {
            if (isPageLoadComplete(strategy)) {
                return;
            }
            sleep(pollInterval);
        }

        throw new RuntimeException("page load timeout after " + timeout.toMillis() + "ms");
    }

    private boolean isPageLoadComplete(PageLoadStrategy strategy) {
        return switch (strategy) {
            case DOMCONTENT -> domContentEventFired;
            case DOMCONTENT_AND_FRAMES -> domContentEventFired && framesStillLoading.isEmpty();
            case LOAD, NETWORKIDLE -> {
                // For now, use DOMCONTENT_AND_FRAMES behavior
                // Full LOAD and NETWORKIDLE implementation will be added later
                yield domContentEventFired && framesStillLoading.isEmpty();
            }
        };
    }

    /**
     * Get current URL.
     */
    public String getUrl() {
        CdpResponse response = eval("window.location.href");
        return response.getResultAsString("result.value");
    }

    /**
     * Get page title.
     */
    public String getTitle() {
        CdpResponse response = eval("document.title");
        return response.getResultAsString("result.value");
    }

    // ========== JavaScript Evaluation ==========

    /**
     * Execute JavaScript and return result.
     */
    public Object script(String expression) {
        CdpResponse response = eval(expression);
        return extractJsValue(response);
    }

    private CdpResponse eval(String expression) {
        return cdp.method("Runtime.evaluate")
                .param("expression", expression)
                .param("returnByValue", true)
                .send();
    }

    private Object extractJsValue(CdpResponse response) {
        if (response.isError()) {
            throw new RuntimeException("JS error: " + response.getError());
        }
        Object exceptionDetails = response.getResult("exceptionDetails");
        if (exceptionDetails != null) {
            String text = response.getResultAsString("exceptionDetails.text");
            throw new RuntimeException("JS exception: " + text);
        }
        return response.getResult("result.value");
    }

    // ========== Screenshot ==========

    /**
     * Take screenshot and return PNG bytes.
     */
    public byte[] screenshot() {
        return screenshot(false);
    }

    /**
     * Take screenshot, optionally embed in report.
     */
    public byte[] screenshot(boolean embed) {
        CdpResponse response = cdp.method("Page.captureScreenshot")
                .param("format", "png")
                .send();

        String base64 = response.getResultAsString("data");
        byte[] bytes = Base64.getDecoder().decode(base64);

        if (embed) {
            LogContext ctx = LogContext.get();
            if (ctx != null) {
                ctx.embed(bytes, "image/png", "screenshot.png");
            }
        }

        return bytes;
    }

    // ========== Lifecycle ==========

    /**
     * Close driver and browser.
     */
    public void quit() {
        if (terminated) {
            return;
        }
        terminated = true;
        ACTIVE.remove(this);

        logger.debug("quitting CDP driver");

        // Close CDP connection
        if (cdp != null) {
            try {
                cdp.close();
            } catch (Exception e) {
                logger.warn("error closing CDP: {}", e.getMessage());
            }
        }

        // Close browser if we launched it
        if (launcher != null) {
            launcher.closeAndWait();
        }
    }

    /**
     * Alias for quit().
     */
    public void close() {
        quit();
    }

    public boolean isTerminated() {
        return terminated;
    }

    // ========== Accessors ==========

    public CdpClient getCdpClient() {
        return cdp;
    }

    public CdpDriverOptions getOptions() {
        return options;
    }

    // ========== Utilities ==========

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
