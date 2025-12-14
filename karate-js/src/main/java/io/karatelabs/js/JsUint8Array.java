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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

class JsUint8Array extends JsArray implements JavaMirror {

    private final byte[] buffer;

    JsUint8Array(int length) {
        this.buffer = new byte[length];
    }

    JsUint8Array(byte[] bytes) {
        this.buffer = bytes.clone();
    }

    @Override
    public Object get(int index) {
        if (index >= 0 && index < buffer.length) {
            return buffer[index] & 0xFF; // return as unsigned
        }
        return Terms.UNDEFINED;
    }

    @Override
    public void set(int index, Object value) {
        if (index >= 0 && index < buffer.length && value instanceof Number v) {
            buffer[index] = (byte) (v.intValue() & 0xFF);
        }
    }

    @Override
    public Iterator<KeyValue> iterator() {
        return new Iterator<>() {
            int index = 0;

            @Override
            public boolean hasNext() {
                return index < buffer.length;
            }

            @Override
            public KeyValue next() {
                int i = index++;
                return new KeyValue(_this, i, i + "", buffer[i] & 0xFF);
            }
        };
    }

    @Override
    public List<Object> toList() {
        ArrayList<Object> list = new ArrayList<>(buffer.length);
        for (KeyValue kv : _this) {
            list.add(kv.value);
        }
        return list;
    }

    @Override
    public int size() {
        return buffer.length;
    }

    @Override
    JsUint8Array fromThis(Context context) {
        Object thisObject = context.getThisObject();
        if (thisObject instanceof JsUint8Array arr) {
            return arr;
        }
        if (thisObject instanceof byte[] bytes) {
            return new JsUint8Array(bytes);
        }
        return this;
    }

    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                if ("length".equals(propName)) {
                    return buffer.length;
                }
                return null; // delegate to wrapped prototype
            }
        };
    }

    @Override
    public Object getJavaValue() {
        return buffer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object call(Context context, Object... args) {
        if (args.length == 0) {
            return new byte[0];
        }
        Object arg = args[0];
        if (arg instanceof Number n) {
            return new byte[n.intValue()];
        }
        if (arg instanceof List) {
            List<Object> items = (List<Object>) arg;
            byte[] bytes = new byte[items.size()];
            for (int i = 0; i < items.size(); i++) {
                Object val = items.get(i);
                if (val instanceof Number n) {
                    bytes[i] = (byte) (n.intValue() & 0xFF);
                } else {
                    bytes[i] = 0;
                }
            }
            return bytes;
        }
        return new byte[0];
    }

}
