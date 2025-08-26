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

class NodeFunction extends JsFunction {

    static final Logger logger = LoggerFactory.getLogger(NodeFunction.class);

    final boolean arrow;
    final Node node;
    final Node body; // STATEMENT or BLOCK (that may return expr)
    final List<String> argNames;
    final int argCount;
    final Context declaredContext;

    public NodeFunction(boolean arrow, Node node, List<String> argNames, Node body, Context context) {
        this.arrow = arrow;
        this.node = node;
        this.argNames = argNames;
        this.argCount = argNames.size();
        this.body = body;
        this.declaredContext = context;
    }

    @Override
    public Node getNode() {
        return node;
    }

    @Override
    public Object invoke(Object... args) {
        Context childContext = new Context(declaredContext, body) {
            @Override
            public Object get(String name) {
                if ("arguments".equals(name)) {
                    return Arrays.asList(args);
                }
                return super.get(name);
            }
        };
        childContext.thisObject = arrow ? declaredContext.thisObject : thisObject;
        int actualArgCount = Math.min(args.length, argCount);
        for (int i = 0; i < actualArgCount; i++) {
            String name = argNames.get(i);
            if (name.charAt(0) == '.') { // varargs hack
                List<Object> remainingArgs = new ArrayList<>();
                for (int j = i; j < args.length; j++) {
                    remainingArgs.add(args[j]);
                }
                childContext.put(name.substring(1), remainingArgs);
            } else {
                childContext.put(name, args[i]);
            }
        }
        if (args.length < argCount) {
            for (int i = args.length; i < argCount; i++) {
                String name = argNames.get(i);
                childContext.put(name, Terms.UNDEFINED);
            }
        }
        Object result = Interpreter.eval(body, childContext);
        // exit function, only propagate error
        if (invokeContext != null && childContext.isError()) {
            invokeContext.updateFrom(childContext);
        }
        return body.type == NodeType.BLOCK ? childContext.getReturnValue() : result;
    }

    @Override
    public String toString() {
        String args = String.join(",", argNames);
        Object name = get("name");
        StringBuilder sb = new StringBuilder();
        if (arrow) {
            if (name != null) {
                sb.append(name).append(" ");
            }
            sb.append("(").append(args).append(") => {}");
        } else {
            sb.append("function");
            if (name != null) {
                sb.append(" ").append(name);
            }
            sb.append("(").append(args).append(") {}");
        }
        return sb.toString();
    }

}
