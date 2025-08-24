package io.karatelabs.js;

import io.karatelabs.common.Resource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    static final Logger logger = LoggerFactory.getLogger(EngineTest.class);

    @Test
    void testEvalResult() {
        Engine engine = new Engine();
        Object result = engine.eval("""
                var foo = 'foo';
                var bar = foo + 'bar';
                """);
        assertEquals("foobar", result);
        assertEquals("foo", engine.context.get("foo"));
    }

    @Test
    void testQuotesWithinStringLiterals() {
        Engine engine = new Engine();
        Object result = engine.eval("""
                var data = {"data": "{\\"myKey\\":\\"myValue\\"}"}
                """);
        assertEquals(Map.of("data", "{\\\"myKey\\\":\\\"myValue\\\"}"), result);
    }

    @Test
    void testWhiteSpaceTrackingWithinTemplates() {
        String js =
                """
                        var request = {
                            body: `{
                                        "RequesterDetails": {
                                            "InstructingTreasuryId": "000689",
                                            "ApiRequestReference": "${idempotencyKey}",
                                            "entity": "000689"
                                         }
                                   }`
                        };
                        const foo = 'bar';
                        """;
        JsParser parser = new JsParser(Resource.text(js));
        Node node = parser.parse();
        Node lastLine = node.findFirst(TokenType.CONST);
        assertEquals(9, lastLine.token.line);
    }

    @Test
    void testSwitchCaseDefaultSyntax() {
        String js = """
                a.b(() => {
                    function c() {
                        switch (d) {
                            case "X":
                                break;
                            default:
                                break;
                        }
                    }
                });
                var e = 1;
                """;
        JsParser parser = new JsParser(Resource.text(js));
        Node node = parser.parse();
        assertEquals(2, node.children.size());
        for (Node child : node.children) {
            assertEquals(NodeType.STATEMENT, child.type);
        }
    }

    @Test
    void testFunctionWithinFunction() {
        String js = """
                function generateCardNumber(firstSix, length) {
                
                    function luhnCheck(input) {
                        const number = input.toString();
                        const digits = number.replace(/\\D/g, '').split('').map(Number);
                        let sum = 0;
                        let isSecond = false;
                        for (let i = digits.length - 1; i >= 0; i--) {
                            let digit = digits[i];
                            if (isSecond) {
                                digit *= 2;
                                if (digit > 9) {
                                    digit -= 9;
                                }
                            }
                            sum += digit;
                            isSecond = !isSecond;
                        }
                        return sum % 10;
                    }
                
                    function randomDigit() {
                        return Math.floor(Math.random() * 9);
                    }
                
                    let cardNumber = firstSix;
                    while (cardNumber.length < length - 1) {
                        cardNumber = cardNumber + randomDigit();
                    }
                    cardNumber = cardNumber + '9';
                    let luhnVal = luhnCheck(cardNumber);
                    cardNumber = cardNumber - luhnVal;
                    return cardNumber.toString();
                
                }
                
                generateCardNumber('411111',16);
                """;
        Engine engine = new Engine();
        String result = (String) engine.eval(js);
        assertTrue(result.startsWith("411111"));
        assertEquals(16, result.length());
    }

    @Test
    void testUndefined() {
        Engine engine = new Engine();
        Object result = engine.eval("1 * 'a'");
        assertEquals(Double.NaN, result);
    }

    @Test
    void testOnAssign() {
        Engine engine = new Engine();
        Map<String, Object> map = new HashMap<>();
        engine.context.setOnAssign((name, value) -> {
            map.put("name", name);
            map.put("value", value);
        });
        engine.eval("var a = 'a'");
        assertEquals("a", map.get("name"));
        assertEquals("a", map.get("value"));
        engine.eval("b = 'b'");
        assertEquals("b", map.get("name"));
        assertEquals("b", map.get("value"));
    }

    @Test
    void testOnConsoleLog() {
        Engine engine = new Engine();
        Map<String, Object> map = new HashMap<>();
        engine.context.setOnConsoleLog((node, text) -> {
            map.put("node", node);
            map.put("text", text);
        });
        engine.eval("console.log('foo');");
        assertEquals("foo", map.get("text"));
        Node node = (Node) map.get("node");
        assertEquals(NodeType.STATEMENT, node.type);
        Token first = node.getFirstToken();
        assertEquals(TokenType.IDENT, first.type);
        assertEquals("console", first.text);
        assertEquals(0, first.line);
        assertEquals(0, first.col);
    }

    @Test
    void testErrorLog() {
        Engine engine = new Engine();
        try {
            engine.eval("var a = 1;\nvar b = a();");
            fail("expected error");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("a is not a function"));
        }
    }

    @Test
    void testWith() {
        Engine engine = new Engine();
        engine.put("foo", "parent");
        Map<String, Object> vars = new HashMap<>();
        vars.put("foo", "child");
        assertEquals("child", engine.evalWith("foo", vars));
        assertEquals("parent", engine.eval("foo"));
    }

}
