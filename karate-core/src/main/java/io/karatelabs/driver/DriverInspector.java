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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Visibility layer for browser driver operations.
 * Provides screenshots, DOM queries, console messages, and event streaming
 * for debugging and AI-assisted development.
 */
public class DriverInspector {

    private static final Logger logger = LogContext.RUNTIME_LOGGER;

    private final CdpDriver driver;
    private final CdpClient cdp;

    // Console message capture
    private final List<String> consoleMessages = new CopyOnWriteArrayList<>();
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();

    // Event handlers
    private Consumer<String> consoleMessageHandler;
    private Consumer<Map<String, Object>> networkRequestHandler;
    private Consumer<Map<String, Object>> networkResponseHandler;
    private Runnable pageLoadHandler;
    private Consumer<Throwable> errorHandler;

    public DriverInspector(CdpDriver driver) {
        this.driver = driver;
        this.cdp = driver.getCdpClient();
        enableObservability();
    }

    private void enableObservability() {
        // Enable CDP domains for observability
        cdp.method("Console.enable").send();
        cdp.method("Network.enable").send();
        // Page.enable and Runtime.enable are already called by CdpDriver

        // Subscribe to console messages
        cdp.on("Console.messageAdded", event -> {
            String text = event.get("message.text");
            String level = event.get("message.level");
            if (text != null) {
                consoleMessages.add(text);
                if ("error".equals(level) || "warning".equals(level)) {
                    consoleErrors.add(text);
                }
                if (consoleMessageHandler != null) {
                    consoleMessageHandler.accept(text);
                }
            }
        });

        // Subscribe to runtime console API messages
        cdp.on("Runtime.consoleAPICalled", event -> {
            String type = event.get("type");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> args = event.get("args");
            if (args != null && !args.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> arg : args) {
                    Object value = arg.get("value");
                    if (value != null) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(value.toString());
                    }
                }
                String text = sb.toString();
                if (!text.isEmpty()) {
                    consoleMessages.add(text);
                    if ("error".equals(type) || "warning".equals(type)) {
                        consoleErrors.add(text);
                    }
                    if (consoleMessageHandler != null) {
                        consoleMessageHandler.accept(text);
                    }
                }
            }
        });

        // Subscribe to network events
        cdp.on("Network.requestWillBeSent", event -> {
            if (networkRequestHandler != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = event.get("request");
                if (request != null) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("requestId", event.get("requestId"));
                    info.put("url", request.get("url"));
                    info.put("method", request.get("method"));
                    info.put("headers", request.get("headers"));
                    networkRequestHandler.accept(info);
                }
            }
        });

        cdp.on("Network.responseReceived", event -> {
            if (networkResponseHandler != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = event.get("response");
                if (response != null) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("requestId", event.get("requestId"));
                    info.put("url", response.get("url"));
                    info.put("status", response.get("status"));
                    info.put("statusText", response.get("statusText"));
                    info.put("headers", response.get("headers"));
                    info.put("mimeType", response.get("mimeType"));
                    networkResponseHandler.accept(info);
                }
            }
        });

        // Subscribe to page load events
        cdp.on("Page.loadEventFired", event -> {
            if (pageLoadHandler != null) {
                pageLoadHandler.run();
            }
        });

        // Subscribe to exception thrown
        cdp.on("Runtime.exceptionThrown", event -> {
            if (errorHandler != null) {
                String description = event.get("exceptionDetails.exception.description");
                if (description == null) {
                    description = event.get("exceptionDetails.text");
                }
                errorHandler.accept(new RuntimeException("JS Exception: " + description));
            }
        });

        logger.debug("DriverInspector observability enabled");
    }

    // ========== Screenshot Capture ==========

    /**
     * Capture screenshot as PNG bytes.
     */
    public byte[] captureScreenshot() {
        return driver.screenshot();
    }

    /**
     * Capture screenshot in specified format.
     * @param format one of: "png", "jpeg", "webp"
     */
    public byte[] captureScreenshot(String format) {
        CdpResponse response = cdp.method("Page.captureScreenshot")
                .param("format", format)
                .send();
        String base64 = response.getResultAsString("data");
        return Base64.getDecoder().decode(base64);
    }

    /**
     * Capture screenshot as Base64 string.
     */
    public String captureScreenshotBase64() {
        CdpResponse response = cdp.method("Page.captureScreenshot")
                .param("format", "png")
                .send();
        return response.getResultAsString("data");
    }

    /**
     * Save screenshot to file.
     */
    public void saveScreenshot(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, captureScreenshot());
            logger.debug("screenshot saved: {}", path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save screenshot: " + e.getMessage(), e);
        }
    }

    // ========== DOM Queries ==========

    /**
     * Execute JavaScript expression and return result.
     */
    public Object evalJs(String expression) {
        return driver.script(expression);
    }

    /**
     * Get outer HTML of element matching selector.
     */
    public String getOuterHtml(String selector) {
        String js = "(function(){ var e = document.querySelector('" + escapeJs(selector) + "'); return e ? e.outerHTML : null; })()";
        return (String) driver.script(js);
    }

    /**
     * Get inner HTML of element matching selector.
     */
    public String getInnerHtml(String selector) {
        String js = "(function(){ var e = document.querySelector('" + escapeJs(selector) + "'); return e ? e.innerHTML : null; })()";
        return (String) driver.script(js);
    }

    /**
     * Get text content of element matching selector.
     */
    public String getText(String selector) {
        String js = "(function(){ var e = document.querySelector('" + escapeJs(selector) + "'); return e ? e.textContent : null; })()";
        return (String) driver.script(js);
    }

    /**
     * Get all attributes of element matching selector.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getAttributes(String selector) {
        String js = "(function(){ var e = document.querySelector('" + escapeJs(selector) + "'); " +
                "if (!e) return null; " +
                "var attrs = {}; " +
                "for (var i = 0; i < e.attributes.length; i++) { " +
                "  attrs[e.attributes[i].name] = e.attributes[i].value; " +
                "} " +
                "return attrs; })()";
        return (Map<String, String>) driver.script(js);
    }

    /**
     * Query all elements matching selector and return their basic info.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> querySelectorAll(String selector) {
        String js = "(function(){ var els = document.querySelectorAll('" + escapeJs(selector) + "'); " +
                "var result = []; " +
                "els.forEach(function(e) { " +
                "  result.push({ " +
                "    tagName: e.tagName, " +
                "    id: e.id || null, " +
                "    className: e.className || null, " +
                "    textContent: e.textContent ? e.textContent.substring(0, 100) : null " +
                "  }); " +
                "}); " +
                "return result; })()";
        return (List<Map<String, Object>>) driver.script(js);
    }

    // ========== Page State ==========

    /**
     * Get full page source HTML.
     */
    public String getPageSource() {
        return (String) driver.script("document.documentElement.outerHTML");
    }

    /**
     * Get current page URL.
     */
    public String getCurrentUrl() {
        return driver.getUrl();
    }

    /**
     * Get page title.
     */
    public String getTitle() {
        return driver.getTitle();
    }

    /**
     * Get all captured console messages.
     */
    public List<String> getConsoleMessages() {
        return new ArrayList<>(consoleMessages);
    }

    /**
     * Get console errors and warnings.
     */
    public List<String> getConsoleErrors() {
        return new ArrayList<>(consoleErrors);
    }

    /**
     * Clear captured console messages.
     */
    public void clearConsoleMessages() {
        consoleMessages.clear();
        consoleErrors.clear();
    }

    // ========== Event Handlers ==========

    /**
     * Register handler for console messages.
     */
    public void onConsoleMessage(Consumer<String> handler) {
        this.consoleMessageHandler = handler;
    }

    /**
     * Register handler for network requests.
     */
    public void onNetworkRequest(Consumer<Map<String, Object>> handler) {
        this.networkRequestHandler = handler;
    }

    /**
     * Register handler for network responses.
     */
    public void onNetworkResponse(Consumer<Map<String, Object>> handler) {
        this.networkResponseHandler = handler;
    }

    /**
     * Register handler for page load events.
     */
    public void onPageLoad(Runnable handler) {
        this.pageLoadHandler = handler;
    }

    /**
     * Register handler for JavaScript errors.
     */
    public void onError(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    // ========== Debug Snapshot ==========

    /**
     * Get a complete debug snapshot of current page state.
     * Useful for debugging and AI-assisted development.
     */
    public Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("url", getCurrentUrl());
        snapshot.put("title", getTitle());
        snapshot.put("consoleMessages", new ArrayList<>(consoleMessages));
        snapshot.put("consoleErrors", new ArrayList<>(consoleErrors));
        snapshot.put("screenshotBase64", captureScreenshotBase64());
        return snapshot;
    }

    /**
     * Get a light debug snapshot without screenshot.
     */
    public Map<String, Object> getSnapshotLight() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("url", getCurrentUrl());
        snapshot.put("title", getTitle());
        snapshot.put("consoleMessages", new ArrayList<>(consoleMessages));
        snapshot.put("consoleErrors", new ArrayList<>(consoleErrors));
        return snapshot;
    }

    // ========== Utilities ==========

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

}
