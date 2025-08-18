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

import io.karatelabs.common.Resource;

public class Token {

    static final Token _EMPTY = new Token(Resource.text(""), TokenType.EOF, 0, 0, 0, "");

    Resource resource;
    public final long pos;
    public final int line;
    public final int col;
    public final TokenType type;
    public final String text;
    Token prev;
    Token next;

    public Token(Resource resource, TokenType type, long pos, int line, int col, String text) {
        this.resource = resource;
        this.type = type;
        this.pos = pos;
        this.line = line;
        this.col = col;
        this.text = text;
    }

    public String getLineText() {
        return resource.getLine(line);
    }

    public String getPositionDisplay() {
        return "[" + (line + 1) + ":" + (col + 1) + "]";
    }

    @Override
    public String toString() {
        switch (type) {
            case WS:
                return "_";
            case WS_LF:
                return "_\\n_";
        }
        return text;
    }

}
