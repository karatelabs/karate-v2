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
package io.karatelabs.match;

public class Match {

    public enum Type {

        EQUALS(false, false, true, false, false, false, false, false),
        NOT_EQUALS(false, true, true, false, false, false, false, false),
        CONTAINS(false, false, false, false, true, false, false, false),
        NOT_CONTAINS(false, true, false, false, true, false, false, false),
        CONTAINS_ONLY(false, false, false, false, true, false, true, false),
        CONTAINS_ANY(false, false, false, false, true, true, false, false),
        CONTAINS_DEEP(false, false, false, false, true, false, false, true),
        CONTAINS_ONLY_DEEP(false, false, false, false, true, false, true, true),
        CONTAINS_ANY_DEEP(false, false, false, false, true, true, false, true),
        EACH_EQUALS(true, false, true, false, false, false, false, false),
        EACH_NOT_EQUALS(true, true, true, false, false, false, false, false),
        EACH_CONTAINS(true, false, false, false, true, false, false, false),
        EACH_NOT_CONTAINS(true, true, false, false, true, false, false, false),
        EACH_CONTAINS_ONLY(true, false, false, false, true, false, true, false),
        EACH_CONTAINS_ANY(true, false, false, false, true, true, false, false),
        EACH_CONTAINS_DEEP(true, false, false, false, true, false, false, true);

        public final boolean each;
        public final boolean not;
        public final boolean equals;
        public final boolean within;
        public final boolean contains;
        public final boolean any;
        public final boolean only;
        public final boolean deep;

        Type(boolean each, boolean not, boolean equals, boolean within, boolean contains, boolean any, boolean only, boolean deep) {
            this.each = each;
            this.not = not;
            this.equals = equals;
            this.within = within;
            this.contains = contains;
            this.any = any;
            this.only = only;
            this.deep = deep;
        }

    }

    public static Value evaluate(Object actual) {
        return new Value(Value.parseIfJsonOrXmlString(actual), false);
    }

    public static Value that(Object actual) {
        return new Value(Value.parseIfJsonOrXmlString(actual), true);
    }

}
