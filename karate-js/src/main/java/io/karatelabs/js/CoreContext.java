/*
 * The MIT License
 *
 * Copyright 2025 Karate Labs Inc.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

class CoreContext implements Context {

    static final Logger logger = LoggerFactory.getLogger(CoreContext.class);

    final ContextRoot root;

    Object thisObject = Terms.UNDEFINED;

    Map<String, Object> _bindings;
    List<BindingInfo> _bindingInfos;

    CoreContext(ContextRoot root, CoreContext parent, int depth, Node node, ContextScope scope, Map<String, Object> bindings) {
        this.root = root;
        this.parent = parent;
        this.depth = depth;
        this.node = node;
        this.scope = scope;
        this._bindings = bindings;
        if (parent != null) {
            thisObject = parent.thisObject;
        }
    }

    CoreContext(CoreContext parent, Node node, ContextScope scope) {
        this(parent.root, parent, parent.depth + 1, node, scope, null);
    }

    void event(EventType type, Node node) {
        if (root.listener != null) {
            Event event = new Event(type, this, node);
            root.listener.onEvent(event);
        }
    }

    // public api ======================================================================================================
    //
    final CoreContext parent;
    final ContextScope scope;
    final int depth;
    final Node node;

    @Override
    public Engine getEngine() {
        return root.getEngine();
    }

    @Override
    public Node getCurrentNode() {
        return root.currentNode;
    }

    @Override
    public Context getParent() {
        return parent;
    }

    @Override
    public ContextScope getScope() {
        return scope;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public Node getNode() {
        return node;
    }

    @Override
    public int getIteration() {
        return iteration;
    }

    @Override
    public String getPath() {
        String parentPath = depth == 0 ? null : parent.getPath();
        String suffix = iteration == -1 ? "" : "[" + iteration + "]";
        return parentPath == null ? node.type + suffix : parentPath + "." + node.type + suffix;
    }

    @Override
    public Object getThisObject() {
        return thisObject;
    }

    @Override
    public String toString() {
        return getPath();
    }

    //==================================================================================================================
    //
    Object get(String key) {
        if ("this".equals(key)) {
            return thisObject;
        }
        if (_bindings != null && _bindings.containsKey(key)) {
            BindingInfo info = findConstOrLet(key);
            if (info != null && !info.initialized) {
                throw new RuntimeException("cannot access '" + key + "' before initialization");
            }
            Object result = _bindings.get(key);
            if (result instanceof Supplier<?> supplier) {
                return supplier.get();
            }
            return result;
        }
        if (parent != null && parent.hasKey(key)) {
            return parent.get(key);
        }
        if (root.hasKey(key)) {
            return root.get(key);
        }
        return Terms.UNDEFINED;
    }

    void put(String key, Object value) {
        declare(key, value, null);
    }

    void declare(String key, Object value, BindingInfo info) {
        if (value instanceof JsFunction) {
            ((JsFunction) value).name = key;
        }
        if (info != null) { // if present, will always be let or const (micro optimization)
            putBinding(key, value, info); // current scope
        } else { // hoist var
            CoreContext targetContext = this;
            while (targetContext.depth > 0 && targetContext.scope != ContextScope.FUNCTION) {
                targetContext = targetContext.parent;
            }
            targetContext.putBinding(key, value, info);
        }
    }

    void update(String key, Object value) {
        if (_bindings != null && _bindings.containsKey(key)) {
            BindingInfo info = findConstOrLet(key);
            if (info != null) {
                if (info.type == BindingType.CONST && info.initialized) {
                    throw new RuntimeException("assignment to constant: " + key);
                }
                info.initialized = true;
            }
            _bindings.put(key, value);
        } else if (parent != null && parent.hasKey(key)) {
            parent.update(key, value);
        } else {
            putBinding(key, value, null);
            if (root.listener != null) {
                root.listener.onVariableWrite(this, BindingType.VAR, key, value);
            }
        }
    }

    private void putBinding(String key, Object value, BindingInfo info) {
        if (_bindings == null) {
            _bindings = new HashMap<>();
        }
        _bindings.put(key, value);
        if (info != null) {
            if (_bindingInfos == null) {
                _bindingInfos = new ArrayList<>();
            }
            _bindingInfos.add(info);
        }
    }

    boolean hasKey(String key) {
        if ("this".equals(key)) {
            return true;
        }
        if (_bindings != null && _bindings.containsKey(key)) {
            return true;
        }
        if (parent != null && parent.hasKey(key)) {
            return true;
        }
        return root.hasKey(key);
    }

    private BindingInfo findConstOrLet(String key) {
        if (_bindingInfos != null) {
            for (BindingInfo info : _bindingInfos) {
                if (info.name.equals(key)) {
                    return info;
                }
            }
        }
        return null;
    }

    //==================================================================================================================
    //
    int iteration = -1;

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

    void updateFrom(CoreContext childContext) {
        exitType = childContext.exitType;
        errorThrown = childContext.errorThrown;
        returnValue = childContext.returnValue;
    }

}
