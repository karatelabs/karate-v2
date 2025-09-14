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

import io.karatelabs.common.Resource;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class Engine {

    public static boolean DEBUG = false;

    final ContextRoot root = new ContextRoot();

    final Map<String, Object> bindings = new HashMap<>();

    public Object eval(Resource resource) {
        return evalInternal(resource);
    }

    public Object eval(File file) {
        return evalInternal(Resource.file(file));
    }

    public Object eval(String text) {
        return evalInternal(Resource.text(text));
    }

    public Object evalWith(String text, Map<String, Object> vars) {
        return evalInternal(Resource.text(text), vars);
    }

    public Object get(String name) {
        return toJava(bindings.get(name));
    }

    public void put(String name, Object value) {
        bindings.put(name, value);
    }

    public void remove(String name) {
        bindings.remove(name);
    }

    public void setOnConsoleLog(Consumer<String> onConsoleLog) {
        root.setOnConsoleLog(onConsoleLog);
    }

    public void setListener(EventListener listener) {
        root.listener = listener;
    }

    public void enableJavaBridge() {
        root.javaBridge = new JavaBridge();
    }

    public void setJavaBridge(JavaBridge javaBridge) {
        root.javaBridge = javaBridge;
    }

    static Object toJava(Object value) {
        if (value instanceof JavaMirror) {
            return ((JavaMirror) value).toJava();
        }
        if (value == Terms.UNDEFINED) {
            return null;
        }
        return value;
    }

    private Object evalInternal(Resource resource) {
        return evalInternal(resource, null);
    }

    private Object evalInternal(Resource resource, Map<String, Object> localVars) {
        try {
            JsParser parser = new JsParser(resource);
            Node node = parser.parse();
            DefaultContext context;
            if (localVars == null) {
                context = new DefaultContext(root, root, 0, node, bindings);
            } else {
                DefaultContext parent = new DefaultContext(root, null, -1, new Node(NodeType.ROOT), bindings);
                context = new DefaultContext(root, parent, 0, node, localVars);
            }
            context.event(EventType.CONTEXT_ENTER, node);
            Object result = Interpreter.eval(node, context);
            context.event(EventType.CONTEXT_EXIT, node);
            return toJava(result);
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null) {
                message = e + "";
            }
            if (resource.isUrlResource()) {
                message = message + "\n" + resource.getRelativePath();
            }
            throw new RuntimeException(message);
        }
    }

}
