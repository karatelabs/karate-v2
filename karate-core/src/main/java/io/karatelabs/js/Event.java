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

public class Event {

    public final Type type;
    public final Context context;
    public final Node node;

    Event(Type type, Context context, Node node) {
        this.type = type;
        this.context = context;
        this.node = node;
    }

    public enum Type {

        CONTEXT_ENTER,
        CONTEXT_EXIT,
        STATEMENT_ENTER,
        STATEMENT_EXIT,
        EXPRESSION_ENTER,
        EXPRESSION_EXIT

    }

    public interface Listener {

        default void onEvent(Event event) {

        }

        default Result onError(Event event, Exception e) {
            return null;
        }

        default void onFunctionCall(Context context, Object[] args) {

        }

        default void onVariableWrite(Context context, VariableType type, String name, Object value) {

        }

    }

    public static class Result {

        final public boolean ignoreError = true;
        final public Object returnValue;

        Result(Object returnValue) {
            this.returnValue = returnValue;
        }

    }

    public enum ExitType {

        CONDITION_FALSE,
        BREAK,
        CONTINUE,
        RETURN,
        THROW,
        ALL_ITERATIONS_COMPLETED

    }

    public enum VariableType {

        VAR,
        LET,
        CONST

    }

}
