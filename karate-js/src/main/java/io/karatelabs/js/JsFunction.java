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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class JsFunction extends JsObject implements JsCallable {

    String name;
    private JsObject functionPrototype;

    /**
     * Returns the function's prototype object (used for instance creation).
     * This is the object that will be set as [[Prototype]] of instances created with 'new'.
     * Lazily created on first access.
     */
    JsObject getFunctionPrototype() {
        if (functionPrototype == null) {
            functionPrototype = new JsObject();
            // ES6: prototype.constructor points back to the function
            functionPrototype.putMember("constructor", this);
        }
        return functionPrototype;
    }

    @Override
    public Object getMember(String name) {
        // For functions, "prototype" returns the function's prototype object
        // Check _map first to allow "Foo.prototype = ..." assignments
        if ("prototype".equals(name)) {
            Object fromMap = super.getMember(name);
            if (fromMap != null && fromMap != getPrototype()) {
                // Prototype was explicitly set via assignment
                return fromMap;
            }
            return getFunctionPrototype();
        }
        return super.getMember(name);
    }

    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                return switch (propName) {
                    case "call" -> callPrototype();
                    case "apply" -> applyPrototype();
                    // TODO bind
                    case "constructor" -> _this;
                    case "name" -> name;
                    case "toString" -> (JsCallable) (context, args) -> {
                        // ES6: Function.prototype.toString returns function source
                        Object thisObj = context.getThisObject();
                        if (thisObj instanceof JsFunctionNode fn) {
                            // User-defined function: return actual source
                            return fn.node.getTextIncludingWhitespace();
                        } else if (thisObj instanceof JsFunction fn) {
                            // Built-in function: return [native code]
                            String fnName = fn.name != null ? fn.name : "";
                            return "function " + fnName + "() { [native code] }";
                        }
                        return "[object Function]";
                    };
                    default -> null;
                };
            }
        };
    }

    static class ThisArgs {

        final Object thisObject;
        final List<Object> args;

        @SuppressWarnings("unchecked")
        ThisArgs(Object[] args, boolean apply) {
            List<Object> list = new ArrayList<>(Arrays.asList(args));
            if (!list.isEmpty()) {
                thisObject = list.removeFirst();
            } else {
                thisObject = Terms.UNDEFINED;
            }
            if (apply) {
                if (list.isEmpty()) {
                    this.args = list;
                } else {
                    Object first = list.getFirst();
                    if (first instanceof List) {
                        this.args = ((List<Object>) first);
                    } else {
                        this.args = new ArrayList<>();
                    }
                }
            } else {
                this.args = list;
            }
        }
    }

    private JsCallable callPrototype() {
        return (context, args) -> {
            ThisArgs thisArgs = new ThisArgs(args, false);
            if (context instanceof CoreContext cc) {
                cc.thisObject = thisArgs.thisObject;
            }
            return call(context, thisArgs.args.toArray());
        };
    }

    private JsCallable applyPrototype() {
        return (context, args) -> {
            ThisArgs thisArgs = new ThisArgs(args, true);
            if (context instanceof CoreContext cc) {
                cc.thisObject = thisArgs.thisObject;
            }
            return call(context, thisArgs.args.toArray());
        };
    }

}
