package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsNumberTest extends EvalBase {

    @Test
    void testConstructor() {
        assertEquals(0, eval("var a = Number()"));
        match(get("a"), "0");
        assertEquals(100, eval("var a = Number(100)"));
        match(get("a"), "100");
        assertEquals(100, eval("var a = Number('100')"));
        match(get("a"), "100");
    }

    @Test
    void testApi() {
        assertEquals("0.100", eval("var a = 0.1; a.toFixed(3)"));
    }

}
