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

import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
class JsProperty {

    static final Logger logger = LoggerFactory.getLogger(JsProperty.class);

    final Node node;
    final Object object;
    final CoreContext context;
    final boolean functionCall;

    boolean optional;
    String name;
    Object index;

    JsProperty(Node node, CoreContext context) {
        this(node, context, false);
    }

    JsProperty(Node node, CoreContext context, boolean functionCall) {
        this.node = node;
        this.context = context;
        this.functionCall = functionCall;
        context.root.currentNode = node;
        switch (node.type) {
            case REF_EXPR:
                object = null;
                name = node.getText();
                break;
            case REF_DOT_EXPR:
                Object tempObject;
                if (node.get(1).type == NodeType.TOKEN) { // normal dot or optional
                    optional = node.get(1).token.type == TokenType.QUES_DOT;
                    name = node.get(2).getText();
                    try {
                        tempObject = Interpreter.eval(node.getFirst(), context);
                    } catch (Exception e) {
                        if (context.root.bridge != null) {
                            // try java interop
                            String base = node.getFirst().getText();
                            String path = base + "." + name;
                            ExternalAccess ja = context.root.bridge.forType(path);
                            if (ja != null) {
                                tempObject = ja;
                                name = null;
                            } else {
                                tempObject = context.root.bridge.forType(base);
                            }
                        } else {
                            tempObject = null;
                        }
                        if (tempObject == null) {
                            throw new RuntimeException("expression: " + node.getFirst().getText() + " - " + e.getMessage());
                        }
                    }
                } else {
                    optional = true;
                    if (node.get(1).type == NodeType.REF_BRACKET_EXPR) { // optional bracket
                        tempObject = Interpreter.eval(node.getFirst(), context);
                        index = Interpreter.eval(node.get(1).get(2), context);
                    } else { // optional function call
                        tempObject = Interpreter.eval(node.getFirst(), context); // evalFnCall
                    }
                }
                if (functionCall) {
                    Object tempMirror = Terms.toJavaMirror(tempObject);
                    if (tempMirror != null) {
                        tempObject = tempMirror;
                    }
                }
                object = tempObject;
                break;
            case REF_BRACKET_EXPR:
                object = Interpreter.eval(node.getFirst(), context);
                index = Interpreter.eval(node.get(2), context);
                break;
            case LIT_EXPR: // so MATH_PRE_EXP can call set() to update variable value
                object = Interpreter.eval(node, context);
                break;
            case PAREN_EXPR: // expression wrapped in round brackets that is invoked as a function, e.g., iife
                object = Interpreter.eval(node.get(1), context);
                break;
            case FN_CALL_EXPR: // currying
                object = Interpreter.eval(node, context);
                break;
            default:
                throw new RuntimeException("cannot assign from: " + node);
        }
    }

    void set(Object value) {
        if (index instanceof Number n) {
            // TODO out of bounds handling, unify iterable
            if (object instanceof List) { // most common case
                ((List<Object>) object).set(n.intValue(), value);
                return;
            } else if (object instanceof byte[] bytes) {
                if (value instanceof Number v) {
                    bytes[n.intValue()] = (byte) (v.intValue() & 0xFF);
                }
                return;
            } else if (object instanceof Map || object instanceof ObjectLike) {
                // For objects, convert numeric index to string for property access
                name = index + "";
            } else {
                throw new RuntimeException("cannot set by index [" + index + "]:" + value + " on (non-array): " + object);
            }
        } else {
            if (index != null) {
                name = index + "";
            }
        }
        if (name == null) {
            throw new RuntimeException("unexpected set [null]:" + value + " on: " + object);
        }
        if (object == null) {
            context.update(name, value);
        } else if (object instanceof Map) {
            ((Map<String, Object>) object).put(name, value);
        } else if (object instanceof ObjectLike objectLike) {
            objectLike.put(name, value);
        } else if (context.root.bridge != null) {
            try {
                if (object instanceof ExternalAccess ja) {
                    ja.update(name, value);
                } else {
                    ExternalAccess ja = context.root.bridge.forInstance(object);
                    ja.update(name, value);
                }
            } catch (Exception e) {
                logger.error("external bridge error: {}", e.getMessage());
                throw new RuntimeException("cannot set '" + name + "'");
            }
        } else {
            throw new RuntimeException("cannot set '" + name + "' - " + node.getText());
        }
    }

    Object get() {
        if (!functionCall && index instanceof Number n) {
            int i = n.intValue();
            if (object == null || object == Terms.UNDEFINED) {
                if (optional) {
                    return Terms.UNDEFINED;
                }
                throw new RuntimeException("cannot read properties of " + object + " (reading '[" + i + "]')");
            }
            if (object instanceof List list) {
                // return undefined for out of bounds access (JS behavior)
                if (i < 0 || i >= list.size()) {
                    return Terms.UNDEFINED;
                }
                return list.get(i);
            }
            if (object instanceof JsArray array) {
                // regex returns JsArray for exec() api
                return array.get(i);
            }
            if (object instanceof String s) {
                if (i < 0 || i >= s.length()) {
                    return Terms.UNDEFINED;
                }
                return s.substring(i, i + 1);
            }
            if (object instanceof byte[] bytes) {
                if (i < 0 || i >= bytes.length) {
                    return Terms.UNDEFINED;
                }
                return bytes[i] & 0xFF;
            }
            // For objects (Map, ObjectLike), convert numeric index to string for property access
            if (object instanceof Map || object instanceof ObjectLike) {
                name = index + "";
            } else {
                throw new RuntimeException("get by index [" + i + "] for non-array: " + object);
            }
        } else if (index != null) {
            name = index + "";
        }
        if (name == null) {
            return object;
        }
        if (object == null || object == Terms.UNDEFINED) {
            if (context.hasKey(name)) {
                return context.get(name);
            }
            if (optional) {
                return Terms.UNDEFINED;
            }
            throw new RuntimeException("cannot read properties of " + object + " (reading '" + name + "')");
        }
        // Map: check direct key access first (optimization), then prototype
        if (object instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) object;
            if (map.containsKey(name)) {
                return map.get(name);
            }
            Object result = new JsObject(map).get(name);
            if (result != null) {
                return result;
            }
            // fallthrough to external bridge for Java interop (e.g., Properties.put())
        } else {
            // covers ObjectLike (JsObject, JsArray, etc.), List, and primitives (String, Number, etc.)
            ObjectLike ol = Terms.toObjectLike(object);
            if (ol != null) {
                return ol.get(name);
            }
        }
        if (object instanceof JsCallable callable) {
            // e.g. [].map.call([1, 2, 3], x => x * 2)
            // we wrap in a JsFunction, so that prototype methods can be looked-up by get(name)
            return new JsFunction() {
                @Override
                public Object call(Context context, Object... args) {
                    return callable.call(context, args);
                }
            }.get(name);
        }
        if (context.root.bridge != null) {
            try {
                if (functionCall) {
                    if (object instanceof ExternalAccess ja) {
                        return ja.readInvokable(name);
                    } else {
                        ExternalAccess ja = context.root.bridge.forInstance(object);
                        return ja.readInvokable(name);
                    }
                } else {
                    if (object instanceof ExternalAccess ja) {
                        return ja.read(name);
                    } else {
                        ExternalAccess ja = context.root.bridge.forInstance(object);
                        return ja.read(name);
                    }
                }
            } catch (Exception e) {
                // ignore java reflection failure
            }
        }
        return Terms.UNDEFINED;
    }

}

