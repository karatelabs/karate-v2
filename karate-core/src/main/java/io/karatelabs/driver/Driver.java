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

import io.karatelabs.js.ObjectLike;
import io.karatelabs.js.SimpleObject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Browser automation driver interface.
 * Provides a unified API for browser automation across different backends (CDP, Playwright, WebDriver).
 * <p>
 * Phase 8: Extracted from CdpDriver to enable multi-backend support.
 * Phase 9: Implements ObjectLike for Gherkin/DSL integration (JS property access).
 */
public interface Driver extends SimpleObject {

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
     * Execute a script on an element.
     * The element is available as '_' in the expression.
     */
    Object script(String locator, String expression);

    /**
     * Execute a script on all matching elements.
     * Each element is available as '_' in the expression.
     */
    List<Object> scriptAll(String locator, String expression);

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

    // ========== Element Operations ==========

    /**
     * Click an element.
     */
    Element click(String locator);

    /**
     * Focus an element.
     */
    Element focus(String locator);

    /**
     * Clear an input element.
     */
    Element clear(String locator);

    /**
     * Input text into an element.
     */
    Element input(String locator, String value);

    /**
     * Set the value of an input element.
     */
    Element value(String locator, String value);

    /**
     * Select an option from a dropdown by text or value.
     */
    Element select(String locator, String text);

    /**
     * Select an option from a dropdown by index.
     */
    Element select(String locator, int index);

    /**
     * Scroll an element into view.
     */
    Element scroll(String locator);

    /**
     * Highlight an element.
     */
    Element highlight(String locator);

    // ========== Element State ==========

    /**
     * Get the text content of an element.
     */
    String text(String locator);

    /**
     * Get the outer HTML of an element.
     */
    String html(String locator);

    /**
     * Get the value of an input element.
     */
    String value(String locator);

    /**
     * Get an attribute of an element.
     */
    String attribute(String locator, String name);

    /**
     * Get a property of an element.
     */
    Object property(String locator, String name);

    /**
     * Check if an element is enabled.
     */
    boolean enabled(String locator);

    /**
     * Check if an element exists.
     */
    boolean exists(String locator);

    /**
     * Get the position of an element (absolute).
     */
    Map<String, Object> position(String locator);

    /**
     * Get the position of an element.
     * @param relative if true, returns viewport-relative position
     */
    Map<String, Object> position(String locator, boolean relative);

    // ========== Locators ==========

    /**
     * Find an element by locator.
     */
    Element locate(String locator);

    /**
     * Find all elements matching a locator.
     */
    List<Element> locateAll(String locator);

    /**
     * Find an element that may not exist (optional).
     */
    Element optional(String locator);

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
     */
    Finder rightOf(String locator);

    /**
     * Create a finder for elements to the left of the reference element.
     */
    Finder leftOf(String locator);

    /**
     * Create a finder for elements above the reference element.
     */
    Finder above(String locator);

    /**
     * Create a finder for elements below the reference element.
     */
    Finder below(String locator);

    /**
     * Create a finder for elements near the reference element.
     */
    Finder near(String locator);

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
