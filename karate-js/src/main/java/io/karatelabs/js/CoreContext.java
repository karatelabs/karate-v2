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

import io.karatelabs.parser.Node;

import java.util.Map;
import java.util.function.Supplier;

class CoreContext implements Context {

    final ContextRoot root;

    Object thisObject = Terms.UNDEFINED;
    CallInfo callInfo;

    Bindings _bindings;

    CoreContext(ContextRoot root, CoreContext parent, int depth, Node node, ContextScope scope, Map<String, Object> bindings) {
        this.root = root;
        this.parent = parent;
        this.depth = depth;
        this.node = node;
        this.scope = scope;
        if (bindings != null) {
            this._bindings = bindings instanceof Bindings b ? b : new Bindings(bindings);
        }
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
    public CallInfo getCallInfo() {
        return callInfo;
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
        if (_bindings != null) {
            Object result = _bindings.getMember(key);
            if (result != null || _bindings.hasMember(key)) {
                BindValue bv = findConstOrLet(key);
                if (bv != null && !bv.initialized) {
                    throw new RuntimeException("cannot access '" + key + "' before initialization");
                }
                if (result instanceof Supplier<?> supplier) {
                    return supplier.get();
                }
                return result;
            }
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
        declare(key, value, null, true);
    }

    void declare(String key, Object value, BindingType type, boolean initialized) {
        if (value instanceof JsFunction) {
            ((JsFunction) value).name = key;
        }
        if (type != null) { // let or const
            BindValue existing = findConstOrLet(key);
            if (existing != null) {
                if (scope == ContextScope.LOOP_INIT) {
                    // for-of/for-in loop iteration: re-declaration is valid, clear binding type
                    _bindings.clearBindingType(key);
                } else {
                    throw new RuntimeException("identifier '" + key + "' has already been declared");
                }
            }
            putBinding(key, value, type, initialized);
            // for top-level declarations, also store in root to persist across evals
            if (depth == 0 && root != null && parent == root) {
                root.addBinding(key, type);
            }
        } else { // hoist var
            CoreContext targetContext = this;
            while (targetContext.depth > 0 && targetContext.scope != ContextScope.FUNCTION) {
                targetContext = targetContext.parent;
            }
            targetContext.putBinding(key, value, null, true);
        }
    }

    void update(String key, Object value) {
        if (_bindings != null && _bindings.hasMember(key)) {
            BindValue bv = findConstOrLet(key);
            if (bv != null) {
                if (bv.type == BindingType.CONST && bv.initialized) {
                    throw new RuntimeException("assignment to constant: " + key);
                }
                bv.initialized = true;
            }
            _bindings.putMember(key, value);
        } else if (parent != null && parent.hasKey(key)) {
            parent.update(key, value);
        } else {
            // implicit global: assign to global scope (ES6 non-strict behavior)
            CoreContext globalContext = this;
            while (globalContext.depth > 0) {
                globalContext = globalContext.parent;
            }
            globalContext.putBinding(key, value, null, true);
            if (root.listener != null) {
                root.listener.onVariableWrite(this, BindingType.VAR, key, value);
            }
        }
    }

    private void putBinding(String key, Object value, BindingType type, boolean initialized) {
        if (_bindings == null) {
            _bindings = new Bindings();
        }
        _bindings.putMember(key, value, type, initialized);
    }

    boolean hasKey(String key) {
        if ("this".equals(key)) {
            return true;
        }
        if (_bindings != null && _bindings.hasMember(key)) {
            return true;
        }
        if (parent != null && parent.hasKey(key)) {
            return true;
        }
        return root.hasKey(key);
    }

    private BindValue findConstOrLet(String key) {
        if (_bindings != null) {
            BindValue bv = _bindings.getBindValue(key);
            if (bv != null && bv.type != null) {
                return bv;
            }
        }
        // check root for top-level const/let declarations from previous evals (only at depth 0)
        if (depth == 0 && root != null && parent == root) {
            return root.getBindValue(key);
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
