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

import io.karatelabs.common.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.CharArrayReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.karatelabs.js.TokenType.*;

abstract class Parser {

    static final Logger logger = LoggerFactory.getLogger(Parser.class);

    final List<Token> tokens;
    private final int size;

    private int position = 0;
    private Marker marker;

    Node markerNode() {
        return marker.node;
    }

    enum Shift {
        NONE, LEFT, RIGHT
    }

    Parser(Resource resource, boolean gherkin) {
        tokens = getTokens(resource, gherkin);
        size = tokens.size();
        marker = new Marker(position, null, new Node(NodeType.ROOT), -1);
    }

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
            sb.append(tokens.get(i));
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
        Token token;
        if (position == size) {
            token = tokens.get(position - 1);
        } else {
            token = tokens.get(position);
        }
        throw new ParserException(message + "\n"
                + token.getPositionDisplay()
                + " " + token + "\nparser state: " + this);
    }

    void error(NodeType... expected) {
        error("expected: " + Arrays.asList(expected));
    }

    void error(TokenType... expected) {
        error("expected: " + Arrays.asList(expected));
    }

    void enter(NodeType type) {
        enterIf(type, null);
    }

    boolean enter(NodeType type, TokenType... tokens) {
        return enterIf(type, tokens);
    }

    boolean enterIf(NodeType type, TokenType[] tokens) {
        if (tokens != null) {
            if (!peekAnyOf(tokens)) {
                return false;
            }
        }
        // new marker has reference to caller marker set
        marker = new Marker(position, marker, new Node(type), marker.depth + 1);
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

    static List<Token> getTokens(Resource resource, boolean gherkin) {
        CharArrayReader reader = new CharArrayReader(resource.getText().toCharArray());
        Lexer lexer = new Lexer(reader);
        if (gherkin) {
            lexer.yybegin(Lexer.GHERKIN);
        }
        List<Token> list = new ArrayList<>();
        Token prev = null;
        int line = 0;
        int col = 0;
        long pos = 0;
        try {
            while (true) {
                TokenType type = lexer.yylex();
                if (type == EOF) {
                    list.add(new Token(resource, type, pos, line, col, ""));
                    break;
                }
                String text = lexer.yytext();
                Token token = new Token(resource, type, pos, line, col, text);
                int length = lexer.yylength();
                pos += length;
                if (type == WS_LF || type == B_COMMENT || type == T_STRING) {
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
                token.prev = prev;
                if (prev != null) {
                    prev.next = token;
                }
                prev = token;
                if (type.primary) {
                    list.add(token);
                }
            }
        } catch (Throwable e) {
            String message = "lexer failed at [" + (line + 1) + ":" + (col + 1) + "] prev: " + prev + "\n" + resource.getRelativePath();
            throw new ParserException(message, e);
        }
        return list;
    }

    private Token cachedPeek = Token.EMPTY;
    private int cachedPeekPos = -1;

    TokenType peek() {
        return peekToken().type;
    }

    Token peekToken() {
        if (cachedPeekPos != position) {
            cachedPeekPos = position;
            cachedPeek = (position == size) ? Token.EMPTY : tokens.get(position);
        }
        return cachedPeek;
    }

    void consume(TokenType token) {
        if (!consumeIf(token)) {
            error(token);
        }
    }

    boolean anyOf(TokenType... tokens) {
        for (TokenType token : tokens) {
            if (consumeIf(token)) {
                return true;
            }
        }
        return false;
    }

    boolean consumeIf(TokenType token) {
        if (peekIf(token)) {
            consumeNext();
            return true;
        }
        return false;
    }

    boolean peekIf(TokenType token) {
        return peek() == token;
    }

    boolean peekAnyOf(TokenType... tokens) {
        for (TokenType token : tokens) {
            if (peekIf(token)) {
                return true;
            }
        }
        return false;
    }

    TokenType lastConsumed() {
        return marker.node.children.getLast().token.type;
    }

    void consumeNext() {
        marker.node.children.add(new Node(next()));
    }

    Token next() {
        return position == size ? Token.EMPTY : tokens.get(position++);
    }

}
