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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

    // Dialog handling
    private volatile String currentDialogText;
    private volatile DialogHandler dialogHandler;

    // Frame tracking
    private Frame currentFrame;
    private final Map<String, Integer> frameContexts = new ConcurrentHashMap<>();

    /**
     * Internal representation of a frame.
     */
    private static class Frame {
        final String id;
        final String url;
        final String name;

        Frame(String id, String url, String name) {
            this.id = id;
            this.url = url;
            this.name = name;
        }

        @Override
        public String toString() {
            return "Frame{id='" + id + "', url='" + url + "', name='" + name + "'}";
        }
    }

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

        cdp.on("Page.javascriptDialogOpening", event -> {
            String message = event.get("message");
            String type = event.get("type");
            String defaultPrompt = event.get("defaultPrompt");
            currentDialogText = message;
            logger.debug("dialog opening: type={}, message={}", type, message);

            if (dialogHandler != null) {
                Dialog dialog = new Dialog(cdp, message, type, defaultPrompt);
                dialogHandler.handle(dialog);
                // If handler didn't resolve the dialog, auto-dismiss
                if (!dialog.isHandled()) {
                    logger.warn("dialog handler did not resolve dialog, auto-dismissing");
                    dialog.dismiss();
                }
            }
            // If no handler registered, dialog will block - user must call dialog() methods
        });

        // Track execution contexts for frames
        cdp.on("Runtime.executionContextCreated", event -> {
            Map<String, Object> context = event.get("context");
            if (context != null) {
                Number contextId = (Number) context.get("id");
                Map<String, Object> auxData = (Map<String, Object>) context.get("auxData");
                if (auxData != null && contextId != null) {
                    String frameId = (String) auxData.get("frameId");
                    Boolean isDefault = (Boolean) auxData.get("isDefault");
                    if (frameId != null && Boolean.TRUE.equals(isDefault)) {
                        frameContexts.put(frameId, contextId.intValue());
                        logger.trace("execution context created: frameId={}, contextId={}", frameId, contextId);
                    }
                }
            }
        });

        cdp.on("Runtime.executionContextsCleared", event -> {
            frameContexts.clear();
            logger.trace("execution contexts cleared");
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
        CdpMessage message = cdp.method("Runtime.evaluate")
                .param("expression", expression)
                .param("returnByValue", true);

        // If in a frame, use its execution context
        Integer contextId = getFrameContext();
        if (contextId != null) {
            message.param("contextId", contextId);
        }

        return message.send();
    }

    private Integer getFrameContext() {
        if (currentFrame == null) {
            return null;
        }
        return frameContexts.get(currentFrame.id);
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

    // ========== Dialog Handling ==========

    /**
     * Register a handler for JavaScript dialogs (alert, confirm, prompt, beforeunload).
     * When a dialog opens, the handler is called with a Dialog object.
     * The handler should call dialog.accept() or dialog.dismiss() to resolve the dialog.
     *
     * @param handler the dialog handler, or null to remove the handler
     */
    public void onDialog(DialogHandler handler) {
        this.dialogHandler = handler;
    }

    /**
     * Get the current dialog message.
     *
     * @return the dialog message, or null if no dialog is open
     */
    public String getDialogText() {
        return currentDialogText;
    }

    /**
     * Accept or dismiss the current dialog.
     * This is used when no DialogHandler is registered.
     *
     * @param accept true to accept (OK), false to dismiss (Cancel)
     */
    public void dialog(boolean accept) {
        dialog(accept, null);
    }

    /**
     * Accept or dismiss the current dialog with optional prompt input.
     * This is used when no DialogHandler is registered.
     *
     * @param accept true to accept (OK), false to dismiss (Cancel)
     * @param input the text to enter for prompt dialogs (ignored if accept is false)
     */
    public void dialog(boolean accept, String input) {
        CdpMessage message = cdp.method("Page.handleJavaScriptDialog")
                .param("accept", accept);
        if (accept && input != null) {
            message.param("promptText", input);
        }
        message.send();
        currentDialogText = null;
    }

    // ========== Frame Switching ==========

    /**
     * Switch to an iframe by index in the frame tree.
     *
     * @param index the zero-based index of the frame
     */
    public void switchFrame(int index) {
        CdpResponse response = cdp.method("Page.getFrameTree").send();
        List<Map<String, Object>> childFrames = response.getResult("frameTree.childFrames");

        if (childFrames == null || index < 0 || index >= childFrames.size()) {
            throw new DriverException("no frame at index: " + index);
        }

        Map<String, Object> frameData = childFrames.get(index);
        Map<String, Object> frame = (Map<String, Object>) frameData.get("frame");
        String frameId = (String) frame.get("id");
        String url = (String) frame.get("url");
        String name = (String) frame.get("name");

        currentFrame = new Frame(frameId, url, name);
        logger.debug("switched to frame by index {}: {}", index, currentFrame);

        // Ensure we have execution context for this frame
        ensureFrameContext(frameId);
    }

    /**
     * Switch to an iframe by locator (CSS, XPath, or wildcard).
     * Pass null to switch back to the main frame.
     *
     * @param locator the locator for the iframe element, or null to return to main frame
     */
    public void switchFrame(String locator) {
        if (locator == null) {
            // Switch back to main frame
            currentFrame = null;
            logger.debug("switched to main frame");
            return;
        }

        // Find the iframe element and get its frame ID
        String js = Locators.wrapInFunctionInvoke(
                "var e = " + Locators.selector(locator) + ";" +
                        " if (!e) return null;" +
                        " if (e.tagName !== 'IFRAME' && e.tagName !== 'FRAME') return { error: 'not a frame element' };" +
                        " return { " +
                        "   name: e.name || ''," +
                        "   src: e.src || ''" +
                        " }");
        Object result = script(js);

        if (result == null) {
            throw new DriverException("frame not found: " + locator);
        }

        Map<String, Object> frameInfo = (Map<String, Object>) result;
        if (frameInfo.containsKey("error")) {
            throw new DriverException("locator is not a frame: " + locator);
        }

        // Get frame ID from frame tree by matching name or src
        String targetName = (String) frameInfo.get("name");
        String targetSrc = (String) frameInfo.get("src");

        CdpResponse response = cdp.method("Page.getFrameTree").send();
        List<Map<String, Object>> childFrames = response.getResult("frameTree.childFrames");

        if (childFrames == null || childFrames.isEmpty()) {
            throw new DriverException("no child frames in page");
        }

        // Find matching frame in tree
        String frameId = null;
        String url = null;
        String name = null;

        for (Map<String, Object> frameData : childFrames) {
            Map<String, Object> frame = (Map<String, Object>) frameData.get("frame");
            String fId = (String) frame.get("id");
            String fUrl = (String) frame.get("url");
            String fName = (String) frame.get("name");

            // Match by name if provided, otherwise by URL
            boolean matches = false;
            if (targetName != null && !targetName.isEmpty() && targetName.equals(fName)) {
                matches = true;
            } else if (targetSrc != null && !targetSrc.isEmpty() && fUrl != null && fUrl.contains(targetSrc)) {
                matches = true;
            } else if ((targetName == null || targetName.isEmpty()) && (targetSrc == null || targetSrc.isEmpty())) {
                // No name or src - use first frame
                matches = true;
            }

            if (matches) {
                frameId = fId;
                url = fUrl;
                name = fName;
                break;
            }
        }

        if (frameId == null) {
            throw new DriverException("could not find frame for locator: " + locator);
        }

        currentFrame = new Frame(frameId, url, name);
        logger.debug("switched to frame by locator {}: {}", locator, currentFrame);

        // Ensure we have execution context for this frame
        ensureFrameContext(frameId);
    }

    /**
     * Get the current frame, or null if in main frame.
     *
     * @return the current frame info, or null
     */
    public Map<String, Object> getCurrentFrame() {
        if (currentFrame == null) {
            return null;
        }
        return Map.of(
                "id", currentFrame.id,
                "url", currentFrame.url != null ? currentFrame.url : "",
                "name", currentFrame.name != null ? currentFrame.name : ""
        );
    }

    private void ensureFrameContext(String frameId) {
        // Check if we already have an execution context for this frame
        if (frameContexts.containsKey(frameId)) {
            return;
        }

        // Create an isolated world for this frame
        try {
            CdpResponse response = cdp.method("Page.createIsolatedWorld")
                    .param("frameId", frameId)
                    .send();
            Integer contextId = response.getResult("executionContextId");
            if (contextId != null) {
                frameContexts.put(frameId, contextId);
                logger.debug("created isolated world for frame {}: contextId={}", frameId, contextId);
            }
        } catch (Exception e) {
            logger.warn("failed to create isolated world for frame {}: {}", frameId, e.getMessage());
        }
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

    // ========== Element Operations ==========

    /**
     * Click an element.
     */
    public Element click(String locator) {
        logger.debug("click: {}", locator);
        script(Locators.clickJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Focus an element.
     */
    public Element focus(String locator) {
        logger.debug("focus: {}", locator);
        script(Locators.focusJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Clear an input element.
     */
    public Element clear(String locator) {
        logger.debug("clear: {}", locator);
        script(Locators.clearJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Input text into an element.
     */
    public Element input(String locator, String value) {
        logger.debug("input: {} <- {}", locator, value);
        script(Locators.inputJs(locator, value));
        return Element.of(this, locator);
    }

    /**
     * Set the value of an input element.
     */
    public Element value(String locator, String value) {
        logger.debug("value: {} <- {}", locator, value);
        script(Locators.inputJs(locator, value));
        return Element.of(this, locator);
    }

    /**
     * Select an option from a dropdown by text or value.
     */
    public Element select(String locator, String text) {
        logger.debug("select: {} <- {}", locator, text);
        script(Locators.optionSelector(locator, text));
        return Element.of(this, locator);
    }

    /**
     * Select an option from a dropdown by index.
     */
    public Element select(String locator, int index) {
        logger.debug("select: {} <- index {}", locator, index);
        String js = Locators.wrapInFunctionInvoke(
                "var e = " + Locators.selector(locator) + ";" +
                        " e.options[" + index + "].selected = true;" +
                        " e.dispatchEvent(new Event('change'))");
        script(js);
        return Element.of(this, locator);
    }

    /**
     * Scroll an element into view.
     */
    public Element scroll(String locator) {
        logger.debug("scroll: {}", locator);
        script(Locators.scrollJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Highlight an element.
     */
    public Element highlight(String locator) {
        logger.debug("highlight: {}", locator);
        script(Locators.highlight(locator, options.getHighlightDuration()));
        return Element.of(this, locator);
    }

    // ========== Element State ==========

    /**
     * Get the text content of an element.
     */
    public String text(String locator) {
        return (String) script(Locators.textJs(locator));
    }

    /**
     * Get the outer HTML of an element.
     */
    public String html(String locator) {
        return (String) script(Locators.outerHtmlJs(locator));
    }

    /**
     * Get the value of an input element.
     */
    public String value(String locator) {
        return (String) script(Locators.valueJs(locator));
    }

    /**
     * Get an attribute of an element.
     */
    public String attribute(String locator, String name) {
        return (String) script(Locators.attributeJs(locator, name));
    }

    /**
     * Get a property of an element.
     */
    public Object property(String locator, String name) {
        return script(Locators.propertyJs(locator, name));
    }

    /**
     * Check if an element is enabled.
     */
    public boolean enabled(String locator) {
        Object result = script(Locators.enabledJs(locator));
        return Boolean.TRUE.equals(result);
    }

    /**
     * Check if an element exists.
     */
    public boolean exists(String locator) {
        Object result = script(Locators.existsJs(locator));
        return Boolean.TRUE.equals(result);
    }

    /**
     * Get the position of an element (absolute).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> position(String locator) {
        return (Map<String, Object>) script(Locators.getPositionJs(locator));
    }

    /**
     * Get the position of an element.
     * @param relative if true, returns viewport-relative position
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> position(String locator, boolean relative) {
        if (relative) {
            String js = Locators.wrapInFunctionInvoke(
                    "var r = " + Locators.selector(locator) + ".getBoundingClientRect();" +
                            " return { x: r.x, y: r.y, width: r.width, height: r.height }");
            return (Map<String, Object>) script(js);
        }
        return position(locator);
    }

    // ========== Locators ==========

    /**
     * Find an element by locator.
     */
    public Element locate(String locator) {
        return Element.of(this, locator);
    }

    /**
     * Find all elements matching a locator.
     */
    @SuppressWarnings("unchecked")
    public List<Element> locateAll(String locator) {
        String js = Locators.scriptAllSelector(locator, Locators.KARATE_REF_GENERATOR);
        List<String> refs = (List<String>) script(js);
        if (refs == null) {
            return List.of();
        }
        List<Element> elements = new ArrayList<>();
        for (String ref : refs) {
            // Create a JS expression locator for each element
            String elementLocator = "(document._karate['" + ref + "'])";
            elements.add(new Element(this, elementLocator, true));
        }
        return elements;
    }

    /**
     * Find an element that may not exist (optional).
     */
    public Element optional(String locator) {
        return Element.optional(this, locator);
    }

    // ========== Script on Element ==========

    /**
     * Execute a script on an element.
     * The element is available as '_' in the expression.
     */
    public Object script(String locator, String expression) {
        String js = Locators.scriptSelector(locator, expression);
        return script(js);
    }

    /**
     * Execute a script on all matching elements.
     * Each element is available as '_' in the expression.
     */
    @SuppressWarnings("unchecked")
    public List<Object> scriptAll(String locator, String expression) {
        String js = Locators.scriptAllSelector(locator, expression);
        return (List<Object>) script(js);
    }

    // ========== Wait Methods ==========

    /**
     * Wait for an element to exist.
     */
    public Element waitFor(String locator) {
        return waitFor(locator, options.getTimeoutDuration());
    }

    /**
     * Wait for an element to exist with custom timeout.
     */
    public Element waitFor(String locator, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            if (exists(locator)) {
                return Element.of(this, locator);
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for element: " + locator);
    }

    /**
     * Wait for any of the locators to match.
     */
    public Element waitForAny(String locator1, String locator2) {
        return waitForAny(new String[]{locator1, locator2});
    }

    /**
     * Wait for any of the locators to match.
     */
    public Element waitForAny(String[] locators) {
        return waitForAny(locators, options.getTimeoutDuration());
    }

    /**
     * Wait for any of the locators to match with custom timeout.
     */
    public Element waitForAny(String[] locators, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            for (String locator : locators) {
                if (exists(locator)) {
                    return Element.of(this, locator);
                }
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for any element: " + String.join(", ", locators));
    }

    /**
     * Wait for an element to contain specific text.
     */
    public Element waitForText(String locator, String expected) {
        return waitForText(locator, expected, options.getTimeoutDuration());
    }

    /**
     * Wait for an element to contain specific text with custom timeout.
     */
    public Element waitForText(String locator, String expected, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            if (exists(locator)) {
                String text = text(locator);
                if (text != null && text.contains(expected)) {
                    return Element.of(this, locator);
                }
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for text '" + expected + "' in element: " + locator);
    }

    /**
     * Wait for an element to be enabled.
     */
    public Element waitForEnabled(String locator) {
        return waitForEnabled(locator, options.getTimeoutDuration());
    }

    /**
     * Wait for an element to be enabled with custom timeout.
     */
    public Element waitForEnabled(String locator, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            if (exists(locator) && enabled(locator)) {
                return Element.of(this, locator);
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for element to be enabled: " + locator);
    }

    /**
     * Wait for URL to contain expected string.
     */
    public String waitForUrl(String expected) {
        return waitForUrl(expected, options.getTimeoutDuration());
    }

    /**
     * Wait for URL to contain expected string with custom timeout.
     */
    public String waitForUrl(String expected, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            String url = getUrl();
            if (url != null && url.contains(expected)) {
                return url;
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for URL to contain: " + expected);
    }

    /**
     * Wait until a JavaScript expression on an element evaluates to truthy.
     * The element is available as '_' in the expression.
     */
    public Element waitUntil(String locator, String expression) {
        return waitUntil(locator, expression, options.getTimeoutDuration());
    }

    /**
     * Wait until a JavaScript expression on an element evaluates to truthy.
     */
    public Element waitUntil(String locator, String expression, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            if (exists(locator)) {
                Object result = script(locator, expression);
                if (isTruthy(result)) {
                    return Element.of(this, locator);
                }
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for condition '" + expression + "' on element: " + locator);
    }

    /**
     * Wait until a JavaScript expression evaluates to truthy.
     */
    public boolean waitUntil(String expression) {
        return waitUntil(expression, options.getTimeoutDuration());
    }

    /**
     * Wait until a JavaScript expression evaluates to truthy with custom timeout.
     */
    public boolean waitUntil(String expression, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            Object result = script(expression);
            if (isTruthy(result)) {
                return true;
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for condition: " + expression);
    }

    /**
     * Wait until a supplier returns a truthy value.
     */
    public Object waitUntil(Supplier<Object> condition) {
        return waitUntil(condition, options.getTimeoutDuration());
    }

    /**
     * Wait until a supplier returns a truthy value with custom timeout.
     */
    public Object waitUntil(Supplier<Object> condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            Object result = condition.get();
            if (isTruthy(result)) {
                return result;
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for condition");
    }

    /**
     * Wait for a specific number of elements to match.
     */
    public List<Element> waitForResultCount(String locator, int count) {
        return waitForResultCount(locator, count, options.getTimeoutDuration());
    }

    /**
     * Wait for a specific number of elements to match with custom timeout.
     */
    public List<Element> waitForResultCount(String locator, int count, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int pollInterval = options.getRetryInterval();

        while (System.currentTimeMillis() < deadline) {
            Object result = script(Locators.countJs(locator));
            int actual = ((Number) result).intValue();
            if (actual == count) {
                return locateAll(locator);
            }
            sleep(pollInterval);
        }

        throw new DriverException("timeout waiting for " + count + " elements: " + locator);
    }

    // ========== Utilities ==========

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        return true;
    }

}
