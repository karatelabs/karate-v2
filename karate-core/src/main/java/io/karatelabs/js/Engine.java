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
import java.util.Map;

public class Engine {

    public static JavaBridge JAVA_BRIDGE = new JavaBridge() {
        // non-final default that you can over-ride
    };

    public static boolean DEBUG = false;

    public final Context context;

    public Engine() {
        this.context = Context.root();
    }

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

    private Object evalInternal(Resource resource) {
        return evalInternal(resource, null);
    }

    private Object evalInternal(Resource resource, Map<String, Object> localVars) {
        try {
            JsParser parser = new JsParser(resource);
            Node node = parser.parse();
            Context evalContext = localVars == null ? context : new Context(context, localVars);
            evalContext.node = node;
            Object result = Interpreter.eval(node, evalContext);
            if (result instanceof JavaMirror) {
                return ((JavaMirror) result).toJava();
            }
            if (result == Terms.UNDEFINED) {
                return null;
            }
            return result;
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

    public void put(String name, Object value) {
        context.put(name, value);
    }

    public void remove(String name) {
        context.remove(name);
    }

    public Object get(String name) {
        Object value = context.get(name);
        if (value instanceof JavaMirror) {
            return ((JavaMirror) value).toJava();
        }
        if (value == Terms.UNDEFINED) {
            return null;
        }
        return value;
    }

}
