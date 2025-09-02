package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JavaBridgeTest extends EvalBase {

    final JavaBridge bridge = new JavaBridge();

    @Override
    Object eval(String text, String vars) {
        engine = new Engine();
        engine.setJavaBridge(bridge);
        return engine.eval(text);
    }

    @Test
    void testDev() {

    }

    @Test
    void testCall() {
        JavaClass cp = new JavaClass(bridge, "java.util.Properties");
        Object o = cp.construct(JavaBridge.EMPTY);
        JavaObject op = new JavaObject(bridge, o);
        assertEquals(0, op.call("size", JavaBridge.EMPTY));
        op.call("put", "foo", 5);
        assertEquals(5, op.call("get", "foo"));
    }

    @Test
    void testGet() {
        DemoPojo dp = new DemoPojo();
        dp.setStringValue("foo");
        dp.setIntValue(5);
        dp.setBooleanValue(true);
        JavaObject jo = new JavaObject(bridge, dp);
        assertEquals("foo", jo.get("stringValue"));
        assertEquals(5, jo.get("intValue"));
        assertEquals(true, jo.get("booleanValue"));
        NodeUtils.match(jo.toMap(), "{ stringValue: 'foo', integerArray: null, intValue: 5, instanceField: 'instance-field', booleanValue: true, doubleValue: 0.0, intArray: null }");
    }

    @Test
    void testSet() {
        DemoPojo dp = new DemoPojo();
        JavaObject jo = new JavaObject(bridge, dp);
        jo.put("stringValue", "bar");
        jo.put("intValue", 10);
        jo.put("booleanValue", true);
        assertEquals("bar", dp.getStringValue());
        assertEquals(10, dp.getIntValue());
        assertTrue(dp.isBooleanValue());
    }

    @Test
    void testSetSpecial() {
        DemoPojo dp = new DemoPojo();
        JavaObject jo = new JavaObject(bridge, dp);
        jo.put("doubleValue", 10);
        jo.put("booleanValue", Boolean.TRUE);
        assertEquals(10, dp.getDoubleValue());
        assertTrue(dp.isBooleanValue());
    }

    @Test
    void testVarArgs() {
        DemoPojo dp = new DemoPojo();
        JavaObject jo = new JavaObject(bridge, dp);
        JavaInvokable method = new JavaInvokable("varArgs", jo);
        assertEquals("foo", method.invoke(null, "foo"));
        assertEquals("bar", method.invoke(null, "foo", "bar"));
    }

    @Test
    void testMethodOverload() {
        DemoPojo dp = new DemoPojo();
        JavaObject jo = new JavaObject(bridge, dp);
        JavaInvokable method = new JavaInvokable("doWork", jo);
        assertEquals("hello", method.invoke());
        assertEquals("hellofoo", method.invoke("foo"));
        assertEquals("hellofootrue", method.invoke("foo", true));
    }

    @Test
    void testConstruct() {
        JavaClass proxy = new JavaClass(bridge, "java.util.Properties");
        Object o = proxy.construct(JavaBridge.EMPTY);
        assertEquals("java.util.Properties", o.getClass().getName());
    }

    @Test
    void testArrayLengthAndMap() {
        List<Object> list = NodeUtils.fromJson("['foo', 'bar']");
        JsArray jl = new JsArray(list);
        assertEquals(2, jl.get("length"));
    }

    @Test
    void testBytes() {
        assertEquals(3, eval("var a = 'foo'.getBytes(); a.length"));
    }

    @Test
    void testJavaInterop() {
        eval("var DemoUtils = Java.type('io.karatelabs.js.DemoUtils'); var b = DemoUtils.doWork()");
        assertEquals("hello", get("b"));
        eval("var DemoUtils = Java.type('io.karatelabs.js.DemoUtils'); var b = DemoUtils.doWork; var c = b()");
        assertEquals("hello", get("c"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo(); var c = b.doWork()");
        assertEquals("hello", get("c"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo(); b.stringValue = 'foo'; var c = b.stringValue");
        assertEquals("foo", get("c"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo('foo'); var c = b.stringValue");
        assertEquals("foo", get("c"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo('foo', 42); var c = b.stringValue; var d = b.intValue");
        assertEquals("foo", get("c"));
        assertEquals(42, get("d"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo(); var c = b.stringValue");
        assertNull(get("c"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo('foo'); b.integerArray = [1, 2]; var c = b.integerArray; var d = b.integerArray[1]");
        NodeUtils.match(get("c"), "[1, 2]");
        assertEquals(2, get("d"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo('foo'); b.intArray = [1, 2]; var c = b.intArray; var d = b.intArray[1]");
        NodeUtils.match(get("c"), "[1, 2]");
        assertEquals(2, get("d"));
        assertEquals("static-field", eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); DemoPojo.staticField"));
        assertEquals("static-field-changed", eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); DemoPojo.staticField = 'static-field-changed'; DemoPojo.staticField"));
        assertEquals("foo", eval("io.karatelabs.js.DemoPojo.staticField = 'foo'; var a = io.karatelabs.js.DemoPojo.staticField"));
        assertEquals("foo", get("a"));
        assertEquals("instance-field", eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var a = new DemoPojo(); a.instanceField"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo(); var c = b.doWork; var d = c()");
        assertEquals("hello", get("d"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo(); var c = b.doWorkFn(); var d = c(2)");
        assertEquals("2", get("d"));
        eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo('foo'); b.integerArray = [1, 2]; var c = b.doIntegerArray()");
        NodeUtils.match(get("c"), "[1, 2]");
    }

    @Test
    void testJavaInteropException() {
        try {
            eval("var DemoUtils = Java.type('io.karatelabs.js.DemoUtils'); var b = DemoUtils.doWorkException; var c = b()");
            fail("expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot invoke static method io.karatelabs.js.DemoUtils#doWorkException"));
        }
        try {
            eval("var DemoUtils = Java.type('io.karatelabs.js.DemoUtils'); var b = DemoUtils.doWorkException; var c = b().foo");
            fail("expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("expression: b() - cannot invoke static method io.karatelabs.js.DemoUtils#doWorkException: java.lang.reflect.InvocationTargetException"));
        }
        try {
            eval("var DemoPojo = Java.type('io.karatelabs.js.DemoPojo'); var b = new DemoPojo(); var c = b.doWorkException()");
            fail("expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot invoke instance method io.karatelabs.js.DemoPojo#doWorkException"));
        }
        try {
            eval("var DemoUtils = Java.type('io.karatelabs.js.DemoUtils'); var b = DemoUtils.doWorkException()");
            fail("expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot invoke static method io.karatelabs.js.DemoUtils#doWorkException"));
        }
        try {
            eval("var DemoSimpleObject = Java.type('io.karatelabs.js.DemoSimpleObject'); var b = new DemoSimpleObject(); var c = b.doWorkException()");
            fail("expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("failed"));
        }
        try {
            eval("var DemoSimpleObject = Java.type('io.karatelabs.js.DemoSimpleObject'); var b = new DemoSimpleObject(); var c = b.inner.doWorkException()");
            fail("expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("failed"));
        }
    }

    @Test
    void testJavaInteropJdk() {
        assertEquals("bar", eval("var props = new java.util.Properties(); props.put('foo', 'bar'); props.get('foo')"));
        assertEquals(new BigDecimal(123123123123L), eval("new java.math.BigDecimal(123123123123)"));
        assertEquals(String.CASE_INSENSITIVE_ORDER, eval("java.lang.String.CASE_INSENSITIVE_ORDER"));
        assertEquals("aGVsbG8=", eval("var Base64 = Java.type('java.util.Base64'); Base64.getEncoder().encodeToString('hello'.getBytes())"));
        assertInstanceOf(UUID.class, eval("java.util.UUID.randomUUID()"));
    }

}
