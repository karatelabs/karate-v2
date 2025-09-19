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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class JsFunctionNode extends JsFunction {

    static final Logger logger = LoggerFactory.getLogger(JsFunctionNode.class);

    final boolean arrow;
    final Node node;
    final Node body; // STATEMENT or BLOCK (that may return expr)
    final List<Node> argNodes;
    final int argCount;
    final CoreContext declaredContext;

    public JsFunctionNode(boolean arrow, Node node, List<Node> argNodes, Node body, CoreContext declaredContext) {
        this.arrow = arrow;
        this.node = node;
        this.argNodes = argNodes;
        this.argCount = argNodes.size();
        this.body = body;
        this.declaredContext = declaredContext;
    }

    @Override
    public Object call(Context callerContext, Object... args) {
        final CoreContext parentContext;
        if (callerContext instanceof CoreContext cc) {
            parentContext = cc;
        } else {
            parentContext = declaredContext;
        }
        // Interpreter.evalFnCall() will always spawn function scope
        CoreContext functionContext = new CoreContext(parentContext, node, ContextScope.BLOCK) {
            @Override
            public Object get(String key) {
                if ("arguments".equals(key)) {
                    return Arrays.asList(args);
                }
                if (super.hasKey(key)) { // typically callerContext
                    return super.get(key);
                }
                return declaredContext.get(key);
            }

            @Override
            boolean hasKey(String key) {
                return "arguments".equals(key) || super.hasKey(key) || declaredContext.hasKey(key);
            }
        };
        int actualArgCount = Math.min(args.length, argCount);
        for (int i = 0; i < actualArgCount; i++) {
            Node argNode = argNodes.get(i);
            Node first = argNode.getFirst();
            if (first.getFirstToken().type == TokenType.DOT_DOT_DOT) { // varargs
                List<Object> remainingArgs = new ArrayList<>();
                for (int j = i; j < args.length; j++) {
                    remainingArgs.add(args[j]);
                }
                String argName = argNode.getLast().getText();
                functionContext.put(argName, remainingArgs);
            } else if (first.type == NodeType.LIT_ARRAY || first.type == NodeType.LIT_OBJECT) {
                Interpreter.evalAssign(first, functionContext, BindingType.VAR, args[i], true);
            } else {
                String argName = argNode.getFirst().getText();
                Object argValue = args[i];
                if (argValue == Terms.UNDEFINED) {
                    // check if default value expression exists
                    Node exprNode = argNode.getLast();
                    if (exprNode.type == NodeType.EXPR) {
                        argValue = Interpreter.eval(exprNode, functionContext);
                    }
                }
                functionContext.put(argName, argValue);
            }
        }
        if (args.length < argCount) {
            for (int i = args.length; i < argCount; i++) {
                Node argNode = argNodes.get(i);
                String argName = argNode.getFirst().getText();
                Node exprNode = argNode.getLast();
                Object argValue;
                if (exprNode.type == NodeType.EXPR) { // default value
                    argValue = Interpreter.eval(exprNode, functionContext);
                } else {
                    argValue = Terms.UNDEFINED;
                }
                functionContext.put(argName, argValue);
            }
        }
        Object result = Interpreter.eval(body, functionContext);
        // exit function, only propagate error
        if (functionContext.isError()) {
            parentContext.updateFrom(functionContext);
        }
        return body.type == NodeType.BLOCK ? functionContext.getReturnValue() : result;
    }

    @Override
    public String toString() {
        return node.toString();
    }

}
