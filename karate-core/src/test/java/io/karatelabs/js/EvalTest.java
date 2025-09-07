package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalTest extends EvalBase {

    @Test
    void testDev() {

    }

    @Test
    void testNumbers() {
        assertEquals(1, eval("1"));
        assertEquals(1, eval("1.0"));
        assertEquals(0.5d, eval(".5"));
        assertEquals(65280, eval("0x00FF00"));
        assertEquals(2000001813346120L, eval("2.00000181334612E15"));
    }

    @Test
    void testPost() {
        assertEquals(1, eval("a = 1; a++"));
        assertEquals(2, get("a"));
        assertEquals(1, eval("a = 1; a--"));
        assertEquals(0, get("a"));
        assertEquals(1, eval("a = { b: 1 }; a.b++"));
        NodeUtils.match(get("a"), "{ b: 2 }");
    }

    @Test
    void testPre() {
        assertEquals(2, eval("a = 1; ++a"));
        assertEquals(2, get("a"));
        assertEquals(0, eval("a = 1; --a"));
        assertEquals(0, get("a"));
        assertEquals(-5, eval("a = 5; -a"));
        assertEquals(5, get("a"));
        assertEquals(2, eval("a = 2; +a"));
        assertEquals(2, get("a"));
        assertEquals(3, eval("a = '3'; +a"));
        assertEquals("3", get("a"));
        assertEquals(2, eval("a = { b: 1 }; ++a.b"));
        NodeUtils.match(get("a"), "{ b: 2 }");
    }

    @Test
    void testLiterals() {
        assertNull(eval("null"));
        assertEquals(true, eval("true"));
        assertEquals(false, eval("false"));
        assertEquals("foo", eval("'foo'"));
        assertEquals("bar", eval("\"bar\""));
    }

    @Test
    void testExprList() {
        assertEquals(3, eval("1, 2, 3"));
    }

    @Test
    void testAssign() {
        assertEquals(1, eval("a = 1"));
        assertEquals(1, get("a"));
        assertEquals(2, eval("a = 2; b = a"));
        assertEquals(2, get("b"));
        assertEquals(3, eval("a = 1 + 2"));
        assertEquals(3, get("a"));
        assertEquals(2, eval("a = 1; a += 1"));
        assertEquals(2, get("a"));
        assertEquals(2, eval("a = 3; a -= 1"));
        assertEquals(2, get("a"));
        assertEquals(6, eval("a = 2; a *= 3"));
        assertEquals(6, get("a"));
        assertEquals(3, eval("a = 6; a /= 2"));
        assertEquals(3, get("a"));
        assertEquals(1, eval("a = 3; a %= 2"));
        assertEquals(1, get("a"));
        assertEquals(9, eval("a = 3; a **= 2"));
        assertEquals(9, get("a"));
        eval("var a = { foo: 'bar' }");
        match(get("a"), "{ foo: 'bar' }");
        eval("var a = { 0: 'a', 1: 'b' }");
        match(get("a"), "{ '0': 'a', '1': 'b' }");
    }

    @Test
    void testVarStatement() {
        assertNull(eval("var a"));
        assertNull(get("a"));
        assertEquals(1, eval("var a = 1"));
        assertEquals(1, get("a"));
        assertEquals(2, eval("var a, b = 2"));
        assertEquals(2, get("a"));
        assertEquals(2, get("b"));
    }

    @Test
    void testExp() {
        assertEquals(512, eval("2 ** 3 ** 2"));
        assertEquals(64, eval("(2 ** 3) ** 2"));
    }

    @Test
    void testBitwise() {
        assertEquals(3, eval("1 | 2"));
        assertEquals(7, eval("3 | 2 | 4"));
        assertEquals(20, eval("5 << 2"));
        assertEquals(4294967295L, eval("var a = -1; a >>>= 0"));
        assertEquals(1, eval("a = 5; a >>= 2"));
        assertEquals(1073741822, eval("a = -5; a >>>= 2"));
    }

    @Test
    void testLogic() {
        assertEquals(true, eval("2 > 1"));
        assertEquals(2, eval("2 || 1"));
        assertEquals("b", eval("'a' && 'b'"));
        assertEquals(true, eval("2 > 1 && 3 < 5"));
        assertEquals(true, eval("2 == '2'"));
        assertEquals(false, eval("2 === '2'"));
    }

    @Test
    void testLogicNonNumbers() {
        assertEquals(false, eval("'a' == 'b'"));
        assertEquals(false, eval("'a' === 'b'"));
        assertEquals(true, eval("'a' == 'a'"));
        assertEquals(true, eval("'a' === 'a'"));
        assertEquals(true, eval("'a' != 'b'"));
        assertEquals(true, eval("'a' !== 'b'"));
        assertEquals(false, eval("'a' != 'a'"));
        assertEquals(false, eval("'a' !== 'a'"));
    }

    @Test
    void testLogicSpecial() {
        assertEquals(false, eval("a = undefined; b = 0; a < b"));
        assertEquals(true, eval("a = ''; b = ''; a == b"));
        assertEquals(false, eval("a = 0; b = -0; a == b"));
        assertEquals(true, eval("a = 0; b = -0; a === b"));
        assertEquals(false, eval("a = 0; b = -0; 1 / a === 1 / b"));
        assertEquals(true, eval("a = Infinity; 1 / a === 0"));
        assertEquals(true, eval("a = -Infinity; 1 / a === -0"));
        assertEquals(true, eval("a = 0; 1 / a === Infinity"));
        assertEquals(true, eval("a = 0; -1 / a === -Infinity"));
        assertEquals(false, eval("a = {}; b = {}; a === b"));
        assertEquals(false, eval("a = []; b = []; a === b"));
        assertEquals(false, eval("a = {}; b = {}; a !== a && b !== b"));
        assertEquals(false, eval("a = []; b = []; a !== a && b !== b"));
        assertEquals(false, eval("!!null"));
        assertEquals(false, eval("!!NaN"));
        assertEquals(false, eval("!!undefined"));
        assertEquals(false, eval("!!''"));
        assertEquals(false, eval("!!0"));
        assertEquals(true, eval("a = null; b = undefined; a == b"));
        assertEquals(false, eval("a = null; b = undefined; a === b"));
        assertEquals(true, eval("a = undefined; b = null; a == b"));
        assertEquals(false, eval("a = undefined; b = null; a === b"));
        assertEquals(true, eval("a = null; b = null; a == b"));
        assertEquals(true, eval("a = null; b = null; a === b"));
        assertEquals(true, eval("a = undefined; b = undefined; a == b"));
        assertEquals(true, eval("a = undefined; b = undefined; a === b"));
        assertEquals(true, eval("a = null; b = null; a == b"));
        assertEquals(true, eval("a = null; b = undefined; a == b"));
        assertEquals(false, eval("a = NaN; b = NaN; a == b"));
        assertEquals(false, eval("a = NaN; b = NaN; a === b"));
        assertEquals(true, eval("a = NaN; b = NaN; a != b"));
        assertEquals(true, eval("a = NaN; b = NaN; a !== b"));
        assertEquals(true, eval("a = NaN; b = NaN; a !== a && b !== b"));
        assertEquals(false, eval("a = NaN; b = NaN; a < b"));
        assertEquals(false, eval("a = NaN; b = NaN; a > b"));
        assertEquals(false, eval("a = NaN; b = NaN; a <= b"));
        assertEquals(false, eval("a = NaN; b = NaN; a >= b"));
    }

    @Test
    void testLogicShortCircuit() {
        assertEquals(null, eval("var a = {}; a.b && a.b.c"));
        assertEquals(0, eval("var a = { b: 0 }; a.b && a.b.c"));
        assertEquals(2, eval("var a = { b: 1, c: 2 }; a.b && a.c"));
        assertEquals(1, eval("var a = { b: 1 }; a.b || a.x.y"));
        assertEquals(2, eval("var a = { b: 0, c: 2 }; a.b || a.c"));
        assertEquals(0, eval("var a = { b: undefined, c: 0 }; a.b || a.c"));
        assertEquals(2, eval("var a = { b: undefined, c: 2 }; a.b || a.c"));
    }

    @Test
    void testUnary() {
        assertEquals(true, eval("!false"));
        assertEquals(-6, eval("~5"));
        assertEquals(2, eval("~~(2.7)"));
    }

    @Test
    void testJsonApi() {
        assertEquals("{\"a\":\"b\"}", eval("JSON.stringify({a:'b'})"));
        assertEquals("{\"a\":\"b\"}", eval("JSON.stringify({a:'b',c:'d'}, ['a'])"));
        assertEquals(Map.of("a", "b"), eval("JSON.parse('{\"a\":\"b\"}')"));
    }

    @Test
    void testParseInt() {
        assertEquals(42, eval("parseInt('42')"));
        assertEquals(42, eval("parseInt('042')"));
        assertEquals(42, eval("parseInt('42px')"));
        assertEquals(3, eval("parseInt('3.14')"));
        assertEquals(255, eval("parseInt('0xFF')"));
        assertEquals(Double.NaN, eval("parseInt('abc')"));
        // assertEquals(5, eval("parseInt('101', 2)")); TODO
    }

    @Test
    void testParseFloat() {
        assertEquals( 3.14, eval("parseFloat('3.14')"));
        assertEquals( 42, eval("parseFloat('42')"));
        assertEquals( 42.99, eval("parseFloat('42.99px')"));
        assertEquals(Double.NaN, eval("parseFloat('abc')"));
        // assertEquals( 1000, eval("parseFloat('1e3')")); TODO
    }

    @Test
    void testIfStatement() {
        eval("if (true) a = 1");
        assertEquals(1, get("a"));
        eval("if (false) a = 1");
        assertNull(get("a"));
        eval("if (false) a = 1; else a = 2");
        assertEquals(2, get("a"));
        eval("a = 1; if (a) b = 2");
        assertEquals(2, get("b"));
        eval("a = 0; if (a) b = 2");
        assertNull(get("b"));
        eval("a = ''; if (a) b = 1; else b = 2");
        assertEquals(2, get("b"));
        assertEquals(true, eval("if (false) { false } else { true }"));
        assertEquals(false, eval("if (false) { false } else { false }"));
    }

    @Test
    void testForLoop() {
        eval("var a = []; for (var i = 0; i < 3; i++) a.push(i)");
        match(get("a"), "[0, 1, 2]");
        eval("var a = []; for (var i = 0; i < 3; i++) { if (i == 1) break; a.push(i) }");
        match(get("a"), "[0]");
        eval("var a = []; for (var i = 0; i < 3; i++) { if (i == 1) continue; a.push(i) }");
        match(get("a"), "[0, 2]");
    }

    @Test
    void testForInLoop() {
        eval("var a = []; for (var x in {a: 1, b: 2, c: 3}) a.push(x)");
        match(get("a"), "['a', 'b', 'c']");
        eval("var a = []; for (var x in {a: 1, b: 2, c: 3}) { if (x == 'b') break; a.push(x) }");
        match(get("a"), "['a']");
        eval("var a = []; for (var x in {a: 1, b: 2, c: 3}) { if (x == 'b') continue; a.push(x) }");
        match(get("a"), "['a', 'c']");
        eval("var a = []; for (var x in [1, 2, 3]) a.push(x)");
        match(get("a"), "['0', '1', '2']");
        eval("var a = []; for (var x in [1, 2, 3]) { if (x == '1') break; a.push(x) }");
        match(get("a"), "['0']");
        eval("var a = []; for (var x in [1, 2, 3]) { if (x == '1') continue; a.push(x) }");
        match(get("a"), "['0', '2']");
    }

    @Test
    void testForOfLoop() {
        eval("var a = []; var x; for (x of {a: 1, b: 2, c: 3}) a.push(x)");
        match(get("a"), "[1, 2, 3]");
        eval("var a = []; var x; for (x of {a: 1, b: 2, c: 3}) { if (x == 2) break; a.push(x) }");
        match(get("a"), "[1]");
        eval("var a = []; var x; for (x of {a: 1, b: 2, c: 3}) { if (x == 2) continue; a.push(x) }");
        match(get("a"), "[1, 3]");
        eval("var a = []; var x; for (x of [1, 2, 3]) a.push(x)");
        match(get("a"), "[1, 2, 3]");
        eval("var a = []; var x; for (x of [1, 2, 3]) { if (x == 2) break; a.push(x) }");
        match(get("a"), "[1]");
        eval("var a = []; var x; for (x of [1, 2, 3]) { if (x == 2) continue; a.push(x) }");
        match(get("a"), "[1, 3]");
        eval("var a = []; for (const x of {a: 1, b: 2, c: 3}) a.push(x)");
        match(get("a"), "[1, 2, 3]");
    }

    @Test
    void testWhileLoop() {
        eval("var i = 0; var a = []; while (i < 3) { a.push(i); i++ }");
        match(get("a"), "[0, 1, 2]");
        eval("var i = 0; var a = []; while (i < 3) { if (i == 1) break; a.push(i); i++ }");
        match(get("a"), "[0]");
        eval("var i = 0; var a = []; while (i < 3) { if (i == 1) { i++; continue }; a.push(i); i++ }");
        match(get("a"), "[0, 2]");
        // ensure extra semicolons in blocks are ignored
        eval("var i = 0; var a = []; while (i < 3) { if (i == 1) { i++;; continue;; };; a.push(i);; i++;; };;");
        match(get("a"), "[0, 2]");
    }

    @Test
    void testDoWhileLoop() {
        eval("var i = 0; var a = []; do { a.push(i); i++ } while (i < 3)");
        match(get("a"), "[0, 1, 2]");
        eval("var i = 0; var a = []; do { if (i == 1) break; a.push(i); i++ } while (i < 3)");
        match(get("a"), "[0]");
        eval("var i = 0; var a = []; do { if (i == 1) { i++; continue }; a.push(i); i++ } while (i < 3)");
        match(get("a"), "[0, 2]");
    }

    @Test
    void testTernary() {
        eval("a = true ? 1 : 2");
        assertEquals(1, get("a"));
        eval("a = false ? 1 : 2");
        assertEquals(2, get("a"));
        eval("a = 5; b = a > 3 ? 'foo' : 4 + 5");
        assertEquals("foo", get("b"));
        eval("a = 5; b = a < 3 ? 'foo' : 4 + 5");
        assertEquals(9, get("b"));
    }

    @Test
    void testTypeOf() {
        assertEquals("string", eval("typeof 'foo'"));
        assertEquals("function", eval("var a = function(){}; typeof a"));
        assertEquals("object", eval("typeof new Error('foo')"));
        assertEquals(true, eval("typeof 'foo' === 'string'"));
        assertEquals("undefined", eval("typeof bar"));
    }

    @Test
    void testTryCatch() {
        eval("var a; try { throw 'foo' } catch (e) { a = e }");
        assertEquals("foo", get("a"));
        eval("var a, b; try { throw 'foo' } catch (e) { a = e; if (true) return; a = null } finally { b = 2 }");
        assertEquals("foo", get("a"));
        assertEquals(2, get("b"));
        eval("var a; try { } finally { a = 3 }");
        assertEquals(3, get("a"));
        eval("var a; try { throw 'foo' } catch { a = 'bar' }");
        assertEquals("bar", get("a"));
    }

    @Test
    void testBracketExpression() {
        assertNull(eval("var foo = {}; foo['bar']"));
    }

    @Test
    void testDotExpressionUndefined() {
        assertNull(eval("var foo = {}; foo.bar"));
    }

    @Test
    void testDotExpressionFailure() {
        try {
            eval("foo.bar");
            fail("error expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot read properties of undefined (reading 'bar')"));
        }
        try {
            eval("var obj = { a: null }; obj.a.b");
            fail("error expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot read properties of null (reading 'b')"));
        }
        try {
            eval("var obj = { }; obj.a.b");
            fail("error expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot read properties of undefined (reading 'b')"));
        }
    }

    @Test
    void testDotExpressionOptional() {
        assertNull(eval("var foo = null; foo?.bar"));
        assertNull(eval("var foo = undefined; foo?.bar"));
        assertNull(eval("var foo; foo?.bar"));
        assertEquals("baz", eval("var foo = { bar: 'baz' }; foo?.bar"));
        assertEquals(42, eval("var obj = { num: 42 }; obj?.num"));
        assertNull(eval("var obj = null; obj?.a?.b?.c"));
        assertNull(eval("var obj = { a: null }; obj?.a?.b?.c"));
        assertNull(eval("var obj = { a: { b: null } }; obj?.a?.b?.c"));
        assertEquals("deep", eval("var obj = { a: { b: { c: 'deep' } } }; obj?.a?.b?.c"));
        assertEquals("value", eval("var obj = { a: { b: 'value' } }; obj?.a.b"));
        try {
            eval("var obj = { a: null }; obj?.a.b");
            fail("error expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("cannot read properties of null (reading 'b')"));
        }
        // bracket
        assertNull(eval("var obj = null; obj?.['key']"));
        assertEquals("value", eval("var obj = { key: 'value' }; obj?.['key']"));
        assertEquals("dynamic", eval("var obj = { foo: 'dynamic' }; var key = 'foo'; obj?.[key]"));
        // function call
        assertNull(eval("var obj = null; obj?.method()"));
        assertEquals("called", eval("var obj = { method: function() { return 'called'; } }; obj?.method()"));
        assertNull(eval("var obj = { method: null }; obj?.method?.()"));
    }

    @Test
    void testThrow() {
        try {
            eval("function a(b){ b() }; a(() => { throw new Error('foo') })");
            fail("error expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("foo"));
        }
    }

    @Test
    void testThrowFunction() {
        try {
            eval("function a(b){ this.bar = 'baz'; b() }; a(function(){ throw new Error('foo') })");
            fail("error expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("foo"));
            JsFunction fn = (JsFunction) get("a");
            assertEquals("baz", fn.get("bar"));
        }
    }

    @Test
    void testSwitch() {
        eval("var a = 2; var b; switch (a) { case 2: b = 1 }");
        assertEquals(1, get("b"));
        eval("var a = 2; var b; switch (a) { case 1: b = 1; break; case 2: b = 2; break }");
        assertEquals(2, get("b"));
        eval("var a = 2; var b; switch (a) { case 1: b = 1; break; default: b = 2 }");
        assertEquals(2, get("b"));
        eval("var a = 1; var b; switch (a) { case 1: b = 1; default: b = 2 }");
        assertEquals(2, get("b"));
    }

    @Test
    void testLetAndConstBasic() {
        assertEquals(1, eval("let a = 1; a"));
        assertEquals(1, get("a"));
        assertEquals(2, eval("let b = 1; b = 2"));
        assertEquals(2, get("b"));
        assertEquals(1, eval("const a = 1; a"));
        assertEquals(1, get("a"));
    }

}
