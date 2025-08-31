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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
class JsProperty {

    static final Logger logger = LoggerFactory.getLogger(JsProperty.class);

    final Node node;
    final Object object;
    final Context context;
    final boolean functionCall;

    boolean optional;
    String name;
    Object index;

    JsProperty(Node node, Context context) {
        this(node, context, false);
    }

    JsProperty(Node node, Context context, boolean functionCall) {
        this.node = node;
        this.context = context;
        this.functionCall = functionCall;
        switch (node.type) {
            case REF_EXPR:
                object = null;
                name = node.getText();
                break;
            case REF_DOT_EXPR:
                Object tempObject;
                if (node.children.get(1).type == NodeType.TOKEN) { // normal dot or optional
                    optional = node.children.get(1).token.type == TokenType.QUES_DOT;
                    name = node.children.get(2).getText();
                    try {
                        tempObject = Interpreter.eval(node.children.getFirst(), context);
                    } catch (Exception e) {
                        // try java interop
                        String base = node.children.getFirst().getText();
                        String path = base + "." + name;
                        if (Engine.JAVA_BRIDGE.typeExists(path)) {
                            tempObject = new JavaClass(path);
                            name = null;
                        } else if (Engine.JAVA_BRIDGE.typeExists(base)) {
                            tempObject = new JavaClass(base);
                        } else {
                            throw new RuntimeException("expression: " + base + " - " + e.getMessage());
                        }
                    }
                } else {
                    optional = true;
                    if (node.children.get(1).type == NodeType.REF_BRACKET_EXPR) { // optional bracket
                        tempObject = Interpreter.eval(node.children.getFirst(), context);
                        index = Interpreter.eval(node.children.get(1).children.get(2), context);
                    } else { // optional function call
                        tempObject = Interpreter.eval(node.children.getFirst(), context); // evalFnCall
                    }
                }
                object = tempObject;
                break;
            case REF_BRACKET_EXPR:
                object = Interpreter.eval(node.children.getFirst(), context);
                index = Interpreter.eval(node.children.get(2), context);
                break;
            case LIT_EXPR: // so MATH_PRE_EXP can call set() to update variable value
                object = Interpreter.eval(node.children.getFirst(), context);
                break;
            case PAREN_EXPR: // expression wrapped in round brackets that is invoked as a function, e.g. iife
                object = Interpreter.eval(node.children.get(1), context);
                break;
            case FN_CALL_EXPR:
                object = Interpreter.eval(node, context);
                break;
            default:
                throw new RuntimeException("cannot assign from: " + node);
        }
    }

    void set(Object value) {
        if (index instanceof Number num) {
            if (object instanceof List) { // most common case
                ((List<Object>) object).set(num.intValue(), value);
            } else if (object instanceof JsArray array) {
                array.set(num.intValue(), value);
            } else {
                throw new RuntimeException("cannot set by index [" + index + "]:" + value + " on (non-array): " + object);
            }
        } else {
            if (index != null) {
                name = index + "";
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
            } else if (object instanceof JavaClass jc) {
                jc.update(name, value);
            } else {
                try {
                    Engine.JAVA_BRIDGE.set(object, name, value);
                } catch (Exception e) {
                    logger.error("java bridge error: {}", e.getMessage());
                    throw new RuntimeException("cannot set '" + name + "'");
                }
            }
        }
    }

    Object get() {
        if (!functionCall && index instanceof Number n) {
            int i = (n).intValue();
            if (object instanceof List) {
                return ((List<Object>) object).get(i);
            }
            if (object instanceof JsArray array) {
                return array.get(i);
            }
            if (object instanceof String s) {
                return s.substring(i, i + 1);
            }
            if (object instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) object;
                String key = i + "";
                if (map.containsKey(key)) {
                    return map.get(key);
                }
            }
            if (object instanceof ObjectLike objectLike) {
                String key = i + "";
                Object value = objectLike.get(key);
                if (value != null) {
                    return value;
                }
            }
            throw new RuntimeException("get by index [" + i + "] for non-array: " + object);
        }
        if (index != null) {
            name = index + "";
        }
        if (name == null) {
            return object;
        }
        if (object instanceof List) {
            return (new JsArray((List<Object>) object).get(name));
        }
        if (object instanceof JsArray array) {
            return array.get(name);
        }
        if (object instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) object;
            // property access, most likely case
            if (map.containsKey(name)) {
                return map.get(name);
            }
            // try js object api
            JsObject jso = new JsObject(map);
            Object result = jso.get(name);
            if (result != null) {
                return result;
            }
            // java interop may have been the intent, will be attempted at the end
        }
        if (object instanceof ObjectLike objectLike) {
            return objectLike.get(name);
        }
        if (object instanceof String s) {
            return new JsString(s).get(name);
        }
        if (object instanceof Number num) {
            return new JsNumber(num).get(name);
        }
        if (object instanceof ZonedDateTime zdt) {
            return new JsDate(zdt).get(name);
        }
        if (object instanceof byte[] bytes) {
            return new JsBytes(bytes).get(name);
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
        if (object instanceof JsCallable callable) {
            // e.g. [].map.call([1, 2, 3], x => x * 2)
            // we convert to a JsFunction, so that prototype methods can be looked-up by name
            return new JsFunction() {
                @Override
                public Object call(Context context, Object... args) {
                    return callable.call(context, args);
                }
            }.get(name);
        }
        if (functionCall && object instanceof JavaMethods jm) {
            return new JavaInvokable(name, jm);
        }
        if (!functionCall && object instanceof JavaFields jf) {
            return jf.read(name);
        }
        try { // java interop
            if (functionCall) {
                return new JavaInvokable(name, new JavaObject(object));
            } else {
                return new JavaObject(object).get(name);
            }
        } catch (Exception e) { // java reflection failed on this object + name
            return Terms.UNDEFINED;
        }
    }

}

