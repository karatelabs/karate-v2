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

public class JsJava implements SimpleObject {

    final JavaBridge bridge;

    JsJava(JavaBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public Object get(String name) {
        if (bridge == null) {
            throw new RuntimeException("Java bridge not enabled");
        }
        return switch (name) {
            case "type" -> (Invokable) args -> bridge.forClass((String) args[0]);
            case "to" -> (Invokable) args -> {
                if (args[0] instanceof JavaAccess ja) {
                    return ja.toJava();
                }
                // TODO regex, functions, lambdas
                return null;
            };
            default -> throw new RuntimeException("no such api on Java: " + name);
        };
    }

}
