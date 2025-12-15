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
import java.util.Set;

import static io.karatelabs.js.TokenType.*;

abstract class Parser {

    static final Logger logger = LoggerFactory.getLogger(Parser.class);

    static class Marker {

        final int position;
        final Marker caller;
        final Node node;
        final int depth;

        public Marker(int position, Marker caller, Node node, int depth) {
            this.position = position;
            this.caller = caller;
            this.node = node;
            this.depth = depth;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (caller != null) {
                if (caller.caller != null) {
                    sb.append(caller.caller.node.type).append(" >> ");
                }
                sb.append(caller.node.type).append(" >> ");
            }
            sb.append('[');
            sb.append(node.type);
            sb.append(']');
            return sb.toString();
        }

    }

    final Resource resource;
    final List<Token> tokens;
    private final int size;

    private int position = 0;
    private Marker marker;

    // Error recovery infrastructure
    private boolean errorRecoveryEnabled = false;
    private final List<SyntaxError> errors = new ArrayList<>();

    Node markerNode() {
        return marker.node;
    }

    enum Shift {
        NONE, LEFT, RIGHT
    }

    Parser(Resource resource, boolean gherkin) {
        this.resource = resource;
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
        if (token.resource.isFile()) {
            System.err.println("file://" + token.resource.getUri().getPath() + ":" + token.getPositionDisplay() + " " + message);
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

    // ========== Error Recovery Methods ==========

    /**
     * Enable error recovery mode for lenient parsing.
     * When enabled, errors are collected instead of thrown,
     * allowing the parser to produce a partial AST.
     */
    protected void enableErrorRecovery() {
        this.errorRecoveryEnabled = true;
    }

    /**
     * @return true if error recovery mode is enabled
     */
    protected boolean isErrorRecoveryEnabled() {
        return errorRecoveryEnabled;
    }

    /**
     * @return list of syntax errors collected during parsing
     */
    public List<SyntaxError> getErrors() {
        return errors;
    }

    /**
     * @return true if any syntax errors were recorded
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Record an error without throwing. In error recovery mode,
     * adds to the error list. Otherwise, throws ParserException.
     */
    protected void recordError(String message) {
        if (errorRecoveryEnabled) {
            Token token = peekToken();
            errors.add(new SyntaxError(token, message));
        } else {
            error(message);
        }
    }

    /**
     * Record an error for expected node types.
     */
    protected void recordError(NodeType... expected) {
        if (errorRecoveryEnabled) {
            Token token = peekToken();
            errors.add(new SyntaxError(token, "expected: " + Arrays.asList(expected), expected[0]));
        } else {
            error(expected);
        }
    }

    /**
     * Record an error for expected token types.
     */
    protected void recordError(TokenType... expected) {
        if (errorRecoveryEnabled) {
            Token token = peekToken();
            errors.add(new SyntaxError(token, "expected: " + Arrays.asList(expected)));
        } else {
            error(expected);
        }
    }

    /**
     * Skip tokens until we find a recovery point.
     * @param recoveryTokens tokens that indicate a safe recovery point
     * @return true if a recovery token was found, false if EOF reached
     */
    protected boolean recoverTo(TokenType... recoveryTokens) {
        Set<TokenType> recoverySet = Set.of(recoveryTokens);
        while (true) {
            TokenType current = peek();
            if (current == EOF || recoverySet.contains(current)) {
                return current != EOF;
            }
            consumeNext();
        }
    }

    /**
     * Enter with recovery - creates incomplete node on mismatch instead of failing.
     * @param type the node type to enter
     * @param startTokens expected start tokens
     * @return true if entered (either with matching token or in recovery mode)
     */
    protected boolean enterWithRecovery(NodeType type, TokenType... startTokens) {
        if (peekAnyOf(startTokens)) {
            return enter(type, startTokens);
        }
        if (errorRecoveryEnabled) {
            // Create incomplete node anyway for IDE support
            enter(type);
            recordError("expected: " + Arrays.asList(startTokens));
            return true;
        }
        return false;
    }

    /**
     * Exit that tolerates incomplete nodes.
     * Even if the node is incomplete, it is added to the parent.
     */
    protected boolean exitSoft() {
        return exit(true, false, Shift.NONE);
    }

    /**
     * Exit that marks node as having errors.
     */
    protected boolean exitWithError(String message) {
        recordError(message);
        return exit(true, false, Shift.NONE);
    }

    // ========== End Error Recovery Methods ==========

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
                    Node prev = parent.removeFirst(); // remove previous sibling
                    node.addFirst(prev); // and make it the first child
                    parent.add(node);
                    break;
                case NONE:
                    parent.add(node);
                    break;
                case RIGHT:
                    Node prevSibling = parent.removeFirst(); // remove previous sibling
                    if (prevSibling.type == node.type) {
                        Node newNode = new Node(node.type);
                        parent.add(newNode);
                        newNode.add(prevSibling.get(0)); // prev lhs
                        newNode.add(prevSibling.get(1)); // operator
                        Node newRhs = new Node(node.type);
                        newNode.add(newRhs);
                        newRhs.add(prevSibling.get(2)); // prev rhs becomes current lhs
                        newRhs.add(node.get(0)); // operator
                        newRhs.add(node.get(1)); // current rhs
                    } else {
                        node.addFirst(prevSibling); // move previous sibling to first child
                        parent.add(node);
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
        List<Token> comments = new ArrayList<>();
        List<Token> list = new ArrayList<>();
        Token prev = null;
        int line = 0;
        int col = 0;
        long pos = 0;
        TokenType type;
        try {
            do {
                type = lexer.yylex();
                String text = lexer.yytext();
                Token token = new Token(resource, type, pos, line, col, text);
                int length = lexer.yylength();
                pos += length;
                if (type == WS_LF || type == T_STRING || type == B_COMMENT) {
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
                    if (!comments.isEmpty()) {
                        token.comments = comments;
                        comments = new ArrayList<>();
                    }
                } else if (type == L_COMMENT || type == G_COMMENT || type == B_COMMENT) {
                    comments.add(token);
                }
            } while (type != EOF);
        } catch (Throwable e) {
            String message = "lexer failed at [" + (line + 1) + ":" + (col + 1) + "] prev: " + prev;
            if (resource.isFile()) {
                message = message + "\n" + resource.getRelativePath();
            }
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
        return marker.node.getLast().token.type;
    }

    void consumeNext() {
        marker.node.add(new Node(next()));
    }

    Token next() {
        return position == size ? Token.EMPTY : tokens.get(position++);
    }

}
