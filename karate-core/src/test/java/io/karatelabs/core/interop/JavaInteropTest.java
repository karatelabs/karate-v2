package io.karatelabs.core.interop;

import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.core.TestUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.karatelabs.core.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Java interop via Java.type() and related features.
 */
class JavaInteropTest {

    @Test
    void testJavaTypeAndNewPojo() {
        // Test Java.type() to load a class and use new to create an instance
        ScenarioRuntime sr = run("""
            * def Pojo = Java.type('io.karatelabs.core.interop.SimplePojo')
            * def pojo = new Pojo()
            * json jsonVar = pojo
            * match jsonVar == { foo: null, bar: 0 }
            """);
        assertPassed(sr);
    }

    @Test
    void testPojoSetAndGet() {
        // Test setting and getting POJO properties
        ScenarioRuntime sr = run("""
            * def Pojo = Java.type('io.karatelabs.core.interop.SimplePojo')
            * def pojo = new Pojo()
            * pojo.foo = 'hello'
            * pojo.bar = 42
            * match pojo.foo == 'hello'
            * match pojo.bar == 42
            """);
        assertPassed(sr);
    }

    @Test
    void testPojoToJson() {
        // Test converting POJO to JSON
        ScenarioRuntime sr = run("""
            * def Pojo = Java.type('io.karatelabs.core.interop.SimplePojo')
            * def pojo = new Pojo()
            * pojo.foo = 'test'
            * pojo.bar = 5
            * json jsonVar = pojo
            * match jsonVar == { foo: 'test', bar: 5 }
            """);
        assertPassed(sr);
    }

    @Test
    void testPojoToXml() {
        // Test converting POJO to XML
        ScenarioRuntime sr = run("""
            * def Pojo = Java.type('io.karatelabs.core.interop.SimplePojo')
            * def pojo = new Pojo()
            * xml xmlVar = pojo
            * match xmlVar == <root><foo></foo><bar>0</bar></root>
            """);
        assertPassed(sr);
    }

    @Test
    void testKarateToBean() {
        // Test karate.toBean() to create a POJO from JSON
        ScenarioRuntime sr = run("""
            * def className = 'io.karatelabs.core.interop.SimplePojo'
            * def testJson = { foo: 'hello', bar: 5 }
            * def testPojo = karate.toBean(testJson, className)
            * assert testPojo.foo == 'hello'
            * assert testPojo.bar == 5
            """);
        assertPassed(sr);
    }

}
