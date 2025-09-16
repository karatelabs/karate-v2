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

class JsUint8Array extends JsArray implements JavaMirror {

    private final byte[] buffer;

    JsUint8Array(int length) {
        this.buffer = new byte[length];
    }

    JsUint8Array(byte[] bytes) {
        this.buffer = bytes.clone();
    }

    public byte[] toByteArray() {
        return buffer.clone();
    }

    @Override
    public Object get(int index) {
        if (index >= 0 && index < buffer.length) {
            return buffer[index] & 0xFF; // Return as unsigned
        }
        return Terms.UNDEFINED;
    }

    @Override
    public void set(int index, Object value) {
        if (index >= 0 && index < buffer.length && value instanceof Number) {
            buffer[index] = (byte) (((Number) value).intValue() & 0xFF);
        }
    }

    @Override
    public int size() {
        return buffer.length;
    }

    @Override
    public Object toJava() {
        return buffer;
    }

}
