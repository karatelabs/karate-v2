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

    private static List<Node> fnArgs(Node fnArgs) {
        List<Node> list = new ArrayList<>(fnArgs.size() - 2);
        for (Node fnArg : fnArgs) {
            if (fnArg.type != NodeType.FN_DECL_ARG) {
                continue;
            }
            list.add(fnArg);
        }
        return list;
    }

    private static Terms terms(Node node, CoreContext context) {
        return terms(eval(node.get(0), context), eval(node.get(2), context));
    }

    private static Terms terms(Object lhs, Object rhs) {
        return new Terms(lhs, rhs);
    }

    @SuppressWarnings("unchecked")
    static Object evalAssign(Node bindings, CoreContext context, BindingType bindingType, Object value, boolean initialized) {
        if (bindings.type == NodeType.LIT_ARRAY) {
            List<Object> list = null;
            if (value instanceof List) {
                list = (List<Object>) value;
            }
            evalLitArray(bindings, context, bindingType, list);
        } else if (bindings.type == NodeType.LIT_OBJECT) {
            Map<String, Object> object = null;
            if (value instanceof Map) {
                object = (Map<String, Object>) value;
            }
            evalLitObject(bindings, context, bindingType, object);
        } else {
            List<Node> varNames = bindings.findAll(IDENT);
            for (Node varName : varNames) {
                String name = varName.getText();
                context.declare(name, value, toInfo(name, bindingType, initialized));
                if (context.root.listener != null) {
                    context.root.listener.onVariableWrite(context, bindingType, name, value);
                }
            }
        }
        return value;
    }

    private static Object evalAssignExpr(Node node, CoreContext context) {
        Node lhs = node.get(0);
        TokenType operator = node.get(1).token.type;
        Object value = eval(node.get(2), context);
        if (operator == EQ) {
            if (lhs.type == NodeType.LIT_EXPR) {
                evalAssign(lhs.getFirst(), context, BindingType.VAR, value, true);
            } else {
                JsProperty prop = new JsProperty(lhs, context);
                prop.set(value);
            }
            return value;
        }
        JsProperty prop = new JsProperty(lhs, context);
        Object result = switch (operator) {
            case PLUS_EQ -> Terms.add(prop.get(), value);
            case MINUS_EQ -> terms(prop.get(), value).min();
            case STAR_EQ -> terms(prop.get(), value).mul();
            case SLASH_EQ -> terms(prop.get(), value).div();
            case PERCENT_EQ -> terms(prop.get(), value).mod();
            case STAR_STAR_EQ -> terms(prop.get(), value).exp();
            case GT_GT_EQ -> terms(prop.get(), value).bitShiftRight();
            case LT_LT_EQ -> terms(prop.get(), value).bitShiftLeft();
            case GT_GT_GT_EQ -> terms(prop.get(), value).bitShiftRightUnsigned();
            default -> throw new RuntimeException("unexpected assignment operator: " + node.get(1));
        };
        prop.set(result);
        return result;
    }

    private static Object evalBlock(Node node, CoreContext context) {
        CoreContext blockContext = new CoreContext(context, node, ContextScope.BLOCK);
        blockContext.event(EventType.CONTEXT_ENTER, node);
        Object blockResult = null;
        for (Node child : node) {
            if (child.type == NodeType.STATEMENT) {
                blockResult = eval(child, blockContext);
                if (blockContext.isStopped()) {
                    break;
                }
            }
        }
        blockContext.event(EventType.CONTEXT_EXIT, node);
        context.updateFrom(blockContext);
        // errors would be handled by caller
        return blockContext.isStopped() ? blockContext.getReturnValue() : blockResult;
    }

    private static Object evalBreakStmt(Node node, CoreContext context) {
        return context.stopAndBreak();
    }

    private static Object evalContinueStmt(Node node, CoreContext context) {
        return context.stopAndContinue();
    }

    @SuppressWarnings("unchecked")
    private static Object evalDeleteStmt(Node node, CoreContext context) {
        JsProperty prop = new JsProperty(node.get(1).getFirst(), context);
        String key = prop.name == null ? prop.index + "" : prop.name;
        if (prop.object instanceof Map) {
            ((Map<String, Object>) prop.object).remove(key);
        } else if (prop.object instanceof ObjectLike) {
            ((ObjectLike) prop.object).remove(key);
        }
        return true;
    }

    private static Object evalExpr(Node node, CoreContext context) {
        node = node.getFirst();
        context.event(EventType.EXPRESSION_ENTER, node);
        try {
            Object result = eval(node, context);
            if (context.root.listener != null) {
                context.event(EventType.EXPRESSION_EXIT, node);
            }
            return result;
        } catch (Exception e) {
            if (context.root.listener != null) {
                Event event = new Event(EventType.EXPRESSION_EXIT, context, node);
                ExitResult exitResult = context.root.listener.onError(event, e);
                if (exitResult != null && exitResult.ignoreError) {
                    return exitResult.returnValue;
                }
            }
            throw e;
        }
    }

    private static Object evalExprList(Node node, CoreContext context) {
        Object result = null;
        for (Node child : node) {
            if (child.type == NodeType.EXPR) {
                result = eval(child, context);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object evalFnCall(Node node, CoreContext context, boolean newKeyword) {
        Node fnArgsNode;
        if (newKeyword) {
            node = node.getFirst();
            // check for new keyword with no parentheses for the constructor
            if (node.size() == 1) {
                fnArgsNode = null;
            } else {
                fnArgsNode = node.get(2);
                node = node.getFirst();
            }
        } else {
            fnArgsNode = node.get(2);
            node = node.getFirst();
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
        int argsCount = fnArgsNode == null ? 0 : fnArgsNode.size();
        for (int i = 0; i < argsCount; i++) {
            Node fnArgNode = fnArgsNode.get(i);
            Node argNode = fnArgNode.get(0);
            if (argNode.isToken()) { // DOT_DOT_DOT
                Object arg = eval(fnArgNode.get(1), context);
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
        CoreContext callContext = new CoreContext(context, node, ContextScope.FUNCTION);
        callContext.thisObject = prop.object == null ? callable : prop.object;
        if (callContext.root.listener != null) {
            callContext.root.listener.onFunctionCall(callContext, args);
        }
        Object result = callable.call(callContext, args);
        context.updateFrom(callContext);
        if (newKeyword && result == null) {
            result = callable;
        }
        if (result instanceof JavaMirror jm) {
            return jm.toJava();
        }
        return result;
    }

    private static Object evalFnExpr(Node node, CoreContext context) {
        if (node.get(1).token.type == IDENT) {
            JsFunctionNode fn = new JsFunctionNode(false, node, fnArgs(node.get(2)), node.getLast(), context);
            context.put(node.get(1).getText(), fn);
            return fn;
        } else {
            return new JsFunctionNode(false, node, fnArgs(node.get(1)), node.getLast(), context);
        }
    }

    private static Object evalFnArrowExpr(Node node, CoreContext context) {
        List<Node> argNodes;
        if (node.getFirst().token.type == IDENT) {
            argNodes = Collections.singletonList(node);
        } else {
            argNodes = fnArgs(node.getFirst());
        }
        return new JsFunctionNode(true, node, argNodes, node.getLast(), context);
    }

    private static Object evalForStmt(Node node, CoreContext context) {
        CoreContext outer = new CoreContext(context, node, ContextScope.LOOP_INIT);
        outer.event(EventType.CONTEXT_ENTER, node);
        Node forBody = node.getLast();
        Object forResult = null;
        CoreContext inner = outer;
        if (node.get(2).token.type == SEMI) {
            // rare case: "for(;;)"
        } else if (node.get(3).token.type == SEMI) {
            boolean isLetOrConst;
            if (node.get(2).type == NodeType.VAR_STMT) {
                evalVarStmt(node.get(2), outer);
                isLetOrConst = node.get(2).getFirstToken().type != VAR;
            } else {
                isLetOrConst = false;
                eval(node.get(2), outer);
            }
            if (node.get(4).token.type == SEMI) {
                // rare no-condition case: "for(init;;increment)"
            } else {
                Node forAfter = node.get(6).token.type == R_PAREN ? null : node.get(6);
                int index = -1;
                while (true) {
                    index++;
                    outer.iteration = index;
                    Object forCondition = eval(node.get(4), outer);
                    if (Terms.isTruthy(forCondition)) {
                        if (isLetOrConst) {
                            inner = new CoreContext(outer, forBody, ContextScope.LOOP_BODY);
                            inner._bindings = new HashMap<>(outer._bindings);
                            inner._bindingInfos = new ArrayList<>(outer._bindingInfos);
                        }
                        forResult = eval(forBody, inner);
                        if (inner.isStopped()) {
                            if (inner.isContinuing()) {
                                inner.reset();
                            } else { // break, return or throw
                                if (outer != inner) {
                                    outer.updateFrom(inner);
                                }
                                break;
                            }
                        }
                        if (forAfter != null) {
                            eval(forAfter, outer);
                        }
                    } else {
                        break;
                    }
                }
            }
        } else { // for in / of
            boolean in = node.get(3).token.type == IN;
            Object forObject = eval(node.get(4), outer);
            Iterable<KeyValue> iterable = Terms.toIterable(forObject);
            BindingType bindingType;
            Node bindings;
            if (node.get(2).type == NodeType.VAR_STMT) {
                bindings = node.get(2).get(1);
                bindingType = switch (node.get(2).getFirstToken().type) {
                    case LET -> BindingType.LET;
                    case CONST -> BindingType.CONST;
                    default -> BindingType.VAR;
                };
            } else {
                bindingType = BindingType.VAR;
                bindings = node.get(2);
            }
            int index = -1;
            for (KeyValue kv : iterable) {
                index++;
                outer.iteration = index;
                Object varValue = in ? kv.key : kv.value;
                evalAssign(bindings, outer, bindingType, varValue, true);
                if (bindingType == BindingType.LET || bindingType == BindingType.CONST) {
                    inner = new CoreContext(outer, forBody, ContextScope.LOOP_BODY);
                    inner._bindings = new HashMap<>(outer._bindings);
                    inner._bindingInfos = new ArrayList<>(outer._bindingInfos);
                }
                forResult = eval(forBody, inner);
                if (inner.isStopped()) {
                    if (inner.isContinuing()) {
                        inner.reset();
                    } else { // break, return or throw
                        if (outer != inner) {
                            outer.updateFrom(inner);
                        }
                        break;
                    }
                }
            }
        }
        outer.event(EventType.CONTEXT_EXIT, node);
        context.updateFrom(outer);
        return forResult;
    }

    private static Object evalIfStmt(Node node, CoreContext context) {
        if (Terms.isTruthy(eval(node.get(2), context))) {
            return eval(node.get(4), context);
        } else {
            if (node.size() > 5) {
                return eval(node.get(6), context);
            }
            return null;
        }
    }

    private static Object evalInstanceOfExpr(Node node, CoreContext context) {
        return Terms.instanceOf(eval(node.get(0), context), eval(node.get(2), context));
    }

    private static BindingInfo toInfo(String varName, BindingType type, boolean initialized) {
        if (type == BindingType.VAR) {
            return null;
        } else {
            BindingInfo info = new BindingInfo(varName, type);
            info.initialized = initialized;
            return info;
        }
    }

    private static Object evalLitArray(Node node, CoreContext context, BindingType bindingType, List<Object> bindSource) {
        int last = node.size() - 1;
        List<Object> list = new ArrayList<>();
        int index = 0;
        for (int i = 1; i < last; i++) {
            Node elem = node.get(i);
            Node exprNode = elem.get(0);
            if (exprNode.token.type == DOT_DOT_DOT) { // rest
                if (bindingType != null) {
                    String varName = elem.getLast().getText();
                    List<Object> value = new ArrayList<>();
                    if (bindSource != null) {
                        for (int j = index; j < bindSource.size(); j++) {
                            value.add(bindSource.get(j));
                        }
                    }
                    context.declare(varName, value, toInfo(varName, bindingType, true));
                } else {
                    Object value = evalRefExpr(elem.get(1), context);
                    Iterable<KeyValue> iterable = Terms.toIterable(value);
                    for (KeyValue kv : iterable) {
                        list.add(kv.value);
                    }
                }
            } else if (exprNode.token.type == COMMA) { // sparse
                list.add(null);
                index++;
            } else {
                if (bindingType != null) {
                    Object value = Terms.UNDEFINED;
                    String varName = exprNode.getFirstToken().text;
                    if (exprNode.getFirst().type == NodeType.ASSIGN_EXPR) { // default value
                        value = evalExpr(exprNode.getFirst().getLast(), context);
                    }
                    if (bindSource != null && index < bindSource.size()) {
                        Object temp = bindSource.get(index);
                        if (temp != Terms.UNDEFINED) {
                            value = bindSource.get(index);
                        }
                    }
                    context.declare(varName, value, toInfo(varName, bindingType, true));
                } else {
                    Object value = evalExpr(exprNode, context);
                    list.add(value);
                }
                index++;
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Object evalLitObject(Node node, CoreContext context, BindingType bindingType, Map<String, Object> bindSource) {
        int last = node.size() - 1;
        Map<String, Object> result;
        if (bindSource != null) {
            result = new HashMap<>(bindSource); // use to derive ...rest if it appears
        } else {
            result = new LinkedHashMap<>(last - 1);
        }
        for (int i = 1; i < last; i++) {
            Node elem = node.get(i);
            Node keyNode = elem.getFirst();
            TokenType token = keyNode.token.type;
            String key;
            if (token == DOT_DOT_DOT) {
                key = elem.get(1).getText();
            } else if (token == S_STRING || token == D_STRING) {
                key = (String) keyNode.token.literalValue();
            } else { // IDENT, NUMBER
                key = keyNode.getText();
            }
            if (token == DOT_DOT_DOT) {
                if (bindingType != null) {
                    // previous keys were being removed from result
                    context.declare(key, result, toInfo(key, bindingType, true));
                } else {
                    Object value = context.get(key);
                    if (value instanceof Map) {
                        Map<String, Object> temp = (Map<String, Object>) value;
                        result.putAll(temp);
                    }
                }
            } else if (elem.size() < 3) { // es6 enhanced object literals
                if (bindingType != null) {
                    Object value = Terms.UNDEFINED;
                    if (bindSource != null && bindSource.containsKey(key)) {
                        value = bindSource.get(key);
                    }
                    context.declare(key, value, toInfo(key, bindingType, true));
                    result.remove(key);
                } else {
                    Object value = context.get(key);
                    result.put(key, value);
                }
            } else {
                if (bindingType != null) {
                    Object value = Terms.UNDEFINED;
                    if (bindSource != null && bindSource.containsKey(key)) {
                        value = bindSource.get(key);
                    }
                    if (elem.get(1).getFirstToken().type == EQ) { // default value
                        value = evalExpr(elem.get(2), context);
                        context.declare(key, value, toInfo(key, bindingType, true));
                    } else {
                        String varName = elem.get(2).getText();
                        context.declare(varName, value, toInfo(key, bindingType, true));
                    }
                    result.remove(key);
                } else {
                    Object value = evalExpr(elem.get(2), context);
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    private static String evalLitTemplate(Node node, CoreContext context) {
        StringBuilder sb = new StringBuilder();
        for (Node child : node) {
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

    private static Object evalLitExpr(Node node, CoreContext context) {
        node = node.getFirst();
        if (node.isToken()) {
            return node.token.literalValue();
        }
        return switch (node.type) {
            case NodeType.LIT_ARRAY -> evalLitArray(node, context, null, null);
            case NodeType.LIT_OBJECT -> evalLitObject(node, context, null, null);
            case NodeType.LIT_TEMPLATE -> evalLitTemplate(node, context);
            case NodeType.LIT_REGEX -> new JsRegex(node.getFirstToken().text);
            default -> throw new RuntimeException("unexpected lit expr: " + node);
        };
    }

    private static Object evalLogicBitExpr(Node node, CoreContext context) {
        return switch (node.get(1).token.type) {
            case AMP -> terms(node, context).bitAnd();
            case PIPE -> terms(node, context).bitOr();
            case CARET -> terms(node, context).bitXor();
            case GT_GT -> terms(node, context).bitShiftRight();
            case LT_LT -> terms(node, context).bitShiftLeft();
            case GT_GT_GT -> terms(node, context).bitShiftRightUnsigned();
            default -> throw new RuntimeException("unexpected operator: " + node.get(1));
        };
    }

    private static boolean evalLogicExpr(Node node, CoreContext context) {
        Object lhs = eval(node.get(0), context);
        Object rhs = eval(node.get(2), context);
        TokenType logicOp = node.get(1).token.type;
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
            default -> throw new RuntimeException("unexpected operator: " + node.get(1));
        };
    }

    private static Object evalLogicAndExpr(Node node, CoreContext context) {
        Object lhsValue = eval(node.get(0), context);
        boolean lhs = Terms.isTruthy(lhsValue);
        if (node.get(1).token.type == AMP_AMP) {
            if (lhs) {
                return eval(node.get(2), context);
            } else {
                return lhsValue;
            }
        } else { // PIPE_PIPE
            if (lhs) {
                return lhsValue;
            } else {
                return eval(node.get(2), context);
            }
        }
    }

    private static Object evalLogicTernExpr(Node node, CoreContext context) {
        if (Terms.isTruthy(eval(node.get(0), context))) {
            return eval(node.get(2), context);
        } else {
            return eval(node.get(4), context);
        }
    }

    private static Object evalMathAddExpr(Node node, CoreContext context) {
        return switch (node.get(1).token.type) {
            case PLUS -> Terms.add(eval(node.get(0), context), eval(node.get(2), context));
            case MINUS -> terms(node, context).min();
            default -> throw new RuntimeException("unexpected operator: " + node.get(1));
        };
    }

    private static Object evalMathMulExpr(Node node, CoreContext context) {
        return switch (node.get(1).token.type) {
            case STAR -> terms(node, context).mul();
            case SLASH -> terms(node, context).div();
            case PERCENT -> terms(node, context).mod();
            default -> throw new RuntimeException("unexpected operator: " + node.get(1));
        };
    }

    private static Object evalMathPostExpr(Node node, CoreContext context) {
        JsProperty postProp = new JsProperty(node.get(0), context);
        Object postValue = postProp.get();
        switch (node.get(1).token.type) {
            case PLUS_PLUS:
                postProp.set(Terms.add(postValue, 1));
                break;
            case MINUS_MINUS:
                postProp.set(terms(postValue, 1).min());
                break;
            default:
                throw new RuntimeException("unexpected operator: " + node.get(1));
        }
        return postValue;
    }

    private static Object evalMathPreExpr(Node node, CoreContext context) {
        JsProperty prop = new JsProperty(node.get(1).getFirst(), context);
        final Object value = prop.get();
        return switch (node.get(0).token.type) {
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
            default -> throw new RuntimeException("unexpected operator: " + node.getFirst());
        };
    }

    private static Object evalProgram(Node node, CoreContext context) {
        Object progResult = null;
        for (Node child : node) {
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

    private static Object evalRefExpr(Node node, CoreContext context) {
        if (node.getFirst().type == NodeType.FN_ARROW_EXPR) { // arrow function
            return evalFnArrowExpr(node.getFirst(), context);
        } else {
            String varName = node.getText();
            if (context.hasKey(varName)) {
                return context.get(varName);
            }
            throw new RuntimeException(varName + " is not defined");
        }
    }

    private static Object evalReturnStmt(Node node, CoreContext context) {
        if (node.size() > 1) {
            return context.stopAndReturn(eval(node.get(1), context));
        } else {
            return context.stopAndReturn(null);
        }
    }

    private static Object evalStatement(Node node, CoreContext context) {
        node = node.getFirst(); // go straight to relevant node
        if (node.token.type == SEMI) { // ignore empty statements
            return null;
        }
        context.event(EventType.STATEMENT_ENTER, node);
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
            context.event(EventType.STATEMENT_EXIT, node);
            return statementResult;
        } catch (Exception e) {
            if (context.root.listener != null) {
                Event event = new Event(EventType.STATEMENT_EXIT, context, node);
                ExitResult exitResult = context.root.listener.onError(event, e);
                if (exitResult != null && exitResult.ignoreError) {
                    return exitResult.returnValue;
                }
            }
            Token first = node.getFirstToken();
            String message = "js failed:\n==========\n" + first.getLineText() + "\n";
            if (first.resource.isUrlResource()) {
                message = message + first.resource + ":" + first.getPositionDisplay() + " ";
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

    private static Object evalSwitchStmt(Node node, CoreContext context) {
        Object switchValue = eval(node.get(2), context);
        List<Node> caseNodes = node.findChildrenOfType(NodeType.CASE_BLOCK);
        for (Node caseNode : caseNodes) {
            Object caseValue = eval(caseNode.get(1), context);
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

    private static Object evalThrowStmt(Node node, CoreContext context) {
        Object result = eval(node.get(1), context);
        return context.stopAndThrow(result);
    }

    private static Object evalTryStmt(Node node, CoreContext context) {
        Object tryValue = eval(node.get(1), context);
        Node finallyBlock = null;
        if (node.get(2).token.type == CATCH) {
            if (node.size() > 7) {
                finallyBlock = node.get(8);
            }
            if (context.isError()) {
                CoreContext catchContext = new CoreContext(context, node, ContextScope.CATCH);
                catchContext.event(EventType.CONTEXT_ENTER, node);
                if (node.get(3).token.type == L_PAREN) {
                    String errorName = node.get(4).getText();
                    catchContext.put(errorName, context.getErrorThrown());
                    tryValue = eval(node.get(6), catchContext);
                } else { // catch without variable name, 3 is block
                    tryValue = eval(node.get(3), context);
                }
                if (context.isError()) { // catch threw error,
                    tryValue = null;
                }
                context.updateFrom(catchContext);
                catchContext.event(EventType.CONTEXT_EXIT, node);
            }
        } else if (node.get(2).token.type == FINALLY) {
            finallyBlock = node.get(3);
        }
        if (finallyBlock != null) {
            CoreContext finallyContext = new CoreContext(context, node, ContextScope.BLOCK);
            finallyContext.event(EventType.CONTEXT_ENTER, node);
            eval(finallyBlock, finallyContext);
            finallyContext.event(EventType.CONTEXT_EXIT, node);
            if (finallyContext.isError()) {
                throw new RuntimeException("finally block threw error: " + finallyContext.getErrorThrown());
            }
        }
        return tryValue;
    }

    private static Object evalTypeofExpr(Node node, CoreContext context) {
        try {
            Object value = eval(node.get(1), context);
            return Terms.typeOf(value);
        } catch (Exception e) {
            return Terms.UNDEFINED.toString();
        }
    }

    private static Object evalUnaryExpr(Node node, CoreContext context) {
        Object unaryValue = eval(node.get(1), context);
        return switch (node.getFirst().token.type) {
            case NOT -> !Terms.isTruthy(unaryValue);
            case TILDE -> Terms.bitNot(unaryValue);
            default -> throw new RuntimeException("unexpected operator: " + node.getFirst());
        };
    }

    private static Object evalVarStmt(Node node, CoreContext context) {
        Object value;
        boolean initialized;
        if (node.size() > 3) {
            value = eval(node.get(3), context);
            initialized = true;
        } else {
            value = Terms.UNDEFINED;
            initialized = false;
        }
        BindingType bindingType = switch (node.getFirstToken().type) {
            case CONST -> BindingType.CONST;
            case LET -> BindingType.LET;
            default -> BindingType.VAR;
        };
        Node bindings = node.get(1);
        return evalAssign(bindings, context, bindingType, value, initialized);
    }

    private static Object evalWhileStmt(Node node, CoreContext context) {
        CoreContext whileContext = new CoreContext(context, node, ContextScope.LOOP_INIT);
        whileContext.event(EventType.CONTEXT_ENTER, node);
        Node whileBody = node.getLast();
        Node whileExpr = node.get(2);
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
        whileContext.event(EventType.CONTEXT_EXIT, node);
        return whileResult;
    }

    private static Object evalDoWhileStmt(Node node, CoreContext context) {
        CoreContext doContext = new CoreContext(context, node, ContextScope.LOOP_INIT);
        doContext.event(EventType.CONTEXT_ENTER, node);
        Node doBody = node.get(1);
        Node doExpr = node.get(4);
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
        doContext.event(EventType.CONTEXT_EXIT, node);
        return doResult;
    }

    static Object eval(Node node, CoreContext context) {
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
            case NEW_EXPR -> evalFnCall(node.get(1), context, true);
            case PAREN_EXPR -> evalExpr(node.get(1), context);
            case PROGRAM -> evalProgram(node, context);
            case REF_EXPR -> evalRefExpr(node, context);
            case REF_BRACKET_EXPR, REF_DOT_EXPR -> new JsProperty(node, context).get();
            case RETURN_STMT -> evalReturnStmt(node, context);
            case STATEMENT -> evalStatement(node, context);
            case SWITCH_STMT -> evalSwitchStmt(node, context);
            case THROW_STMT -> evalThrowStmt(node, context);
            case TRY_STMT -> evalTryStmt(node, context);
            case TYPEOF_EXPR -> evalTypeofExpr(node, context);
            case UNARY_EXPR -> evalUnaryExpr(node, context);
            case VAR_STMT -> evalVarStmt(node, context);
            case WHILE_STMT -> evalWhileStmt(node, context);
            case DO_WHILE_STMT -> evalDoWhileStmt(node, context);
            default -> throw new RuntimeException(node.toStringError("eval - unexpected node"));
        };
    }

}
