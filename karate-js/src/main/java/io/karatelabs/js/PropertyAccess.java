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

import io.karatelabs.parser.Node;
import io.karatelabs.parser.NodeType;
import io.karatelabs.parser.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
class PropertyAccess {

    static final Logger logger = LoggerFactory.getLogger(PropertyAccess.class);

    final Node node;
    final Object object;
    final CoreContext context;
    final boolean functionCall;

    boolean optional;
    String name;
    Object index;

    PropertyAccess(Node node, CoreContext context) {
        this(node, context, false);
    }

    PropertyAccess(Node node, CoreContext context, boolean functionCall) {
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
                    Object tempJsValue = Terms.toJsValue(tempObject);
                    if (tempJsValue != null) {
                        tempObject = tempJsValue;
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
        } else if (object instanceof ObjectLike objectLike) {
            // Check ObjectLike BEFORE Map (JsObject implements Map)
            objectLike.putMember(name, value);
        } else if (object instanceof Map) {
            ((Map<String, Object>) object).put(name, value);
        } else if (context.root.bridge != null) {
            try {
                if (object instanceof ExternalAccess ja) {
                    ja.setProperty(name, value);
                } else {
                    ExternalAccess ja = context.root.bridge.forInstance(object);
                    ja.setProperty(name, value);
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
            // Check JsArray BEFORE List (JsArray implements List)
            if (object instanceof JsArray array) {
                // JsArray.getElement(int) returns raw values for JS internal use
                return array.getElement(i);
            }
            if (object instanceof List<?> list) {
                // Plain Java List - return undefined for out of bounds access (JS behavior)
                if (i < 0 || i >= list.size()) {
                    return Terms.UNDEFINED;
                }
                return list.get(i);
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
            // Handle Java native arrays via toObjectLike conversion to JsArray
            ObjectLike converted = Terms.toObjectLike(object);
            if (converted instanceof JsArray jsArray) {
                return jsArray.getElement(i);
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
            if (functionCall && context.root.bridge != null && object instanceof ExternalAccess ea) {
                return (JsCallable) (c, args) -> ea.construct(args);
            }
            return object;
        }
        if (object == null || object == Terms.UNDEFINED) {
            if (context.hasKey(name)) {
                Object result = context.get(name);
                if (functionCall && context.root.bridge != null && result instanceof ExternalAccess ea) {
                    return (JsCallable) (c, args) -> ea.construct(args);
                }
                return result;
            }
            if (optional) {
                return Terms.UNDEFINED;
            }
            throw new RuntimeException("cannot read properties of " + object + " (reading '" + name + "')");
        }
        // Check JsObject/JsArray BEFORE generic ObjectLike/Map
        // This ensures prototype chain lookup and raw value access for JS internals
        if (object instanceof JsObject jsObj) {
            // For JsObject, check if key exists to distinguish null from undefined
            if (jsObj.containsKey(name)) {
                return jsObj.getMember(name);  // Returns raw value including null
            }
            // Check prototype chain
            Object result = jsObj.getMember(name);
            if (isFound(result)) {
                return result;
            }
            return Terms.UNDEFINED;
        } else if (object instanceof JsArray jsArr) {
            Object result = jsArr.getMember(name);
            if (isFound(result)) {
                return result;
            }
            return Terms.UNDEFINED;
        } else if (object instanceof ObjectLike ol) {
            Object result = ol.getMember(name);
            if (isFound(result)) {
                return result;
            }
            // For other ObjectLike (JavaObject, SimpleObject), fall through to external bridge
        } else if (object instanceof Map) {
            // Plain Java Map - check direct key access first, then prototype
            Map<String, Object> map = (Map<String, Object>) object;
            if (map.containsKey(name)) {
                return map.get(name);
            }
            Object result = new JsObject(map).getMember(name);
            if (result != null) {
                return result;
            }
            // fallthrough to external bridge for Java interop (e.g., Properties.put())
        } else if (object instanceof List) {
            // Plain Java List - convert to ObjectLike for property access
            ObjectLike ol = Terms.toObjectLike(object);
            if (ol != null) {
                Object result = ol.getMember(name);
                if (isFound(result)) {
                    return result;
                }
            }
            // fallthrough to external bridge for Java method access
        } else {
            // Other objects (primitives, POJOs, ExternalAccess like JavaType)
            ObjectLike ol = Terms.toObjectLike(object);
            if (ol != null) {
                Object result = ol.getMember(name);
                if (isFound(result)) {
                    return result;
                }
            }
            // For primitives without a bridge, return undefined
            // POJOs and ExternalAccess fall through to the bridge
        }
        if (object instanceof JsCallable callable) {
            // e.g. [].map.call([1, 2, 3], x => x * 2)
            // we wrap in a JsFunction, so that prototype methods can be looked-up by get(name)
            return new JsFunction() {
                @Override
                public Object call(Context context, Object... args) {
                    return callable.call(context, args);
                }
            }.getMember(name);
        }
        return accessViaBridge(name);
    }

    private static boolean isFound(Object result) {
        return result != null && result != Terms.UNDEFINED;
    }

    private Object accessViaBridge(String name) {
        if (context.root.bridge == null) {
            return Terms.UNDEFINED;
        }
        try {
            ExternalAccess ja = object instanceof ExternalAccess ea
                    ? ea : context.root.bridge.forInstance(object);
            return functionCall ? ja.getMethod(name) : ja.getProperty(name);
        } catch (Exception e) {
            return Terms.UNDEFINED;
        }
    }

}

