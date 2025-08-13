package io.karatelabs.match;

import io.karatelabs.common.Json;
import io.karatelabs.js.Engine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationTest {

    static Value value(Object o) {
        Object temp = Value.parseIfJsonOrXmlString(o);
        return new Value(temp);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "EQUALS;EACH_EQUALS",
            "CONTAINS;EACH_CONTAINS",
            "CONTAINS_DEEP;EACH_CONTAINS_DEEP"}, delimiter = ';')
    void testSchema(String matchType, String matchEachType) {
        Json json = Json.of("{ a: '#number' }");
        Map<String, Object> map = json.asMap();
        Result mr = Match.evaluate("[{ a: 1 }, { a: 2 }]").is(Match.Type.valueOf(matchEachType), map);
        assertTrue(mr.pass);
        Engine engine = new Engine();
        engine.set("schema", map);
        Operation operation = new Operation(engine, Match.Type.valueOf(matchType), value("[{ a: 1 }, { a: 2 }]"), value("#[] schema"), false);
        assertTrue(operation.execute());
        operation = new Operation(engine, Match.Type.valueOf(matchType), value("{ a: 'x', b: { c: 'y' } }"), value("{ a: '#string', b: { c: '#string' } }"), false);
        assertTrue(operation.execute());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EQUALS", "CONTAINS", "CONTAINS_DEEP"})
    void testSchemaOptionalObject(String matchType) {
        Json part = Json.of("{ bar: '#string' }");
        Engine engine = new Engine();
        engine.set("part", part.asMap());
        Operation operation = new Operation(engine, Match.Type.valueOf(matchType), value("{ foo: null }"), value("{ foo: '##(part)' }"), false);
        assertTrue(operation.execute());
        operation = new Operation(engine, Match.Type.valueOf(matchType), value("{ foo: { bar: 'baz' } }"), value("{ foo: '##(part)' }"), false);
        assertTrue(operation.execute());
    }

}
