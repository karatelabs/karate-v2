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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.CharArrayReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

abstract class Parser {

    static final Logger logger = LoggerFactory.getLogger(Parser.class);

    final List<Chunk> chunks;
    private final int size;

    int position = 0;
    Marker marker;

    enum Shift {
        NONE, LEFT, RIGHT
    }

    Parser(Source source, boolean gherkin) {
        chunks = getChunks(source, gherkin);
        size = chunks.size();
        marker = new Marker(position, null, new Node(Type.ROOT), -1);
    }

    public abstract Node parse();

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, position - 7);
        int end = Math.min(position + 7, size);
        for (int i = start; i < end; i++) {
            if (i == 0) {
                sb.append("| ");
            }
            if (i == position) {
                sb.append(">>");
            }
            sb.append(chunks.get(i));
            sb.append(' ');
        }
        if (position == size) {
            sb.append(">>");
        }
        sb.append("|");
        sb.append("\ncurrent node: ");
        sb.append(marker);
        return sb.toString();
    }

    void error(String message) {
        Chunk chunk;
        if (position == size) {
            chunk = chunks.get(position - 1);
        } else {
            chunk = chunks.get(position);
        }
        throw new ParserException(message + "\n"
                + chunk.getPositionDisplay()
                + " " + chunk + "\nparser state: " + this);
    }

    void error(Type... expected) {
        error("expected: " + Arrays.asList(expected));
    }

    void error(Token... expected) {
        error("expected: " + Arrays.asList(expected));
    }

    void enter(Type type) {
        enterIf(type, null);
    }

    boolean enter(Type type, Token... tokens) {
        return enterIf(type, tokens);
    }

    boolean enterIf(Type type, Token[] tokens) {
        if (tokens != null) {
            if (!peekAnyOf(tokens)) {
                return false;
            }
        }
        Node node = new Node(type);
        Marker caller = marker;
        marker = new Marker(position, caller, node, marker.depth + 1);
        if (marker.depth > 100) {
            throw new ParserException("too much recursion");
        }
        if (tokens != null) {
            consumeNext();
        }
        return true;
    }

    boolean exit() {
        return exit(true, false, Shift.NONE);
    }

    boolean exit(boolean result, boolean mandatory) {
        return exit(result, mandatory, Shift.NONE);
    }

    void exit(Shift shift) {
        exit(true, false, shift);
    }

    private boolean exit(boolean result, boolean mandatory, Shift shift) {
        if (mandatory && !result) {
            error(marker.node.type);
        }
        if (result) {
            Node parent = marker.caller.node;
            Node node = marker.node;
            switch (shift) {
                case LEFT:
                    Node prev = parent.children.remove(0); // remove previous sibling
                    node.children.add(0, prev); // and make it the first child
                    parent.children.add(node);
                    break;
                case NONE:
                    parent.children.add(node);
                    break;
                case RIGHT:
                    Node prevSibling = parent.children.remove(0); // remove previous sibling
                    if (prevSibling.type == node.type) {
                        Node newNode = new Node(node.type);
                        parent.children.add(newNode);
                        newNode.children.add(prevSibling.children.get(0)); // prev lhs
                        newNode.children.add(prevSibling.children.get(1)); // operator
                        Node newRhs = new Node(node.type);
                        newNode.children.add(newRhs);
                        newRhs.children.add(prevSibling.children.get(2)); // prev rhs becomes current lhs
                        newRhs.children.add(node.children.get(0)); // operator
                        newRhs.children.add(node.children.get(1)); // current rhs
                    } else {
                        node.children.add(0, prevSibling); // move previous sibling to first child
                        parent.children.add(node);
                    }
            }
        } else {
            position = marker.position;
        }
        marker = marker.caller;
        return result;
    }

    static List<Chunk> getChunks(Source source, boolean gherkin) {
        CharArrayReader reader = new CharArrayReader(source.text.toCharArray());
        Lexer lexer = new Lexer(reader);
        if (gherkin) {
            lexer.yybegin(Lexer.GHERKIN);
        }
        List<Chunk> list = new ArrayList<>();
        Chunk prev = null;
        int line = 0;
        int col = 0;
        long pos = 0;
        try {
            while (true) {
                Token token = lexer.yylex();
                if (token == Token.EOF) {
                    list.add(new Chunk(source, token, pos, line, col, ""));
                    break;
                }
                String text = lexer.yytext();
                Chunk chunk = new Chunk(source, token, pos, line, col, text);
                int length = lexer.yylength();
                pos += length;
                if (token == Token.WS_LF || token == Token.B_COMMENT || token == Token.T_STRING) {
                    for (int i = 0; i < length; i++) {
                        if (text.charAt(i) == '\n') {
                            col = 0;
                            line++;
                        } else {
                            col++;
                        }
                    }
                } else {
                    col += length;
                }
                chunk.prev = prev;
                if (prev != null) {
                    prev.next = chunk;
                }
                prev = chunk;
                if (token.primary) {
                    list.add(chunk);
                }
            }
        } catch (Throwable e) {
            String message = "lexer failed at [" + (line + 1) + ":" + (col + 1) + "] prev: " + prev + "\n" + source.getStringForLog();
            throw new ParserException(message, e);
        }
        return list;
    }

    boolean peekIf(Token token) {
        if (position == size) {
            return false;
        }
        return chunks.get(position).token == token;
    }

    Token peek() {
        if (position == size) {
            return Token.EOF;
        }
        return chunks.get(position).token;
    }

    Token peekPrev() {
        if (position == 0) {
            return Token.EOF;
        }
        return chunks.get(position - 1).token;
    }

    void consumeNext() {
        Node node = new Node(chunks.get(position++));
        marker.node.children.add(node);
    }

    void consume(Token token) {
        if (!consumeIf(token)) {
            error(token);
        }
    }

    boolean consumeIf(Token token) {
        if (peekIf(token)) {
            Node node = new Node(chunks.get(position++));
            marker.node.children.add(node);
            return true;
        }
        return false;
    }

    boolean peekAnyOf(Token... tokens) {
        for (Token token : tokens) {
            if (peekIf(token)) {
                return true;
            }
        }
        return false;
    }

    boolean anyOf(Token... tokens) {
        for (Token token : tokens) {
            if (consumeIf(token)) {
                return true;
            }
        }
        return false;
    }

}
