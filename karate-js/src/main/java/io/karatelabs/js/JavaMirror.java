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

/**
 * Interface for JS objects that wrap Java values.
 * <p>
 * Two methods provide access to the underlying value:
 * <ul>
 *   <li>{@link #getJavaValue()} - Returns the idiomatic Java type for external use (e.g., JsDate → Date)</li>
 *   <li>{@link #getJsValue()} - Returns the raw internal value for internal operations (e.g., JsDate → Long millis)</li>
 * </ul>
 */
public interface JavaMirror {

    /**
     * Returns the idiomatic Java representation of this object.
     * Used when values leave the JS engine and need to be consumed by Java code.
     * <p>
     * Examples: JsNumber → Number, JsDate → Date, JsArray → List
     */
    Object getJavaValue();

    /**
     * Returns the raw internal value for use in JS operations (comparison, arithmetic, etc.).
     * This enables a consistent "unwrap first, then switch on raw types" pattern in Terms.java.
     * <p>
     * Examples: JsNumber → Number, JsDate → Long (millis), JsArray → List
     * <p>
     * Default implementation delegates to {@link #getJavaValue()} which is correct for most types.
     * Override when internal representation differs from external (e.g., JsDate stores long millis).
     */
    default Object getJsValue() {
        return getJavaValue();
    }

}
