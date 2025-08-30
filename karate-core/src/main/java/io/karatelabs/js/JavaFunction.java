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

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class JavaFunction implements JsCallable {

    final JsCallable callable;

    @SuppressWarnings("unchecked")
    JavaFunction(Object o) {
        if (o instanceof Function) {
            callable = (c, args) -> ((Function<Object, Object>) o).apply(args[0]);
        } else if (o instanceof Runnable) {
            callable = (c, args) -> {
                ((Runnable) o).run();
                return null;
            };
        } else if (o instanceof Callable) {
            callable = (c, args) -> {
                try {
                    return ((Callable<Object>) o).call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        } else if (o instanceof Consumer) {
            callable = (c, args) -> {
                ((Consumer<Object>) o).accept(args[0]);
                return null;
            };
        } else if (o instanceof Supplier) {
            callable = (c, args) -> ((Supplier<Object>) o).get();
        } else if (o instanceof Predicate) {
            callable = (c, args) -> ((Predicate<Object>) o).test(args[0]);
        } else {
            throw new RuntimeException("cannot convert to java function: " + o);
        }
    }

    @Override
    public Object call(Context context, Object... args) {
        return callable.call(context, args);
    }

}
