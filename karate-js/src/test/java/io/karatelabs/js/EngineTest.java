package io.karatelabs.js;

import io.karatelabs.common.Resource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    static final Logger logger = LoggerFactory.getLogger(EngineTest.class);

    @Test
    void testEvalWith() {
        Engine engine = new Engine();
        engine.put("x", 10);
        Map<String, Object> vars = new HashMap<>();
        vars.put("y", 20);
        engine.evalWith("var a = x + y", vars);
        assertEquals(30, vars.get("a"));
        assertFalse(engine.getBindings().containsKey("a"));
        assertFalse(engine.getBindings().containsKey("y"));
    }

    @Test
    void testEvalResult() {
        Engine engine = new Engine();
        Object result = engine.eval("""
                var foo = 'foo';
                var bar = foo + 'bar';
                """);
        assertEquals("foobar", result);
        assertEquals("foo", engine.get("foo"));
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
        String js = """
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
        Node lastLine = node.findFirstChild(TokenType.CONST);
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
        assertEquals(3, node.size());
        for (Node child : node) {
            if (!child.isEof()) {
                assertEquals(NodeType.STATEMENT, child.type);
            }
        }
    }

    @Test
    void testSwitchMultipleCaseWithReturn() {
        String js = """
                var fun = x => {
                    switch (x) {
                        case 'foo':
                        case 'fie':
                            return 'FOO';
                        default:
                            return 'BAR';
                    }
                };
                fun('foo');
                """;
        Engine engine = new Engine();
        String result = (String) engine.eval(js);
        assertEquals("FOO", result);
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
        ContextListener listener = new ContextListener() {
            @Override
            public void onVariableWrite(Context context, BindingType type, String name, Object value) {
                map.put("name", name);
                map.put("value", value);
            }
        };
        engine.setListener(listener);
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
        StringBuilder sb = new StringBuilder();
        engine.setOnConsoleLog(sb::append);
        engine.eval("console.log('foo');");
        assertEquals("foo", sb.toString());
        sb.setLength(0);
        engine.eval("var a = function(){ }; a.prototype.toString = function(){ return 'bar' }; console.log(a)");
        assertEquals("bar", sb.toString());
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

    @Test
    void testProgramContextParentIsNotNull() {
        Engine engine = new Engine();
        ContextListener listener = new ContextListener() {
            @Override
            public void onEvent(Event event) {
                assertNotNull(event.context.getParent());
            }
        };
        engine.setListener(listener);
        engine.eval("console.log('foo')");
    }

    @Test
    void testArrayForEachIterationTracking() {
        Engine engine = new Engine();
        StringBuilder sb = new StringBuilder();
        ContextListener listener = new ContextListener() {
            @Override
            public void onFunctionCall(Context context, Object... args) {
                if (context.getNode().type == NodeType.REF_DOT_EXPR && "b.push".equals(context.getNode().getText())) {
                    sb.append(context.getParent().getParent().getIteration()).append(":").append(args[0]).append("|");
                }
            }
        };
        engine.setListener(listener);
        engine.eval("""
                var a = [1, 2, 3];
                var b = [];
                a.forEach(x => b.push(x));
                """);
        assertEquals(List.of(1, 2, 3), engine.get("b"));
        assertEquals("0:1|1:2|2:3|", sb.toString());
        //====
        sb.setLength(0);
        engine.eval("""
                var a = { a: 1, b: 2, c: 3 };
                var b = [];
                Object.keys(a).forEach(x => b.push(x));
                """);
        assertEquals(List.of("a", "b", "c"), engine.get("b"));
        assertEquals("0:a|1:b|2:c|", sb.toString());
    }

    @Test
    void testForLoopIterationTracking() {
        Engine engine = new Engine();
        StringBuilder sb = new StringBuilder();
        ContextListener listener = new ContextListener() {
            @Override
            public void onFunctionCall(Context context, Object... args) {
                if (context.getNode().type == NodeType.REF_DOT_EXPR && "b.push".equals(context.getNode().getText())) {
                    sb.append(context.getParent().getIteration()).append(":").append(args[0]).append("|");
                }
            }
        };
        engine.setListener(listener);
        engine.eval("""
                var a = [1, 2, 3];
                var b = [];
                for (var i = 0; i < a.length; i++) b.push(a[i]);
                """);
        assertEquals(List.of(1, 2, 3), engine.get("b"));
        assertEquals("0:1|1:2|2:3|", sb.toString());
        //====
        sb.setLength(0);
        engine.eval("""
                var a = { a: 1, b: 2, c: 3 };
                var b = [];
                for (x in a) b.push(x);
                """);
        assertEquals(List.of("a", "b", "c"), engine.get("b"));
        assertEquals("0:a|1:b|2:c|", sb.toString());
        //====
        sb.setLength(0);
        engine.eval("""
                var a = { a: 1, b: 2, c: 3 };
                var b = [];
                for (x of a) b.push(x);
                """);
        assertEquals(List.of(1, 2, 3), engine.get("b"));
        assertEquals("0:1|1:2|2:3|", sb.toString());
    }

    @Test
    void testCommentExtraction() {
        String js = """
                console.log('foo');
                // hello world
                console.log('bar');
                """;
        Resource resource = Resource.text(js);
        JsParser parser = new JsParser(resource);
        Node node = parser.parse();
        assertTrue(node.getFirstToken().getComments().isEmpty());
        Node secondStatement = node.get(1);
        Token thenFirst = secondStatement.getFirstToken();
        assertEquals("// hello world", thenFirst.comments.getFirst().text);
    }

    @Test
    void testJsDocCommentExtraction() {
        String js = """
                /**
                 * @schema schema
                 */
                
                 console.log('hello world');
                """;
        Resource resource = Resource.text(js);
        JsParser parser = new JsParser(resource);
        Node node = parser.parse();
        String comment = node.getFirstToken().getComments().getFirst().text;
        assertTrue(comment.startsWith("/**"));
    }

}
