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

import io.karatelabs.js.Invokable;
import io.karatelabs.js.SimpleObject;

import java.util.*;

/**
 * 5-method facade over Driver for LLM browser agents.
 * Exposes: look(), act(), go(), wait(), eval()
 *
 * Implements SimpleObject so methods are accessible from JavaScript:
 *   agent.look()
 *   agent.act('e1', 'click')
 *   agent.go('https://example.com')
 */
public class AgentDriver implements SimpleObject {

    private static final String ARIA_SNAPSHOT_JS = loadAriaSnapshotJs();

    private final Driver driver;
    private final boolean sidebarEnabled;

    public AgentDriver(Driver driver) {
        this(driver, false);
    }

    public AgentDriver(Driver driver, boolean sidebarEnabled) {
        this.driver = driver;
        this.sidebarEnabled = sidebarEnabled;
        // Inject the aria-snapshot.js into the browser
        injectAriaSnapshot();
    }

    @Override
    public Object jsGet(String name) {
        return switch (name) {
            case "look" -> (Invokable) this::look;
            case "act" -> (Invokable) this::act;
            case "go" -> (Invokable) this::go;
            case "wait" -> (Invokable) this::waitFor;
            case "eval" -> (Invokable) this::evalJs;
            case "handoff" -> (Invokable) this::handoff;
            case "driver" -> driver; // Escape hatch for advanced use
            default -> null;
        };
    }

    @Override
    public Collection<String> keys() {
        return List.of("look", "act", "go", "wait", "eval", "handoff", "driver");
    }

    // ========== Core API Methods ==========

    /**
     * Get current page state and available actions.
     * Usage: agent.look() or agent.look({scope: '#main', viewport: true})
     */
    private Map<String, Object> look(Object... args) {
        Map<String, Object> options = argsToMap(args);

        emit("cmd", "look()");

        // Re-inject script in case of navigation
        injectAriaSnapshot();

        // Build options JSON for the browser script
        String optionsJson = buildOptionsJson(options);

        // Get interactable elements from browser
        @SuppressWarnings("unchecked")
        List<String> tree = (List<String>) driver.script(
            "window.__karate.getInteractables(" + optionsJson + ")"
        );

        // Get actions map
        @SuppressWarnings("unchecked")
        Map<String, List<String>> actions = (Map<String, List<String>>) driver.script(
            "window.__karate.getActions()"
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", driver.getUrl());
        result.put("title", driver.getTitle());
        result.put("tree", tree != null ? tree : List.of());
        result.put("actions", actions != null ? actions : Map.of());

        // Check if page is still loading
        Boolean loading = (Boolean) driver.script(
            "document.readyState !== 'complete'"
        );
        if (Boolean.TRUE.equals(loading)) {
            result.put("loading", true);
        }

        int count = tree != null ? tree.size() : 0;
        emit("result", "Found " + count + " elements");

        return result;
    }

    /**
     * Perform an action on an element.
     * Usage: agent.act('e1', 'click') or agent.act('e1', 'input', 'hello')
     */
    private Map<String, Object> act(Object... args) {
        if (args.length < 2) {
            return errorResult("act requires ref and op arguments", "INVALID_ARGS");
        }

        String ref = String.valueOf(args[0]);
        String op = String.valueOf(args[1]);
        Object value = args.length > 2 ? args[2] : null;

        // Emit command to extension
        String cmdText = value != null
            ? "act(<code>" + ref + "</code>, <code>" + op + "</code>, <code>" + value + "</code>)"
            : "act(<code>" + ref + "</code>, <code>" + op + "</code>)";
        emit("cmd", cmdText, ref);

        try {
            // Resolve ref to element locator
            String locator = "ref:" + ref;

            boolean changed = performAction(locator, op, value);

            emit("result", op + " completed");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("changed", changed);
            return result;

        } catch (DriverException e) {
            String message = e.getMessage();
            if (message != null && message.contains("stale")) {
                emit("error", "Stale ref - need to look() again");
                return errorResult("ref " + ref + " is stale, call look() to refresh", "STALE_REF");
            }
            emit("error", message);
            return errorResult(message, "ACTION_FAILED");
        }
    }

    /**
     * Navigate to a URL.
     * Usage: agent.go('https://example.com')
     */
    private Map<String, Object> go(Object... args) {
        if (args.length < 1) {
            return errorResult("go requires url argument", "INVALID_ARGS");
        }

        String url = String.valueOf(args[0]);
        emit("cmd", "go(<code>" + url + "</code>)");

        driver.setUrl(url);

        // Re-inject after navigation
        injectAriaSnapshot();

        String title = driver.getTitle();
        emit("result", "Navigated to: " + (title != null && !title.isEmpty() ? title : url));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", driver.getUrl());
        result.put("title", title);
        return result;
    }

    /**
     * Wait for an element or condition.
     * Usage: agent.wait('e1') or agent.wait("document.title.includes('Done')")
     */
    private Map<String, Object> waitFor(Object... args) {
        if (args.length < 1) {
            return errorResult("wait requires condition argument", "INVALID_ARGS");
        }

        String condition = String.valueOf(args[0]);
        long start = System.currentTimeMillis();

        try {
            if (condition.matches("e\\d+")) {
                // Wait for element ref
                String locator = "ref:" + condition;
                driver.waitFor(locator);
            } else {
                // Wait for JS condition
                driver.waitUntil(condition);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("elapsed", System.currentTimeMillis() - start);
            return result;

        } catch (DriverException e) {
            return errorResult(e.getMessage(), "TIMEOUT");
        }
    }

    /**
     * Execute arbitrary JavaScript.
     * Usage: agent.eval("document.querySelector('#foo').textContent")
     */
    private Map<String, Object> evalJs(Object... args) {
        if (args.length < 1) {
            return errorResult("eval requires js argument", "INVALID_ARGS");
        }

        String js = String.valueOf(args[0]);

        try {
            Object scriptResult = driver.script(js);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", scriptResult);
            return result;

        } catch (Exception e) {
            return errorResult(e.getMessage(), "EVAL_FAILED");
        }
    }

    /**
     * Request user intervention.
     * Shows alert in sidebar and waits for user to click Resume.
     * Usage: agent.handoff("Please solve the CAPTCHA")
     */
    private Map<String, Object> handoff(Object... args) {
        if (args.length < 1) {
            return errorResult("handoff requires message argument", "INVALID_ARGS");
        }

        String message = String.valueOf(args[0]);

        // Re-inject in case of navigation
        injectAriaSnapshot();

        // Show handoff alert in sidebar
        driver.script("window.__karate.handoff('" + escapeJs(message) + "')");

        // Poll until user clicks Resume (check every 500ms)
        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorResult("Handoff interrupted", "INTERRUPTED");
            }

            Boolean pending = (Boolean) driver.script("window.__karate.isHandoffPending()");
            if (!Boolean.TRUE.equals(pending)) {
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - start;

        // Get user's response text
        String response = (String) driver.script("window.__karate.getHandoffResponse()");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumed", true);
        result.put("elapsed", elapsed);
        if (response != null && !response.isEmpty()) {
            result.put("response", response);
        }
        return result;
    }

    // ========== Helper Methods ==========

    private boolean performAction(String locator, String op, Object value) {
        return switch (op) {
            case "click" -> {
                driver.click(locator);
                yield true;
            }
            case "input" -> {
                driver.input(locator, String.valueOf(value));
                yield true;
            }
            case "clear" -> {
                driver.clear(locator);
                yield true;
            }
            case "focus" -> {
                driver.focus(locator);
                yield false; // focus doesn't change state
            }
            case "select" -> {
                driver.select(locator, String.valueOf(value));
                yield true;
            }
            case "check" -> {
                Element el = driver.locate(locator);
                Boolean checked = (Boolean) driver.script(locator, "_.checked");
                if (!Boolean.TRUE.equals(checked)) {
                    el.click();
                }
                yield true;
            }
            case "uncheck" -> {
                Element el = driver.locate(locator);
                Boolean checked = (Boolean) driver.script(locator, "_.checked");
                if (Boolean.TRUE.equals(checked)) {
                    el.click();
                }
                yield true;
            }
            default -> {
                // Try anyway - let browser fail if invalid
                driver.click(locator);
                yield true;
            }
        };
    }

    private void injectAriaSnapshot() {
        try {
            // Check if already injected
            Boolean exists = (Boolean) driver.script("typeof window.__karate !== 'undefined'");
            if (!Boolean.TRUE.equals(exists)) {
                driver.script(ARIA_SNAPSHOT_JS);
            }
            // Set sidebar enabled state
            driver.script("window.__karate.sidebarEnabled = " + sidebarEnabled);
        } catch (Exception e) {
            // Ignore - may fail during navigation
        }
    }

    private String buildOptionsJson(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof String) {
                sb.append("\"").append(val).append("\"");
            } else if (val instanceof Boolean || val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(val).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> argsToMap(Object[] args) {
        if (args == null || args.length == 0) {
            return Map.of();
        }
        Object first = args[0];
        if (first instanceof Map) {
            return (Map<String, Object>) first;
        }
        return Map.of();
    }

    private Map<String, Object> errorResult(String message, String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        result.put("code", code);
        return result;
    }

    // ========== Script Loading ==========

    private static String loadAriaSnapshotJs() {
        try (var is = AgentDriver.class.getResourceAsStream("aria-snapshot.js")) {
            if (is != null) {
                return new String(is.readAllBytes());
            }
        } catch (Exception e) {
            // Fallback to inline script
        }
        return getInlineAriaSnapshotJs();
    }

    /**
     * Inline fallback if resource file not found.
     * This is a minimal implementation for MVP.
     */
    private static String getInlineAriaSnapshotJs() {
        return """
            window.__karate = {
                refs: {},
                seq: 1,

                getInteractables: function(options) {
                    options = options || {};
                    this.refs = {};
                    this.seq = 1;

                    var results = [];
                    var selector = 'button, a, input, select, textarea, [role="button"], [role="link"], [tabindex]:not([tabindex="-1"])';

                    if (options.scope) {
                        var container = document.querySelector(options.scope);
                        if (!container) return results;
                        var elements = container.querySelectorAll(selector);
                    } else {
                        var elements = document.querySelectorAll(selector);
                    }

                    for (var i = 0; i < elements.length; i++) {
                        var el = elements[i];

                        // Skip hidden elements
                        if (el.offsetParent === null && el.tagName !== 'INPUT') continue;
                        if (el.getAttribute('aria-hidden') === 'true') continue;

                        // Viewport check
                        if (options.viewport !== false) {
                            var rect = el.getBoundingClientRect();
                            if (rect.bottom < 0 || rect.top > window.innerHeight ||
                                rect.right < 0 || rect.left > window.innerWidth) {
                                continue;
                            }
                        }

                        var ref = 'e' + this.seq++;
                        this.refs[ref] = el;

                        var role = this.getRole(el);
                        var name = this.getName(el);

                        results.push(role + ':' + name + '[' + ref + ']');
                    }

                    return results;
                },

                getActions: function() {
                    var actions = {};
                    for (var ref in this.refs) {
                        var el = this.refs[ref];
                        var role = this.getRole(el);
                        actions[ref] = this.getActionsForRole(role, el);
                    }
                    return actions;
                },

                getRole: function(el) {
                    var role = el.getAttribute('role');
                    if (role) return role;

                    var tag = el.tagName.toLowerCase();
                    var type = el.type ? el.type.toLowerCase() : '';

                    if (tag === 'button') return 'button';
                    if (tag === 'a') return 'link';
                    if (tag === 'select') return 'combobox';
                    if (tag === 'textarea') return 'textbox';
                    if (tag === 'input') {
                        if (type === 'checkbox') return 'checkbox';
                        if (type === 'radio') return 'radio';
                        if (type === 'submit' || type === 'button') return 'button';
                        return 'textbox';
                    }
                    return 'generic';
                },

                getName: function(el) {
                    // aria-label first
                    var label = el.getAttribute('aria-label');
                    if (label) return label;

                    // aria-labelledby
                    var labelledBy = el.getAttribute('aria-labelledby');
                    if (labelledBy) {
                        var labels = labelledBy.split(/\\s+/).map(function(id) {
                            var labelEl = document.getElementById(id);
                            return labelEl ? labelEl.textContent : '';
                        });
                        if (labels.join('').trim()) return labels.join(' ').trim();
                    }

                    // Associated label
                    if (el.id) {
                        var labelFor = document.querySelector('label[for="' + el.id + '"]');
                        if (labelFor) return labelFor.textContent.trim();
                    }

                    // Placeholder or value for inputs
                    if (el.placeholder) return el.placeholder;

                    // alt for images
                    if (el.alt) return el.alt;

                    // title
                    if (el.title) return el.title;

                    // Text content (for buttons, links)
                    var text = el.textContent || '';
                    text = text.trim().replace(/\\s+/g, ' ');
                    if (text.length > 50) text = text.substring(0, 47) + '...';
                    return text || '(unnamed)';
                },

                getActionsForRole: function(role, el) {
                    switch (role) {
                        case 'button':
                        case 'link':
                            return ['click'];
                        case 'textbox':
                            return ['input', 'clear', 'focus'];
                        case 'checkbox':
                            return ['click', 'check', 'uncheck'];
                        case 'radio':
                            return ['click'];
                        case 'combobox':
                            return ['select', 'input', 'click'];
                        default:
                            return ['click'];
                    }
                },

                resolveRef: function(ref) {
                    return this.refs[ref] || null;
                }
            };
            """;
    }

    // ========== Extension Integration ==========

    /**
     * Log to the browser sidebar.
     * @param type One of: cmd, result, error
     * @param message The message to display
     * @param ref Optional element ref to highlight
     */
    private void emit(String type, String message, String ref) {
        if (!sidebarEnabled) return;
        try {
            String refArg = ref != null ? ", '" + ref + "'" : "";
            driver.script(
                "window.__karate && window.__karate.log('" + type + "', '" +
                escapeJs(message) + "'" + refArg + ");"
            );
        } catch (Exception e) {
            // Ignore - sidebar may not be ready
        }
    }

    private void emit(String type, String message) {
        emit(type, message, null);
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ========== Accessors ==========

    public Driver getDriver() {
        return driver;
    }
}
