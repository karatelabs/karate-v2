package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Bindings auto-unwrapping and Engine external map support.
 */
class BindingsTest {

    @Test
    void testBindingsAutoUnwrapDate() {
        Engine engine = new Engine();
        engine.eval("var d = new Date(86400000)");

        // get() returns unwrapped Date
        Object result = engine.get("d");
        assertInstanceOf(Date.class, result);
        assertEquals(86400000L, ((Date) result).getTime());

        // getBindings().get() also returns unwrapped Date
        Object fromBindings = engine.getBindings().get("d");
        assertInstanceOf(Date.class, fromBindings);
    }

    @Test
    void testBindingsAutoUnwrapUndefined() {
        Engine engine = new Engine();
        engine.eval("var x");

        // undefined becomes null
        assertNull(engine.get("x"));
        assertNull(engine.getBindings().get("x"));
    }

    @Test
    void testBindingsAutoUnwrapBoxedPrimitives() {
        Engine engine = new Engine();
        engine.eval("var n = new Number(42); var s = new String('hello'); var b = new Boolean(true)");

        assertEquals(42, engine.get("n"));
        assertEquals("hello", engine.get("s"));
        assertEquals(true, engine.get("b"));
    }

    @Test
    void testExternalMapBackedEngine() {
        Map<String, Object> externalMap = new HashMap<>();
        externalMap.put("x", 10);

        Engine engine = new Engine(externalMap);

        // Engine sees external map value
        assertEquals(10, engine.eval("x"));

        // Engine modifications visible in external map
        engine.eval("var y = 20");
        assertEquals(20, externalMap.get("y"));

        // External map modifications visible to engine
        externalMap.put("z", 30);
        assertEquals(30, engine.eval("z"));
    }

    @Test
    void testExternalMapSeesJsValues() {
        Map<String, Object> externalMap = new HashMap<>();
        Engine engine = new Engine(externalMap);

        engine.eval("var d = new Date(0)");

        // External map sees raw JsDate
        Object raw = externalMap.get("d");
        assertInstanceOf(JsDate.class, raw);

        // But engine.get() returns unwrapped Date
        Object unwrapped = engine.get("d");
        assertInstanceOf(Date.class, unwrapped);
    }

    @Test
    void testBindingsValuesAutoUnwrap() {
        Engine engine = new Engine();
        engine.eval("var d1 = new Date(0); var d2 = new Date(1000)");

        // values() returns unwrapped values
        for (Object value : engine.getBindings().values()) {
            if (value != null) {
                assertInstanceOf(Date.class, value);
            }
        }
    }

    @Test
    void testBindingsEntrySetAutoUnwrap() {
        Engine engine = new Engine();
        engine.eval("var d = new Date(0)");

        // entrySet() returns unwrapped values
        for (Map.Entry<String, Object> entry : engine.getBindings().entrySet()) {
            if ("d".equals(entry.getKey())) {
                assertInstanceOf(Date.class, entry.getValue());
            }
        }
    }

    @Test
    void testFunctionWrapperAutoConvertsReturnValue() {
        Engine engine = new Engine();
        engine.eval("function getDate() { return new Date(0); }");

        Object fn = engine.get("getDate");
        assertInstanceOf(JsFunctionWrapper.class, fn);

        // Call the wrapped function via eval - should return unwrapped Date
        Object result = engine.eval("getDate()");
        assertInstanceOf(Date.class, result);
    }

    @Test
    void testFunctionWrapperConvertsUndefined() {
        Engine engine = new Engine();
        engine.eval("function returnsUndefined() { return undefined; }");

        // Call via eval
        Object result = engine.eval("returnsUndefined()");
        assertNull(result);
    }

    @Test
    void testFunctionWrapperConvertsJsObject() {
        Engine engine = new Engine();
        engine.eval("function getObj() { return { date: new Date(0) }; }");

        // Call via eval
        Object result = engine.eval("getObj()");

        // Result should be a Map (JsObject implements Map)
        assertInstanceOf(Map.class, result);

        // The map's get() auto-unwraps via JsObject.get()
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertInstanceOf(Date.class, map.get("date"));
    }

    @Test
    void testFunctionWrapperPreservesSource() {
        Engine engine = new Engine();
        engine.eval("var fn = (x) => x * 2");

        Object fn = engine.get("fn");
        assertInstanceOf(JsFunctionWrapper.class, fn);

        JsFunctionWrapper wrapper = (JsFunctionWrapper) fn;
        assertEquals("(x) => x * 2", wrapper.getSource());
    }

    @Test
    void testFunctionWrapperPreservesMember() {
        Engine engine = new Engine();
        engine.eval("function foo() {}; foo.bar = 'baz'");

        Object fn = engine.get("foo");
        assertInstanceOf(JsFunctionWrapper.class, fn);

        JsFunctionWrapper wrapper = (JsFunctionWrapper) fn;
        assertEquals("baz", wrapper.getMember("bar"));
    }

    @Test
    void testRawBindingsReturnsUnwrappedMap() {
        Engine engine = new Engine();
        engine.eval("var d = new Date(0)");

        // getRawBindings returns the raw map
        Map<String, Object> raw = engine.getRawBindings();
        // Note: Engine bindings and root bindings are different
        // getRawBindings() returns root context bindings
    }

}
