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

import io.karatelabs.js.SimpleObject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Browser automation driver interface.
 * Provides a unified API for browser automation across different backends (CDP, Playwright, WebDriver).
 *
 * <p>This interface extends {@link CoreDriver} which defines the ~12 primitive operations.
 * Most methods in this interface are convenience methods that delegate to those primitives,
 * primarily to {@link CoreDriver#eval(String)}.</p>
 *
 * <p>Phase 8: Extracted from CdpDriver to enable multi-backend support.</p>
 * <p>Phase 9: Implements ObjectLike for Gherkin/DSL integration (JS property access).</p>
 * <p>Phase 10: Extends CoreDriver to clearly separate primitives from convenience methods.</p>
 *
 * @see CoreDriver
 */
public interface Driver extends CoreDriver, SimpleObject {

    // ========== ObjectLike Implementation (for JS property access) ==========

    /**
     * Get a property value by name for JavaScript access.
     * Supports: driver.url, driver.title, driver.cookies
     */
    @Override
    default Object jsGet(String name) {
        return switch (name) {
            case "url" -> getUrl();
            case "title" -> getTitle();
            case "cookies" -> getCookies();
            default -> null;
        };
    }

    /**
     * Convert driver state to a map for JS/JSON serialization.
     */
    @Override
    default Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("url", getUrl());
        map.put("title", getTitle());
        map.put("cookies", getCookies());
        return map;
    }

    // ========== CoreDriver Primitive Implementations ==========
    // These default methods implement CoreDriver primitives by delegating to Driver methods

    /**
     * Execute JavaScript. Delegates to {@link #script(String)}.
     */
    @Override
    default Object eval(String js) {
        return script(js);
    }

    /**
     * Execute JavaScript with support for JsFunction.
     * Delegates to {@link #script(Object)}.
     *
     * @param expression JavaScript expression as String or JsFunction
     * @return the result of the JavaScript execution
     */
    default Object eval(Object expression) {
        return script(expression);
    }

    /**
     * Navigate to URL. Delegates to {@link #setUrl(String)}.
     */
    @Override
    default void navigate(String url) {
        setUrl(url);
    }

    /**
     * Switch frames. Dispatches to {@link #switchFrame(int)} or {@link #switchFrame(String)}.
     */
    @Override
    default void frame(Object target) {
        if (target == null) {
            switchFrame((String) null);
        } else if (target instanceof Number n) {
            switchFrame(n.intValue());
        } else {
            switchFrame(target.toString());
        }
    }

    /**
     * Window management. Dispatches to maximize(), minimize(), fullscreen().
     */
    @Override
    default void window(String operation) {
        switch (operation) {
            case "maximize" -> maximize();
            case "minimize" -> minimize();
            case "fullscreen" -> fullscreen();
            case "normal" -> setDimensions(Map.of("windowState", "normal"));
            default -> throw new DriverException("unknown window operation: " + operation);
        }
    }

    /**
     * Tab switching. Dispatches to {@link #switchPage(int)} or {@link #switchPage(String)}.
     */
    @Override
    default void tab(Object target) {
        if (target instanceof Number n) {
            switchPage(n.intValue());
        } else {
            switchPage(target.toString());
        }
    }

    // ========== Navigation ==========

    /**
     * Navigate to URL and wait for page load.
     */
    void setUrl(String url);

    /**
     * Get current URL.
     */
    String getUrl();

    /**
     * Get page title.
     */
    String getTitle();

    /**
     * Wait for page to load based on strategy.
     */
    void waitForPageLoad(PageLoadStrategy strategy);

    /**
     * Wait for page to load with custom timeout.
     */
    void waitForPageLoad(PageLoadStrategy strategy, Duration timeout);

    /**
     * Refresh the current page.
     */
    void refresh();

    /**
     * Reload the page ignoring cache.
     */
    void reload();

    /**
     * Navigate back in history.
     */
    void back();

    /**
     * Navigate forward in history.
     */
    void forward();

    // ========== JavaScript Evaluation ==========

    /**
     * Execute JavaScript and return result.
     */
    Object script(String expression);

    /**
     * Execute JavaScript with support for JsFunction.
     * If the argument is a JsFunction (e.g., arrow function), it will be serialized
     * to its source code and invoked as an IIFE.
     *
     * <p>Examples:</p>
     * <pre>
     * script("document.title")                    // string expression
     * script(() => document.title)                // arrow function (from Karate JS)
     * script(() => { return document.title })     // arrow function with block body
     * </pre>
     *
     * @param expression JavaScript expression as String or JsFunction
     * @return the result of the JavaScript execution
     */
    default Object script(Object expression) {
        String js = Locators.toFunction(expression);
        // If it's a function, wrap in IIFE to invoke it
        if (js.contains("=>") || js.startsWith("function")) {
            js = "(" + js + ")()";
        }
        return script(js);
    }

    /**
     * Execute a script on an element.
     * The element is available as '_' in the expression.
     * Expression can be a String ("_.value") or JsFunction (_ => _.value).
     */
    default Object script(String locator, Object expression) {
        return script(Locators.scriptSelector(locator, expression));
    }

    /**
     * Execute a script on all matching elements.
     * Each element is available as '_' in the expression.
     * Expression can be a String or JsFunction.
     */
    @SuppressWarnings("unchecked")
    default List<Object> scriptAll(String locator, Object expression) {
        return (List<Object>) script(Locators.scriptAllSelector(locator, expression));
    }

    // ========== Screenshot ==========

    /**
     * Take screenshot and return PNG bytes.
     */
    byte[] screenshot();

    /**
     * Take screenshot, optionally embed in report.
     */
    byte[] screenshot(boolean embed);

    // ========== Dialog Handling ==========

    /**
     * Register a handler for JavaScript dialogs (alert, confirm, prompt, beforeunload).
     */
    void onDialog(DialogHandler handler);

    /**
     * Get the current dialog message.
     */
    String getDialogText();

    /**
     * Accept or dismiss the current dialog.
     */
    void dialog(boolean accept);

    /**
     * Accept or dismiss the current dialog with optional prompt input.
     */
    void dialog(boolean accept, String input);

    // ========== Frame Switching ==========

    /**
     * Switch to an iframe by index.
     */
    void switchFrame(int index);

    /**
     * Switch to an iframe by locator, or null to return to main frame.
     */
    void switchFrame(String locator);

    /**
     * Get the current frame info, or null if in main frame.
     */
    Map<String, Object> getCurrentFrame();

    // ========== Element Operations (defaults delegate to script + Locators) ==========

    /**
     * Click an element.
     */
    default Element click(String locator) {
        script(Locators.clickJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Focus an element.
     */
    default Element focus(String locator) {
        script(Locators.focusJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Clear an input element.
     */
    default Element clear(String locator) {
        script(Locators.clearJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Input text into an element (focus, clear, type).
     */
    default Element input(String locator, String value) {
        focus(locator);
        clear(locator);
        keys().type(value);
        return Element.of(this, locator);
    }

    /**
     * Set the value of an input element directly.
     */
    default Element value(String locator, String value) {
        script(Locators.inputJs(locator, value));
        return Element.of(this, locator);
    }

    /**
     * Select an option from a dropdown by text or value.
     */
    default Element select(String locator, String text) {
        script(Locators.optionSelector(locator, text));
        return Element.of(this, locator);
    }

    /**
     * Select an option from a dropdown by index.
     */
    default Element select(String locator, int index) {
        String js = Locators.wrapInFunctionInvoke(
                "var e = " + Locators.selector(locator) + ";" +
                        " e.options[" + index + "].selected = true;" +
                        " e.dispatchEvent(new Event('input', {bubbles: true}));" +
                        " e.dispatchEvent(new Event('change', {bubbles: true}))");
        script(js);
        return Element.of(this, locator);
    }

    /**
     * Scroll an element into view.
     */
    default Element scroll(String locator) {
        script(Locators.scrollJs(locator));
        return Element.of(this, locator);
    }

    /**
     * Highlight an element (for debugging).
     */
    default Element highlight(String locator) {
        script(Locators.highlight(locator, getOptions().getHighlightDuration()));
        return Element.of(this, locator);
    }

    // ========== Element State (defaults delegate to script + Locators) ==========

    /**
     * Get the text content of an element.
     */
    default String text(String locator) {
        return (String) script(Locators.textJs(locator));
    }

    /**
     * Get the outer HTML of an element.
     */
    default String html(String locator) {
        return (String) script(Locators.outerHtmlJs(locator));
    }

    /**
     * Get the value of an input element.
     */
    default String value(String locator) {
        return (String) script(Locators.valueJs(locator));
    }

    /**
     * Get an attribute of an element.
     */
    default String attribute(String locator, String name) {
        return (String) script(Locators.attributeJs(locator, name));
    }

    /**
     * Get a property of an element.
     */
    default Object property(String locator, String name) {
        return script(Locators.propertyJs(locator, name));
    }

    /**
     * Check if an element is enabled.
     */
    default boolean enabled(String locator) {
        return Boolean.TRUE.equals(script(Locators.enabledJs(locator)));
    }

    /**
     * Check if an element exists.
     */
    default boolean exists(String locator) {
        return Boolean.TRUE.equals(script(Locators.existsJs(locator)));
    }

    /**
     * Get the position of an element (absolute).
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> position(String locator) {
        return (Map<String, Object>) script(Locators.getPositionJs(locator));
    }

    /**
     * Get the position of an element.
     *
     * @param relative if true, returns viewport-relative position
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> position(String locator, boolean relative) {
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
     * This is a convenience method that creates an Element wrapper.
     */
    default Element locate(String locator) {
        return Element.of(this, locator);
    }

    /**
     * Find all elements matching a locator.
     * Implementation requires driver-specific indexed locator support.
     */
    List<Element> locateAll(String locator);

    /**
     * Find an element that may not exist (optional).
     * This is a convenience method that creates an optional Element wrapper.
     */
    default Element optional(String locator) {
        return Element.optional(this, locator);
    }

    // ========== Wait Methods ==========

    /**
     * Wait for an element to exist.
     */
    Element waitFor(String locator);

    /**
     * Wait for an element to exist with custom timeout.
     */
    Element waitFor(String locator, Duration timeout);

    /**
     * Wait for any of the locators to match.
     */
    Element waitForAny(String locator1, String locator2);

    /**
     * Wait for any of the locators to match.
     */
    Element waitForAny(String[] locators);

    /**
     * Wait for any of the locators to match with custom timeout.
     */
    Element waitForAny(String[] locators, Duration timeout);

    /**
     * Wait for an element to contain specific text.
     */
    Element waitForText(String locator, String expected);

    /**
     * Wait for an element to contain specific text with custom timeout.
     */
    Element waitForText(String locator, String expected, Duration timeout);

    /**
     * Wait for an element to be enabled.
     */
    Element waitForEnabled(String locator);

    /**
     * Wait for an element to be enabled with custom timeout.
     */
    Element waitForEnabled(String locator, Duration timeout);

    /**
     * Wait for URL to contain expected string.
     */
    String waitForUrl(String expected);

    /**
     * Wait for URL to contain expected string with custom timeout.
     */
    String waitForUrl(String expected, Duration timeout);

    /**
     * Wait until a JavaScript expression on an element evaluates to truthy.
     */
    Element waitUntil(String locator, String expression);

    /**
     * Wait until a JavaScript expression on an element evaluates to truthy.
     */
    Element waitUntil(String locator, String expression, Duration timeout);

    /**
     * Wait until a JavaScript expression evaluates to truthy.
     */
    boolean waitUntil(String expression);

    /**
     * Wait until a JavaScript expression evaluates to truthy with custom timeout.
     */
    boolean waitUntil(String expression, Duration timeout);

    /**
     * Wait until a supplier returns a truthy value.
     */
    Object waitUntil(Supplier<Object> condition);

    /**
     * Wait until a supplier returns a truthy value with custom timeout.
     */
    Object waitUntil(Supplier<Object> condition, Duration timeout);

    /**
     * Wait for a specific number of elements to match.
     */
    List<Element> waitForResultCount(String locator, int count);

    /**
     * Wait for a specific number of elements to match with custom timeout.
     */
    List<Element> waitForResultCount(String locator, int count, Duration timeout);

    // ========== Cookies ==========

    /**
     * Get a cookie by name.
     */
    Map<String, Object> cookie(String name);

    /**
     * Set a cookie.
     */
    void cookie(Map<String, Object> cookie);

    /**
     * Delete a cookie by name.
     */
    void deleteCookie(String name);

    /**
     * Clear all cookies.
     */
    void clearCookies();

    /**
     * Get all cookies.
     */
    List<Map<String, Object>> getCookies();

    // ========== Window Management ==========

    /**
     * Maximize the browser window.
     */
    void maximize();

    /**
     * Minimize the browser window.
     */
    void minimize();

    /**
     * Make the browser window fullscreen.
     */
    void fullscreen();

    /**
     * Get window dimensions and position.
     */
    Map<String, Object> getDimensions();

    /**
     * Set window dimensions and/or position.
     */
    void setDimensions(Map<String, Object> dimensions);

    /**
     * Activate the browser window (bring to front).
     */
    void activate();

    // ========== PDF Generation ==========

    /**
     * Generate a PDF of the current page.
     */
    byte[] pdf(Map<String, Object> options);

    /**
     * Generate a PDF of the current page with default options.
     */
    byte[] pdf();

    // ========== Mouse and Keyboard ==========

    /**
     * Get a Mouse object at position (0, 0).
     */
    Mouse mouse();

    /**
     * Get a Mouse object positioned at an element's center.
     */
    Mouse mouse(String locator);

    /**
     * Get a Mouse object at specified coordinates.
     */
    Mouse mouse(Number x, Number y);

    /**
     * Get a Keys object for keyboard input.
     */
    Keys keys();

    // ========== Pages/Tabs Management ==========

    /**
     * Get list of all page targets (tabs).
     */
    List<String> getPages();

    /**
     * Switch to a page by title or URL substring.
     */
    void switchPage(String titleOrUrl);

    /**
     * Switch to a page by index.
     */
    void switchPage(int index);

    // ========== Positional Locators ==========

    /**
     * Create a finder for elements to the right of the reference element.
     * This is a convenience method that creates a Finder.
     */
    default Finder rightOf(String locator) {
        return new Finder(this, locator, Finder.Position.RIGHT_OF);
    }

    /**
     * Create a finder for elements to the left of the reference element.
     * This is a convenience method that creates a Finder.
     */
    default Finder leftOf(String locator) {
        return new Finder(this, locator, Finder.Position.LEFT_OF);
    }

    /**
     * Create a finder for elements above the reference element.
     * This is a convenience method that creates a Finder.
     */
    default Finder above(String locator) {
        return new Finder(this, locator, Finder.Position.ABOVE);
    }

    /**
     * Create a finder for elements below the reference element.
     * This is a convenience method that creates a Finder.
     */
    default Finder below(String locator) {
        return new Finder(this, locator, Finder.Position.BELOW);
    }

    /**
     * Create a finder for elements near the reference element.
     * This is a convenience method that creates a Finder.
     */
    default Finder near(String locator) {
        return new Finder(this, locator, Finder.Position.NEAR);
    }

    // ========== Request Interception ==========

    /**
     * Enable request interception with a handler.
     */
    void intercept(List<String> patterns, InterceptHandler handler);

    /**
     * Enable request interception for all requests.
     */
    void intercept(InterceptHandler handler);

    /**
     * Stop request interception.
     */
    void stopIntercept();

    // ========== Lifecycle ==========

    /**
     * Close driver and browser.
     */
    void quit();

    /**
     * Alias for quit().
     */
    void close();

    /**
     * Check if driver is terminated.
     */
    boolean isTerminated();

    // ========== Accessors ==========

    /**
     * Get driver options.
     */
    DriverOptions getOptions();

}
