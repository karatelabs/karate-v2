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

import java.util.HashMap;
import java.util.Map;

public class Context {

    static final Logger logger = LoggerFactory.getLogger(Context.class);

    private Map<String, Object> _bindings;

    Object thisObject = Terms.UNDEFINED;

    ContextListener listener;

    Context(Context parent, int depth, Node node, Map<String, Object> bindings) {
        this.parent = parent;
        this.depth = depth;
        this.node = node;
        this._bindings = bindings;
        if (parent != null) {
            listener = parent.listener;
            thisObject = parent.thisObject;
        }
    }

    Context(Context parent, Node node) {
        this(parent, parent == null ? 0 : parent.depth + 1, node, null);
    }

    // public api ======================================================================================================
    //
    public final Context parent;

    public final int depth;

    public final Node node;

    public int getIterationIndex() {
        return iterationIndex;
    }

    public String getPath() {
        String prefix = (depth == 0) ? null : parent.getPath();
        String suffix = node.type + (iterationIndex == -1 ? "" : "[" + String.format("%02d", iterationIndex) + "]");
        return prefix == null ? suffix : prefix + "." + suffix;
    }

    @Override
    public String toString() {
        return getPath();
    }

    //==================================================================================================================
    //
    Object get(String name) {
        if ("this".equals(name)) {
            return thisObject;
        }
        if (_bindings != null && _bindings.containsKey(name)) {
            return _bindings.get(name);
        }
        if (parent.hasKey(name)) {
            return parent.get(name);
        }
        return Terms.UNDEFINED;
    }

    void put(String name, Object value) {
        if (value instanceof JsFunction) {
            ((JsFunction) value).name = name;
        }
        putBinding(name, value);
    }

    void update(String name, Object value) {
        if (_bindings != null && _bindings.containsKey(name)) {
            _bindings.put(name, value);
        } else if (parent.hasKey(name)) {
            parent.update(name, value);
        } else {
            putBinding(name, value);
            if (listener != null) {
                listener.onVariableWrite(this, name, value);
            }
        }
    }

    private void putBinding(String key, Object value) {
        if (_bindings == null) {
            _bindings = new HashMap<>();
        }
        _bindings.put(key, value);
    }

    boolean hasKey(String name) {
        if ("this".equals(name)) {
            return true;
        }
        if (_bindings != null && _bindings.containsKey(name)) {
            return true;
        }
        if (parent.hasKey(name)) {
            return true;
        }
        return false;
    }

    //==================================================================================================================
    //
    boolean construct;
    int iterationIndex = -1;

    private ExitType exitType;
    private Object returnValue;
    private Object errorThrown;

    Object stopAndBreak() {
        exitType = ExitType.BREAK;
        returnValue = null;
        errorThrown = null;
        return null;
    }

    Object stopAndThrow(Object error) {
        exitType = ExitType.THROW;
        returnValue = null;
        errorThrown = error;
        return error;
    }

    Object stopAndReturn(Object value) {
        exitType = ExitType.RETURN;
        returnValue = value;
        errorThrown = null;
        return value;
    }

    Object stopAndContinue() {
        exitType = ExitType.CONTINUE;
        returnValue = null;
        errorThrown = null;
        return null;
    }

    boolean isStopped() {
        return exitType != null;
    }

    ExitType getExitType() {
        return exitType;
    }

    boolean isContinuing() {
        return exitType == ExitType.CONTINUE;
    }

    void reset() {
        exitType = null;
        returnValue = null;
        errorThrown = null;
    }

    boolean isError() {
        return exitType == ExitType.THROW;
    }

    Object getReturnValue() {
        return returnValue;
    }

    Object getErrorThrown() {
        return errorThrown;
    }

    void updateFrom(Context childContext) {
        exitType = childContext.exitType;
        errorThrown = childContext.errorThrown;
        returnValue = childContext.returnValue;
    }

}
