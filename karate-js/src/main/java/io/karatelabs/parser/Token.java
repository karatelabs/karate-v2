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
package io.karatelabs.parser;

import io.karatelabs.common.Resource;

import java.util.Collections;
import java.util.List;

public class Token {

    public static final Token EMPTY = new Token(Resource.text(""), TokenType.EOF, 0, 0, 0, 0);

    public final Resource resource;
    public final long pos;
    public final int line;
    public final int col;
    public final TokenType type;
    final short length;

    List<Token> comments;
    Token prev;
    Token next;

    public Token(Resource resource, TokenType type, long pos, int line, int col, int length) {
        this.resource = resource;
        this.type = type;
        this.pos = pos;
        this.line = line;
        this.col = col;
        this.length = (short) length;
    }

    public String getText() {
        int start = (int) pos;
        return resource.getText().substring(start, start + length);
    }

    public int getLength() {
        return length;
    }

    public Token getNextPrimary() {
        Token temp = this;
        do {
            temp = temp.next;
        } while (!temp.type.primary);
        // this will never be null, because the last token is always EOF
        // and EOF is considered "primary" unlike white-space or comments
        return temp;
    }

    public String getLineText() {
        return resource.getLine(line);
    }

    public String getPositionDisplay() {
        return (line + 1) + ":" + (col + 1);
    }

    public List<Token> getComments() {
        return comments == null ? Collections.emptyList() : comments;
    }

    @Override
    public String toString() {
        return switch (type) {
            case WS -> "_";
            case WS_LF -> "_\\n_";
            case EOF -> "_EOF_";
            default -> getText();
        };
    }

}
