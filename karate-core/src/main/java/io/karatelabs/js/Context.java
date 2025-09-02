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
    final ContextRoot root;

    Object thisObject = Terms.UNDEFINED;

    Context(ContextRoot root, Context parent, int depth, Node node, Map<String, Object> bindings) {
        this.root = root;
        this.parent = parent;
        this.depth = depth;
        this.node = node;
        this._bindings = bindings;
        if (parent != null) {
            thisObject = parent.thisObject;
        }
    }

    Context(Context parent, Node node) {
        this(parent.root, parent, parent.depth + 1, node, null);
    }


    void event(Event.Type type, Node node) {
        if (root.listener != null) {
            Event event = new Event(type, this, node);
            root.listener.onEvent(event);
        }
    }

    // public api ======================================================================================================
    //
    public final Context parent;

    public final int depth;

    public final Node node;

    public int getIterationIndex() {
        return iterationIndex;
    }

    public Event.ExitType getExitType() {
        return exitType;
    }

    public String getPath() {
        String parentPath = parent.getPath();
        String suffix = iterationIndex == -1 ? "" : "[" + iterationIndex + "]";
        return parentPath == null ? node.type + suffix : parentPath + "." + node.type + suffix;
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
            if (root.listener != null) {
                root.listener.onVariableWrite(this, Event.VariableType.VAR, name, value); // TODO var types
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
        return parent.hasKey(name);
    }

    //==================================================================================================================
    //
    boolean construct;
    int iterationIndex = -1;

    private Event.ExitType exitType;
    private Object returnValue;
    private Object errorThrown;

    Object stopAndBreak() {
        exitType = Event.ExitType.BREAK;
        returnValue = null;
        errorThrown = null;
        return null;
    }

    Object stopAndThrow(Object error) {
        exitType = Event.ExitType.THROW;
        returnValue = null;
        errorThrown = error;
        return error;
    }

    Object stopAndReturn(Object value) {
        exitType = Event.ExitType.RETURN;
        returnValue = value;
        errorThrown = null;
        return value;
    }

    Object stopAndContinue() {
        exitType = Event.ExitType.CONTINUE;
        returnValue = null;
        errorThrown = null;
        return null;
    }

    boolean isStopped() {
        return exitType != null;
    }

    boolean isContinuing() {
        return exitType == Event.ExitType.CONTINUE;
    }

    void reset() {
        exitType = null;
        returnValue = null;
        errorThrown = null;
    }

    boolean isError() {
        return exitType == Event.ExitType.THROW;
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
