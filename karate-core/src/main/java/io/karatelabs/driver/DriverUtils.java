package io.karatelabs.driver;

import io.karatelabs.js.Args;
import io.karatelabs.js.Engine;
import io.karatelabs.js.JavaCallable;

import java.util.Map;

public class DriverUtils {

    /**
     * Bind driver action methods as global functions
     * This allows writing `* click('#button')` instead of `* driver.click('#button')`.
     */
    @SuppressWarnings("unchecked")
    public static void bindJsHelpers(Engine engine, Driver driver) {

        // Element actions (return Element for chaining)
        engine.putRootBinding("click", Args.invoke(args -> driver.click(args[0].toString())));
        engine.putRootBinding("input", Args.invoke(args ->
                driver.input(args[0].toString(), args.length > 1 ? args[1].toString() : "")));
        engine.putRootBinding("clear", Args.invoke(args -> driver.clear(args[0].toString())));
        engine.putRootBinding("focus", Args.invoke(args -> driver.focus(args[0].toString())));
        engine.putRootBinding("scroll", Args.invoke(args -> driver.scroll(args[0].toString())));
        engine.putRootBinding("highlight", Args.invoke(args -> driver.highlight(args[0].toString())));
        engine.putRootBinding("select", Args.invoke(args -> {
            String locator = args[0].toString();
            Object value = args.length > 1 ? args[1] : null;
            if (value instanceof Number n) {
                return driver.select(locator, n.intValue());
            }
            return driver.select(locator, value != null ? value.toString() : "");
        }));
        // Element state (return primitives)
        engine.putRootBinding("text", Args.invoke(args -> driver.text(args[0].toString())));
        engine.putRootBinding("html", Args.invoke(args -> driver.html(args[0].toString())));
        engine.putRootBinding("value", Args.invoke(args -> {
            if (args.length == 1) {
                // Getter: value('#input')
                return driver.value(args[0].toString());
            } else {
                // Setter: value('#input', 'text')
                return driver.value(args[0].toString(), args[1].toString());
            }
        }));
        engine.putRootBinding("attribute", Args.invoke(args ->
                driver.attribute(args[0].toString(), args[1].toString())));
        engine.putRootBinding("exists", Args.invoke(args -> driver.exists(args[0].toString())));
        engine.putRootBinding("enabled", Args.invoke(args -> driver.enabled(args[0].toString())));
        engine.putRootBinding("position", Args.invoke(args -> driver.position(args[0].toString())));

        // Wait methods
        engine.putRootBinding("waitFor", Args.invoke(args -> driver.waitFor(args[0].toString())));
        engine.putRootBinding("waitForText", Args.invoke(args ->
                driver.waitForText(args[0].toString(), args[1].toString())));
        engine.putRootBinding("waitForEnabled", Args.invoke(args -> driver.waitForEnabled(args[0].toString())));
        engine.putRootBinding("waitForUrl", Args.invoke(args -> driver.waitForUrl(args[0].toString())));
        engine.putRootBinding("waitUntil", Args.invoke(args -> {
            if (args.length == 1) {
                // waitUntil(expression) - wait for JS expression to be truthy
                return driver.waitUntil(args[0].toString());
            } else {
                // waitUntil(locator, expression) - wait for element expression
                return driver.waitUntil(args[0].toString(), args[1].toString());
            }
        }));

        // Locators
        engine.putRootBinding("locate", Args.invoke(args -> driver.locate(args[0].toString())));
        engine.putRootBinding("locateAll", Args.invoke(args -> driver.locateAll(args[0].toString())));
        engine.putRootBinding("optional", Args.invoke(args -> driver.optional(args[0].toString())));

        // Frame switching
        engine.putRootBinding("switchFrame", Args.invoke(args -> {
            Object arg = args.length > 0 ? args[0] : null;
            if (arg == null) {
                driver.switchFrame((String) null);
            } else if (arg instanceof Number n) {
                driver.switchFrame(n.intValue());
            } else {
                driver.switchFrame(arg.toString());
            }
            return null;
        }));

        // Page/Tab switching
        engine.putRootBinding("switchPage", Args.invoke(args -> {
            Object arg = args[0];
            if (arg instanceof Number n) {
                driver.switchPage(n.intValue());
            } else {
                driver.switchPage(arg.toString());
            }
            return null;
        }));
        engine.putRootBinding("getPages", Args.invoke(args -> driver.getPages()));

        // Script execution
        engine.putRootBinding("script", Args.invoke(args -> {
            if (args.length == 1) {
                // Single arg: can be String or JsFunction (arrow function)
                return driver.script(args[0]);
            } else {
                // Two args: locator and expression (String or JsFunction)
                return driver.script(args[0].toString(), args[1]);
            }
        }));
        engine.putRootBinding("scriptAll", Args.invoke(args -> driver.scriptAll(args[0].toString(), args[1])));

        // Navigation
        engine.putRootBinding("refresh", Args.invoke(() -> {
            driver.refresh();
            return null;
        }));
        engine.putRootBinding("back", Args.invoke(() -> {
            driver.back();
            return null;
        }));
        engine.putRootBinding("forward", Args.invoke(() -> {
            driver.forward();
            return null;
        }));

        // Screenshots
        engine.putRootBinding("screenshot", Args.invoke(args -> {
            if (args.length == 0) {
                return driver.screenshot();
            } else if (args[0] instanceof Boolean b) {
                return driver.screenshot(b);
            }
            return driver.screenshot();
        }));

        // Cookies
        engine.putRootBinding("clearCookies", Args.invoke(args -> {
            driver.clearCookies();
            return null;
        }));
        engine.putRootBinding("deleteCookie", Args.invoke(args -> {
            driver.deleteCookie(args[0].toString());
            return null;
        }));


        engine.putRootBinding("cookie", Args.invoke(args -> {
            Object arg = args[0];
            if (arg instanceof String s) {
                return driver.cookie(s);
            } else if (arg instanceof Map) {
                driver.cookie((Map<String, Object>) arg);
                return null;
            }
            return null;
        }));
        // Dialog handling
        engine.putRootBinding("dialog", Args.invoke(args -> {
            boolean accept = args.length > 0 && Boolean.TRUE.equals(args[0]);
            if (args.length > 1 && args[1] != null) {
                driver.dialog(accept, args[1].toString());
            } else {
                driver.dialog(accept);
            }
            return null;
        }));

        // Dialog handler registration (wraps JsCallable to DialogHandler)
        engine.putRootBinding("onDialog", Args.call((ctx, args) -> {
            if (args.length == 0 || args[0] == null) {
                driver.onDialog(null);
            } else if (args[0] instanceof JavaCallable jsHandler) {
                driver.onDialog(dialog -> {
                    jsHandler.call(ctx, dialog);
                });
            }
            return null;
        }));

        // Mouse and keys
        engine.putRootBinding("mouse", Args.invoke(args -> {
            if (args.length == 0) {
                return driver.mouse();
            } else if (args.length == 1) {
                return driver.mouse(args[0].toString());
            } else {
                return driver.mouse((Number) args[0], (Number) args[1]);
            }
        }));
        engine.putRootBinding("keys", Args.invoke(driver::keys));

        // Key constants (e.g., Key.ENTER, Key.TAB)
        engine.putRootBinding("Key", new io.karatelabs.js.JavaType(io.karatelabs.driver.Keys.class));

        // Utility: delay/sleep (milliseconds)
        engine.putRootBinding("delay", Args.invoke(args -> {
            int millis = args.length > 0 ? ((Number) args[0]).intValue() : 0;
            if (millis > 0) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        }));
    }

}
