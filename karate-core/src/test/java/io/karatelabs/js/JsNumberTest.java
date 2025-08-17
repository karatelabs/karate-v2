package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsNumberTest extends EvalBase {

    @Test
    void testNumberConstructor() {
        assertEquals(0, eval("Number()"));
        assertEquals(100, eval("Number(100)"));
        assertEquals(100, eval("Number('100')"));
    }

    @Test
    void testumberMethods() {
        assertEquals("0.100", eval("var a = 0.1; a.toFixed(3)"));
    }

}
