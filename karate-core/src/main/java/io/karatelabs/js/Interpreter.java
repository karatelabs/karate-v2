/*
 * The MIT License
 *
 * Copyright 2024 Karate Labs Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.karatelabs.js;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static io.karatelabs.js.TokenType.*;

class Interpreter {

    static final Logger logger = LoggerFactory.getLogger(Interpreter.class);

    private static List<String> argNames(Node fnArgs) {
        List<String> list = new ArrayList<>(fnArgs.children.size());
        for (Node fnArg : fnArgs.children) {
            if (fnArg.type != NodeType.FN_DECL_ARG) {
                continue;
            }
            Node first = fnArg.children.get(0);
            if (first.token.type == DOT_DOT_DOT) { // varargs
                list.add("." + fnArg.children.get(1).getText());
            } else {
                list.add(fnArg.children.getFirst().getText());
            }
        }
        return list;
    }

    private static Terms terms(Node node, DefaultContext context) {
        return terms(eval(node.children.get(0), context), eval(node.children.get(2), context));
    }

    private static Terms terms(Object lhs, Object rhs) {
        return new Terms(lhs, rhs);
    }

    private static Object evalAssignExpr(Node node, DefaultContext context) {
        JsProperty prop = new JsProperty(node.children.get(0), context);
        Object value = eval(node.children.get(2), context);
        Object result = switch (node.children.get(1).token.type) {
            case EQ -> value;
            case PLUS_EQ -> Terms.add(prop.get(), value);
            case MINUS_EQ -> terms(prop.get(), value).min();
            case STAR_EQ -> terms(prop.get(), value).mul();
            case SLASH_EQ -> terms(prop.get(), value).div();
            case PERCENT_EQ -> terms(prop.get(), value).mod();
            case STAR_STAR_EQ -> terms(prop.get(), value).exp();
            case GT_GT_EQ -> terms(prop.get(), value).bitShiftRight();
            case LT_LT_EQ -> terms(prop.get(), value).bitShiftLeft();
            case GT_GT_GT_EQ -> terms(prop.get(), value).bitShiftRightUnsigned();
            default -> throw new RuntimeException("unexpected assignment operator: " + node.children.get(1));
        };
        prop.set(result);
        return result;
    }

    private static Object evalBlock(Node node, DefaultContext context) {
        Object blockResult = null;
        for (Node child : node.children) {
            if (child.type == NodeType.STATEMENT) {
                blockResult = eval(child, context);
                if (context.isStopped()) {
                    break;
                }
            }
        }
        return context.isStopped() ? context.getReturnValue() : blockResult;
    }

    private static Object evalBreakStmt(Node node, DefaultContext context) {
        return context.stopAndBreak();
    }

    private static Object evalContinueStmt(Node node, DefaultContext context) {
        return context.stopAndContinue();
    }

    @SuppressWarnings("unchecked")
    private static Object evalDeleteStmt(Node node, DefaultContext context) {
        JsProperty prop = new JsProperty(node.children.get(1).children.getFirst(), context);
        String key = prop.name == null ? prop.index + "" : prop.name;
        if (prop.object instanceof Map) {
            ((Map<String, Object>) prop.object).remove(key);
        } else if (prop.object instanceof ObjectLike) {
            ((ObjectLike) prop.object).remove(key);
        }
        return true;
    }

    private static Object evalExpr(Node node, DefaultContext context) {
        node = node.children.getFirst();
        context.event(Event.Type.EXPRESSION_ENTER, node);
        try {
            Object result = eval(node, context);
            if (context.root.listener != null) {
                context.event(Event.Type.EXPRESSION_EXIT, node);
            }
            return result;
        } catch (Exception e) {
            if (context.root.listener != null) {
                Event event = new Event(Event.Type.EXPRESSION_EXIT, context, node);
                Event.Result eventResult = context.root.listener.onError(event, e);
                if (eventResult != null && eventResult.ignoreError) {
                    return eventResult.returnValue;
                }
            }
            throw e;
        }
    }

    private static Object evalExprList(Node node, DefaultContext context) {
        Object result = null;
        for (Node child : node.children) {
            if (child.type == NodeType.EXPR) {
                result = eval(child, context);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object evalFnCall(Node node, DefaultContext context, boolean newKeyword) {
        Node fnArgsNode;
        if (newKeyword) {
            node = node.children.getFirst();
            // check for new keyword with no parentheses for the constructor
            if (node.children.size() == 1) {
                fnArgsNode = null;
            } else {
                fnArgsNode = node.children.get(2);
                node = node.children.getFirst();
            }
        } else {
            fnArgsNode = node.children.get(2);
            node = node.children.getFirst();
        }
        JsProperty prop = new JsProperty(node, context, true);
        Object o = prop.get();
        if (o == Terms.UNDEFINED) { // optional chaining
            return o;
        }
        JsCallable callable = Terms.toCallable(o);
        if (callable == null) {
            throw new RuntimeException(node.toStringWithoutType() + " is not a function");
        }
        List<Object> argsList = new ArrayList<>();
        int argsCount = fnArgsNode == null ? 0 : fnArgsNode.children.size();
        for (int i = 0; i < argsCount; i++) {
            Node fnArgNode = fnArgsNode.children.get(i);
            Node argNode = fnArgNode.children.get(0);
            if (argNode.isToken()) { // DOT_DOT_DOT
                Object arg = eval(fnArgNode.children.get(1), context);
                if (arg instanceof List) {
                    argsList.addAll((List<Object>) arg);
                } else if (arg instanceof JsArray array) {
                    argsList.addAll(array.toList());
                }
            } else {
                Object arg = eval(argNode, context);
                argsList.add(arg);
            }
        }
        Object[] args = argsList.toArray();
        DefaultContext callContext = new DefaultContext(context, node);
        callContext.thisObject = prop.object == null ? callable : prop.object;
        callContext.event(Event.Type.CONTEXT_ENTER, node);
        if (callContext.root.listener != null) {
            callContext.root.listener.onFunctionCall(callContext, args);
        }
        Object result = callable.call(callContext, args);
        callContext.event(Event.Type.CONTEXT_EXIT, node);
        context.updateFrom(callContext);
        if (newKeyword && result == null) {
            result = callable;
        }
        if (result instanceof JavaMirror jm) {
            return jm.toJava();
        }
        return result;
    }

    private static Object evalFnExpr(Node node, DefaultContext context) {
        if (node.children.get(1).token.type == IDENT) {
            JsFunctionNode fn = new JsFunctionNode(false, node, argNames(node.children.get(2)), node.children.getLast(), context);
            context.put(node.children.get(1).getText(), fn);
            return fn;
        } else {
            return new JsFunctionNode(false, node, argNames(node.children.get(1)), node.children.getLast(), context);
        }
    }

    private static Object evalFnArrowExpr(Node node, DefaultContext context) {
        List<String> argNames;
        if (node.children.getFirst().token.type == IDENT) {
            argNames = Collections.singletonList(node.children.getFirst().getText());
        } else {
            argNames = argNames(node.children.getFirst());
        }
        return new JsFunctionNode(true, node, argNames, node.children.getLast(), context);
    }

    private static Object evalForStmt(Node node, DefaultContext context) {
        DefaultContext forContext = new DefaultContext(context, node);
        forContext.event(Event.Type.CONTEXT_ENTER, node);
        Node forBody = node.children.getLast();
        Object forResult = null;
        if (node.children.get(2).token.type == SEMI) {
            // rare case: "for(;;)"
        } else if (node.children.get(3).token.type == SEMI) {
            eval(node.children.get(2), forContext);
            if (node.children.get(4).token.type == SEMI) {
                // rare no-condition case: "for(init;;increment)"
            } else {
                Node forAfter = node.children.get(6).token.type == R_PAREN ? null : node.children.get(6);
                int index = -1;
                while (true) {
                    index++;
                    forContext.iteration = index;
                    Object forCondition = eval(node.children.get(4), forContext);
                    if (Terms.isTruthy(forCondition)) {
                        forResult = eval(forBody, forContext);
                        if (forContext.isStopped()) {
                            if (forContext.isContinuing()) {
                                forContext.reset();
                            } else { // break, return or throw
                                context.updateFrom(forContext);
                                break;
                            }
                        }
                        if (forAfter != null) {
                            eval(forAfter, forContext);
                        }
                    } else {
                        break;
                    }
                }
            }
        } else { // for in / of
            boolean in = node.children.get(3).token.type == IN;
            Object forObject = eval(node.children.get(4), forContext);
            Iterable<KeyValue> iterable = JsObject.toIterable(forObject);
            String varName;
            if (node.children.get(2).type == NodeType.VAR_STMT) {
                varName = node.children.get(2).children.get(1).getText();
            } else {
                varName = node.children.get(2).getText();
            }
            int index = -1;
            for (KeyValue kv : iterable) {
                index++;
                forContext.iteration = index;
                if (in) {
                    forContext.put(varName, kv.key);
                } else {
                    forContext.put(varName, kv.value);
                }
                forResult = eval(forBody, forContext);
                if (forContext.isStopped()) {
                    if (forContext.isContinuing()) {
                        forContext.reset();
                    } else { // break, return or throw
                        context.updateFrom(forContext);
                        break;
                    }
                }
            }
        }
        forContext.event(Event.Type.CONTEXT_EXIT, node);
        return forResult;
    }

    private static Object evalIfStmt(Node node, DefaultContext context) {
        if (Terms.isTruthy(eval(node.children.get(2), context))) {
            return eval(node.children.get(4), context);
        } else {
            if (node.children.size() > 5) {
                return eval(node.children.get(6), context);
            }
            return null;
        }
    }

    private static Object evalInstanceOfExpr(Node node, DefaultContext context) {
        return Terms.instanceOf(eval(node.children.get(0), context), eval(node.children.get(2), context));
    }

    @SuppressWarnings("unchecked")
    private static Object evalLitArray(Node node, DefaultContext context) {
        int last = node.children.size() - 1;
        List<Object> list = new ArrayList<>();
        for (int i = 1; i < last; i++) {
            Node elem = node.children.get(i);
            Node exprNode = elem.children.get(0);
            if (exprNode.token.type == DOT_DOT_DOT) { // spread
                Object value = eval(elem.children.get(1), context);
                if (value instanceof List) {
                    List<Object> temp = (List<Object>) value;
                    list.addAll(temp);
                } else if (value instanceof String s) {
                    for (char c : s.toCharArray()) {
                        list.add(Character.toString(c));
                    }
                }
            } else if (exprNode.token.type == COMMA) { // sparse
                list.add(null);
            } else {
                Object value = eval(exprNode, context);
                list.add(value);
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Object evalLitObject(Node node, DefaultContext context) {
        int last = node.children.size() - 1;
        Map<String, Object> map = new LinkedHashMap<>(last - 1);
        for (int i = 1; i < last; i++) {
            Node elem = node.children.get(i);
            Node keyNode = elem.children.getFirst();
            TokenType token = keyNode.token.type;
            String key;
            if (token == DOT_DOT_DOT) {
                key = elem.children.get(1).getText();
            } else if (token == S_STRING || token == D_STRING) {
                key = (String) keyNode.token.literalValue();
            } else { // IDENT, NUMBER
                key = keyNode.getText();
            }
            if (token == DOT_DOT_DOT) {
                Object value = context.get(key);
                if (value instanceof Map) {
                    Map<String, Object> temp = (Map<String, Object>) value;
                    map.putAll(temp);
                }
            } else if (elem.children.size() < 3) { // es6 enhanced object literals
                Object value = context.get(key);
                map.put(key, value);
            } else {
                Node exprNode = elem.children.get(2);
                Object value = eval(exprNode, context);
                map.put(key, value);
            }
        }
        return map;
    }

    private static String evalLitTemplate(Node node, DefaultContext context) {
        StringBuilder sb = new StringBuilder();
        for (Node child : node.children) {
            if (child.token.type == T_STRING) {
                sb.append(child.token.text);
            } else if (child.type == NodeType.EXPR) {
                Object value = eval(child, context);
                if (value == Terms.UNDEFINED) {
                    throw new RuntimeException(child.getText() + " is not defined");
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }

    private static Object evalLitExpr(Node node, DefaultContext context) {
        node = node.children.getFirst();
        if (node.isToken()) {
            return node.token.literalValue();
        }
        return switch (node.type) {
            case NodeType.LIT_ARRAY -> evalLitArray(node, context);
            case NodeType.LIT_OBJECT -> evalLitObject(node, context);
            case NodeType.LIT_TEMPLATE -> evalLitTemplate(node, context);
            case NodeType.LIT_REGEX -> new JsRegex(node.getFirstToken().text);
            default -> throw new RuntimeException("unexpected lit expr: " + node);
        };
    }

    private static Object evalLogicBitExpr(Node node, DefaultContext context) {
        return switch (node.children.get(1).token.type) {
            case AMP -> terms(node, context).bitAnd();
            case PIPE -> terms(node, context).bitOr();
            case CARET -> terms(node, context).bitXor();
            case GT_GT -> terms(node, context).bitShiftRight();
            case LT_LT -> terms(node, context).bitShiftLeft();
            case GT_GT_GT -> terms(node, context).bitShiftRightUnsigned();
            default -> throw new RuntimeException("unexpected operator: " + node.children.get(1));
        };
    }

    private static boolean evalLogicExpr(Node node, DefaultContext context) {
        Object lhs = eval(node.children.get(0), context);
        Object rhs = eval(node.children.get(2), context);
        TokenType logicOp = node.children.get(1).token.type;
        if (Terms.NAN.equals(lhs) || Terms.NAN.equals(rhs)) {
            if (Terms.NAN.equals(lhs) && Terms.NAN.equals(rhs)) {
                return logicOp == NOT_EQ || logicOp == NOT_EQ_EQ;
            }
            return false;
        }
        return switch (logicOp) {
            case EQ_EQ -> Terms.eq(lhs, rhs, false);
            case EQ_EQ_EQ -> Terms.eq(lhs, rhs, true);
            case NOT_EQ -> !Terms.eq(lhs, rhs, false);
            case NOT_EQ_EQ -> !Terms.eq(lhs, rhs, true);
            case LT -> Terms.lt(lhs, rhs);
            case GT -> Terms.gt(lhs, rhs);
            case LT_EQ -> Terms.ltEq(lhs, rhs);
            case GT_EQ -> Terms.gtEq(lhs, rhs);
            default -> throw new RuntimeException("unexpected operator: " + node.children.get(1));
        };
    }

    private static Object evalLogicAndExpr(Node node, DefaultContext context) {
        Object lhsValue = eval(node.children.get(0), context);
        boolean lhs = Terms.isTruthy(lhsValue);
        if (node.children.get(1).token.type == AMP_AMP) {
            if (lhs) {
                return eval(node.children.get(2), context);
            } else {
                return lhsValue;
            }
        } else { // PIPE_PIPE
            if (lhs) {
                return lhsValue;
            } else {
                return eval(node.children.get(2), context);
            }
        }
    }

    private static Object evalLogicTernExpr(Node node, DefaultContext context) {
        if (Terms.isTruthy(eval(node.children.get(0), context))) {
            return eval(node.children.get(2), context);
        } else {
            return eval(node.children.get(4), context);
        }
    }

    private static Object evalMathAddExpr(Node node, DefaultContext context) {
        return switch (node.children.get(1).token.type) {
            case PLUS -> Terms.add(eval(node.children.get(0), context), eval(node.children.get(2), context));
            case MINUS -> terms(node, context).min();
            default -> throw new RuntimeException("unexpected operator: " + node.children.get(1));
        };
    }

    private static Object evalMathMulExpr(Node node, DefaultContext context) {
        return switch (node.children.get(1).token.type) {
            case STAR -> terms(node, context).mul();
            case SLASH -> terms(node, context).div();
            case PERCENT -> terms(node, context).mod();
            default -> throw new RuntimeException("unexpected operator: " + node.children.get(1));
        };
    }

    private static Object evalMathPostExpr(Node node, DefaultContext context) {
        JsProperty postProp = new JsProperty(node.children.get(0), context);
        Object postValue = postProp.get();
        switch (node.children.get(1).token.type) {
            case PLUS_PLUS:
                postProp.set(Terms.add(postValue, 1));
                break;
            case MINUS_MINUS:
                postProp.set(terms(postValue, 1).min());
                break;
            default:
                throw new RuntimeException("unexpected operator: " + node.children.get(1));
        }
        return postValue;
    }

    private static Object evalMathPreExpr(Node node, DefaultContext context) {
        JsProperty prop = new JsProperty(node.children.get(1).children.getFirst(), context);
        final Object value = prop.get();
        return switch (node.children.get(0).token.type) {
            case PLUS_PLUS -> {
                prop.set(Terms.add(value, 1));
                yield prop.get();
            }
            case MINUS_MINUS -> {
                prop.set(terms(value, 1).min());
                yield prop.get();
            }
            case MINUS -> terms(value, -1).mul();
            case PLUS -> Terms.objectToNumber(value);
            default -> throw new RuntimeException("unexpected operator: " + node.children.getFirst());
        };
    }

    private static Object evalProgram(Node node, DefaultContext context) {
        Object progResult = null;
        for (Node child : node.children) {
            progResult = eval(child, context);
            if (context.isError()) {
                Object errorThrown = context.getErrorThrown();
                String errorMessage = null;
                if (errorThrown instanceof JsObject jsError) {
                    Object message = jsError.get("message");
                    if (message instanceof String) {
                        errorMessage = (String) message;
                    }
                }
                String message = child.toStringError(errorMessage == null ? errorThrown.toString() : errorMessage);
                throw new RuntimeException(message);
            }
        }
        return progResult;
    }

    private static Object evalRefExpr(Node node, DefaultContext context) {
        if (node.children.getFirst().type == NodeType.FN_ARROW_EXPR) { // arrow function
            return evalFnArrowExpr(node.children.getFirst(), context);
        } else {
            return context.get(node.getText());
        }
    }

    private static Object evalReturnStmt(Node node, DefaultContext context) {
        if (node.children.size() > 1) {
            return context.stopAndReturn(eval(node.children.get(1), context));
        } else {
            return context.stopAndReturn(null);
        }
    }

    private static Object evalStatement(Node node, DefaultContext context) {
        node = node.children.getFirst(); // go straight to relevant node
        if (node.token.type == SEMI) { // ignore empty statements
            return null;
        }
        context.event(Event.Type.STATEMENT_ENTER, node);
        try {
            Object statementResult = eval(node, context);
            if (logger.isTraceEnabled() || Engine.DEBUG) {
                NodeType nodeType = node.type;
                if (nodeType != NodeType.EXPR && nodeType != NodeType.BLOCK) {
                    Token first = node.getFirstToken();
                    logger.trace("{}{} {} | {}", first.resource, first.getPositionDisplay(), statementResult, node);
                    if (Engine.DEBUG) {
                        System.out.println(first.resource + first.getPositionDisplay() + " " + statementResult + " | " + node);
                    }
                }
            }
            context.event(Event.Type.STATEMENT_EXIT, node);
            return statementResult;
        } catch (Exception e) {
            if (context.root.listener != null) {
                Event event = new Event(Event.Type.STATEMENT_EXIT, context, node);
                Event.Result eventResult = context.root.listener.onError(event, e);
                if (eventResult != null && eventResult.ignoreError) {
                    return eventResult.returnValue;
                }
            }
            Token first = node.getFirstToken();
            String message = "js failed:\n==========\n" + first.getLineText() + "\n";
            if (first.resource.isUrlResource()) {
                message = message + first.resource + first.getPositionDisplay() + " ";
            } else if (first.line != 0) {
                message = message + first.getPositionDisplay() + " ";
            }
            message = message + e.getMessage();
            message = message.trim() + "\n----------\n";
            if (first.resource.isFile()) {
                System.err.println("file://" + first.resource.getUri().getPath() + ":" + first.getPositionDisplay() + " " + e);
            }
            throw new RuntimeException(message, e);
        }
    }

    private static Object evalSwitchStmt(Node node, DefaultContext context) {
        Object switchValue = eval(node.children.get(2), context);
        List<Node> caseNodes = node.findChildrenOfType(NodeType.CASE_BLOCK);
        for (Node caseNode : caseNodes) {
            Object caseValue = eval(caseNode.children.get(1), context);
            if (Terms.eq(switchValue, caseValue, true)) {
                Object caseResult = evalBlock(caseNode, context);
                if (context.isStopped()) {
                    return caseResult;
                }
            }
        }
        List<Node> defaultNodes = node.findChildrenOfType(NodeType.DEFAULT_BLOCK);
        if (!defaultNodes.isEmpty()) {
            return evalBlock(defaultNodes.getFirst(), context);
        }
        return null;
    }

    private static Object evalTryStmt(Node node, DefaultContext context) {
        Object tryValue = eval(node.children.get(1), context);
        Node finallyBlock = null;
        if (node.children.get(2).token.type == CATCH) {
            if (node.children.size() > 7) {
                finallyBlock = node.children.get(8);
            }
            if (context.isError()) {
                DefaultContext catchContext = new DefaultContext(context, node);
                catchContext.event(Event.Type.CONTEXT_ENTER, node);
                if (node.children.get(3).token.type == L_PAREN) {
                    String errorName = node.children.get(4).getText();
                    catchContext.put(errorName, context.getErrorThrown());
                    tryValue = eval(node.children.get(6), catchContext);
                } else { // catch without variable name, 3 is block
                    tryValue = eval(node.children.get(3), context);
                }
                if (context.isError()) { // catch threw error,
                    tryValue = null;
                }
                context.updateFrom(catchContext);
                catchContext.event(Event.Type.CONTEXT_EXIT, node);
            }
        } else if (node.children.get(2).token.type == FINALLY) {
            finallyBlock = node.children.get(3);
        }
        if (finallyBlock != null) {
            DefaultContext finallyContext = new DefaultContext(context, node);
            finallyContext.event(Event.Type.CONTEXT_ENTER, node);
            eval(finallyBlock, finallyContext);
            finallyContext.event(Event.Type.CONTEXT_EXIT, node);
            if (finallyContext.isError()) {
                throw new RuntimeException("finally block threw error: " + finallyContext.getErrorThrown());
            }
        }
        return tryValue;
    }

    private static Object evalUnaryExpr(Node node, DefaultContext context) {
        Object unaryValue = eval(node.children.get(1), context);
        return switch (node.children.get(0).token.type) {
            case NOT -> !Terms.isTruthy(unaryValue);
            case TILDE -> Terms.bitNot(unaryValue);
            default -> throw new RuntimeException("unexpected operator: " + node.children.getFirst());
        };
    }

    private static Object evalVarStmt(Node node, DefaultContext context) {
        Object varValue;
        if (node.children.size() > 3) {
            varValue = eval(node.children.get(3), context);
        } else {
            varValue = Terms.UNDEFINED;
        }
        List<Node> varNames = node.children.get(1).findAll(IDENT);
        // TODO let & const
        for (Node varName : varNames) {
            String name = varName.getText();
            context.put(name, varValue);
            if (context.root.listener != null) {
                context.root.listener.onVariableWrite(context, Event.VariableType.VAR, name, varValue);
            }
        }
        return varValue;
    }

    private static Object evalWhileStmt(Node node, DefaultContext context) {
        DefaultContext whileContext = new DefaultContext(context, node);
        whileContext.event(Event.Type.CONTEXT_ENTER, node);
        Node whileBody = node.children.getLast();
        Node whileExpr = node.children.get(2);
        Object whileResult = null;
        while (true) {
            Object whileCondition = eval(whileExpr, whileContext);
            if (!Terms.isTruthy(whileCondition)) {
                break;
            }
            whileResult = eval(whileBody, whileContext);
            if (whileContext.isStopped()) {
                if (whileContext.isContinuing()) {
                    whileContext.reset();
                } else { // break, return or throw
                    context.updateFrom(whileContext);
                    break;
                }
            }
        }
        whileContext.event(Event.Type.CONTEXT_EXIT, node);
        return whileResult;
    }

    private static Object evalDoWhileStmt(Node node, DefaultContext context) {
        DefaultContext doContext = new DefaultContext(context, node);
        doContext.event(Event.Type.CONTEXT_ENTER, node);
        Node doBody = node.children.get(1);
        Node doExpr = node.children.get(4);
        Object doResult;
        while (true) {
            doResult = eval(doBody, doContext);
            if (doContext.isStopped()) {
                if (doContext.isContinuing()) {
                    doContext.reset();
                } else { // break, return or throw
                    context.updateFrom(doContext);
                    break;
                }
            }
            Object doCondition = eval(doExpr, doContext);
            if (!Terms.isTruthy(doCondition)) {
                break;
            }
        }
        doContext.event(Event.Type.CONTEXT_EXIT, node);
        return doResult;
    }

    static Object eval(Node node, DefaultContext context) {
        return switch (node.type) {
            case ASSIGN_EXPR -> evalAssignExpr(node, context);
            case BLOCK -> evalBlock(node, context);
            case BREAK_STMT -> evalBreakStmt(node, context);
            case CONTINUE_STMT -> evalContinueStmt(node, context);
            case DELETE_STMT -> evalDeleteStmt(node, context);
            case EXPR -> evalExpr(node, context);
            case EXPR_LIST -> evalExprList(node, context);
            case LIT_EXPR -> evalLitExpr(node, context);
            case FN_EXPR -> evalFnExpr(node, context);
            case FN_ARROW_EXPR -> evalFnArrowExpr(node, context);
            case FN_CALL_EXPR -> evalFnCall(node, context, false);
            case FOR_STMT -> evalForStmt(node, context);
            case IF_STMT -> evalIfStmt(node, context);
            case INSTANCEOF_EXPR -> evalInstanceOfExpr(node, context);
            case LOGIC_EXPR -> evalLogicExpr(node, context);
            case LOGIC_AND_EXPR -> evalLogicAndExpr(node, context);
            case LOGIC_BIT_EXPR -> evalLogicBitExpr(node, context);
            case LOGIC_TERN_EXPR -> evalLogicTernExpr(node, context);
            case MATH_ADD_EXPR -> evalMathAddExpr(node, context);
            case MATH_EXP_EXPR -> terms(node, context).exp();
            case MATH_MUL_EXPR -> evalMathMulExpr(node, context);
            case MATH_POST_EXPR -> evalMathPostExpr(node, context);
            case MATH_PRE_EXPR -> evalMathPreExpr(node, context);
            case NEW_EXPR -> evalFnCall(node.children.get(1), context, true);
            case PAREN_EXPR -> evalExpr(node.children.get(1), context);
            case PROGRAM -> evalProgram(node, context);
            case REF_EXPR -> evalRefExpr(node, context);
            case REF_BRACKET_EXPR, REF_DOT_EXPR -> new JsProperty(node, context).get();
            case RETURN_STMT -> evalReturnStmt(node, context);
            case STATEMENT -> evalStatement(node, context);
            case SWITCH_STMT -> evalSwitchStmt(node, context);
            case THROW_STMT -> context.stopAndThrow(eval(node.children.get(1), context));
            case TRY_STMT -> evalTryStmt(node, context);
            case TYPEOF_EXPR -> Terms.typeOf(eval(node.children.get(1), context));
            case UNARY_EXPR -> evalUnaryExpr(node, context);
            case VAR_STMT -> evalVarStmt(node, context);
            case WHILE_STMT -> evalWhileStmt(node, context);
            case DO_WHILE_STMT -> evalDoWhileStmt(node, context);
            default -> throw new RuntimeException(node.toStringError("eval - unexpected node"));
        };
    }

}
