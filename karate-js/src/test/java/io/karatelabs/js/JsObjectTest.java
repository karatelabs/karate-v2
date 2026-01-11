package io.karatelabs.js;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsObjectTest extends EvalBase {

    @Test
    void testDev() {

    }

    @Test
    void testObjectNumericBracketAccess() {
        // Test that objects can be accessed with numeric indices using bracket notation
        // In standard JavaScript, obj[3] is equivalent to obj['3'] for objects
        eval("var obj = {1: 0.4, 2: 0.6, 3: 0.75, 4: 0.85, 5: 0.95, 6: 1};"
                + "var value = obj[3];");
        assertEquals(0.75, get("value"));

        // Also test with string keys - should work the same way
        eval("var obj2 = {'1': 0.4, '2': 0.6, '3': 0.75};"
                + "var value2 = obj2[3];");
        assertEquals(0.75, get("value2"));

        // Test that both numeric and string access return the same value
        eval("var obj3 = {5: 'hello'};"
                + "var numAccess = obj3[5];"
                + "var strAccess = obj3['5'];");
        assertEquals("hello", get("numAccess"));
        assertEquals("hello", get("strAccess"));

        // Test setting with numeric indices
        eval("var obj4 = {};"
                + "obj4[1] = 'one';"
                + "obj4[2] = 'two';"
                + "var val1 = obj4[1];"
                + "var val2 = obj4['2'];");
        assertEquals("one", get("val1"));
        assertEquals("two", get("val2"));
        NodeUtils.match(get("obj4"), "{ '1': 'one', '2': 'two' }");
    }

    @Test
    void testObject() {
        matchEval("{}", "{}");
        matchEval("{ a: 1 }", "{ a: 1 }");
        matchEval("{ a: 1, b: 2 }", "{ a: 1, b: 2 }");
        matchEval("{ 'a': 1 }", "{ a: 1 }");
        matchEval("{ \"a\": 1 }", "{ a: 1 }");
        matchEval("{ a: 'b' }", "{ a: 'b' }");
        matchEval("{ a: true }", "{ a: true }");
        matchEval("{ a: (1 + 2) }", "{ a: 3 }");
        matchEval("{ a: b }", "{ a: 5 }", "{ b: 5 }");
    }

    @Test
    void testObjectMutation() {
        assertEquals(3, eval("a.b = 1 + 2", "{ a: {} }"));
        NodeUtils.match(get("a"), "{ b: 3 }");
        eval("var a = { foo: 1, bar: 2 }; delete a.foo");
        NodeUtils.match(get("a"), "{ bar: 2 }");
        eval("var a = { foo: 1, bar: 2 }; delete a['bar']");
        NodeUtils.match(get("a"), "{ foo: 1 }");
    }

    @Test
    void testObjectProp() {
        assertEquals(2, eval("a = { b: 2 }; a.b"));
        assertEquals(2, eval("a = { b: 2 }; a['b']"));
    }

    @Test
    void testObjectPropReservedWords() {
        assertEquals(2, eval("a = { 'null': 2 }; a.null"));
    }

    @Test
    void testObjectFunction() {
        assertEquals("foo", eval("a = { b: function(){ return this.c }, c: 'foo' }; a.b()"));
    }

    @Test
    void testObjectEnhanced() {
        eval("a = 1; b = { a }");
        NodeUtils.match(get("b"), "{ a: 1 }");
    }

    @Test
    void testObjectPrototype() {
        String js = "function Dog(name){ this.name = name }; var dog = new Dog('foo');"
                + " Dog.prototype.toString = function(){ return this.name }; ";
        assertEquals("foo", eval(js + "dog.toString()"));
        assertEquals(true, eval(js + "dog.constructor === Dog"));
        assertEquals(true, eval(js + "dog instanceof Dog"));
        assertEquals(true, eval(js + "dog instanceof dog.constructor"));
    }

    @Test
    void testPrototypePropertySetToNull() {
        // Edge case: when a property is explicitly set to null on child,
        // it should NOT continue looking up the prototype chain
        String js = "function Animal() { this.sound = 'generic' };"
                + "function Cat() { this.sound = null };" // explicitly set to null
                + "Cat.prototype = new Animal();"
                + "var cat = new Cat();";
        assertNull(eval(js + "cat.sound")); // should be null, not 'generic'

        // Test setting property to null AFTER object creation (via prototype chain)
        String js2 = "function Parent() { this.value = 'parent' };"
                + "function Child() {}"
                + "Child.prototype = new Parent();"
                + "var child = new Child();"
                + "child.value = null;"; // set to null after creation
        assertNull(eval(js2 + "child.value")); // should be null, not 'parent'

        // Test using bracket notation access
        String js3 = "function Base() { this.prop = 'base' };"
                + "function Derived() { this.prop = null };"
                + "Derived.prototype = new Base();"
                + "var obj = new Derived();";
        assertNull(eval(js3 + "obj['prop']")); // bracket access should also return null
    }

    @Test
    void testConstructorThis() {
        eval("function Dog(name) { this.name = name }; var dog = new Dog('Fido'); var name = dog.name");
        assertEquals("Fido", get("name"));
    }

    @Test
    void testObjectApi() {
        matchEval("Object.keys({ a: 1, b: 2 })", "['a', 'b']");
        matchEval("Object.values({ a: 1, b: 2 })", "[1, 2]");
        matchEval("Object.entries({ a: 1, b: 2 })", "[['a', 1], ['b', 2]]");
        matchEval("Object.assign({}, { a: 1 }, { b: 2 })", "{ a: 1, b: 2 }");
        matchEval("Object.assign({ a: 0 }, { a: 1, b: 2 })", "{ a: 1, b: 2 }");
        matchEval("Object.fromEntries([['a', 1], ['b', 2]])", "{ a: 1, b: 2 }");
        matchEval("Object.fromEntries(Object.entries({ a: 1, b: 2 }))", "{ a: 1, b: 2 }");
        assertEquals(true, eval("Object.is(42, 42)"));
        assertEquals(true, eval("Object.is('foo', 'foo')"));
        assertEquals(false, eval("Object.is('foo', 'bar')"));
        assertEquals(false, eval("Object.is(null, undefined)"));
        assertEquals(true, eval("Object.is(null, null)"));
        assertEquals(true, eval("Object.is(NaN, NaN)"));
        // assertEquals(false, eval("Object.is(0, -0)"));
        matchEval("{}.valueOf()", "{}");
        matchEval("var obj = { a: 1, b: 2 }; obj.valueOf()", "{ a: 1, b: 2 }");
        matchEval("var x = { a: 0.5 }; Object.entries(x).map(y => [y[0], y[1], typeof y[1]])", "[[a, 0.5, number]]");
    }

    @Test
    void testObjectSpread() {
        matchEval("var obj1 = {a: 1, b: 2}; var obj2 = {...obj1}; obj2", "{ a: 1, b: 2 }");
        matchEval("var obj1 = {a: 1, b: 2}; var obj2 = {...obj1, b: 3}; obj2", "{ a: 1, b: 3 }");
        matchEval("var obj1 = {a: 1}; var obj2 = {b: 2}; var obj3 = {...obj1, ...obj2}; obj3", "{ a: 1, b: 2 }");
        matchEval("var obj1 = {a: 1, b: 2}; var obj2 = {b: 3, c: 4}; var obj3 = {...obj1, ...obj2}; obj3", "{ a: 1, b: 3, c: 4 }");
        matchEval("var obj1 = {a: 1, b: 2}; var obj2 = {b: 3, c: 4}; var obj3 = {...obj2, ...obj1}; obj3", "{ b: 2, c: 4, a: 1 }");
    }

    @Test
    void testPrototypeChainBasic() {
        // Basic prototype chain: instance inherits from constructor's prototype
        assertEquals(42, eval("function Foo() {}; Foo.prototype.bar = 42; var f = new Foo(); f.bar"));
    }

    @Test
    void testPrototypeChainInstanceOf() {
        // instanceof should work with prototype chain
        assertEquals(true, eval("function Foo() {}; var f = new Foo(); f instanceof Foo"));
    }

    @Test
    void testPrototypeChainProtoGetter() {
        // __proto__ getter should return the prototype delegate
        assertEquals(true, eval("function Foo() {}; var f = new Foo(); f.__proto__ === Foo.prototype"));
    }

    @Test
    void testPrototypeChainGetPrototypeOf() {
        // Object.getPrototypeOf should return the prototype delegate
        assertEquals(true, eval("function Foo() {}; var f = new Foo(); Object.getPrototypeOf(f) === Foo.prototype"));
    }

    @Test
    void testPrototypeChainInheritance() {
        // Prototype chain inheritance via Object.create
        assertEquals("sound", eval(
                "function Animal() {}; Animal.prototype.speak = function() { return 'sound'; };"
                        + "function Dog() {}; Dog.prototype = Object.create(Animal.prototype);"
                        + "var d = new Dog(); d.speak()"));
    }

    @Test
    void testPrototypeChainOwnPropertyShadows() {
        // Own property should shadow prototype property
        assertEquals(1, eval("function Foo() { this.bar = 1; }; Foo.prototype.bar = 2; var f = new Foo(); f.bar"));
    }

}
