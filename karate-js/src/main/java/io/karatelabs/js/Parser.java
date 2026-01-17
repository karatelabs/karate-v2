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
    protected final boolean errorRecoveryEnabled;
    private final List<SyntaxError> errors = new ArrayList<>();
    private int lastRecoveryPosition = -1;

    Node markerNode() {
        return marker.node;
    }

    boolean isCallerType(NodeType type) {
        return marker.caller != null && marker.caller.node.type == type;
    }

    enum Shift {
        NONE, LEFT, RIGHT
    }

    Parser(Resource resource, boolean gherkin, boolean errorRecovery) {
        this.resource = resource;
        this.errorRecoveryEnabled = errorRecovery;
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
        Token token = peekToken();
        if (errorRecoveryEnabled) {
            errors.add(new SyntaxError(token, message));
            return;
        }
        if (token.resource.isFile()) {
            System.err.println("file://" + token.resource.getUri().getPath() + ":" + token.getPositionDisplay() + " " + message);
        }
        throw new ParserException(message + "\n"
                + token.getPositionDisplay()
                + " " + token + "\nparser state: " + this);
    }

    void error(NodeType... expected) {
        if (errorRecoveryEnabled) {
            errors.add(new SyntaxError(peekToken(), "expected: " + Arrays.asList(expected), expected[0]));
            return;
        }
        error("expected: " + Arrays.asList(expected));
    }

    void error(TokenType... expected) {
        if (errorRecoveryEnabled) {
            errors.add(new SyntaxError(peekToken(), "expected: " + Arrays.asList(expected)));
            return;
        }
        error("expected: " + Arrays.asList(expected));
    }

    // ========== Error Recovery Methods ==========

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
     * Skip tokens until we find a recovery point.
     * Includes infinite loop detection - if called from the same position twice,
     * forces skip of at least one token to guarantee progress.
     * @param recoveryTokens tokens that indicate a safe recovery point
     * @return true if a recovery token was found, false if EOF reached
     */
    protected boolean recoverTo(TokenType... recoveryTokens) {
        // Infinite loop safeguard: if recovering from same position, force progress
        if (position == lastRecoveryPosition) {
            if (peek() != EOF) {
                consumeNext();
            }
        }
        lastRecoveryPosition = position;
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
     * Consume token if present, or record error if in recovery mode.
     * @param token the expected token type
     * @return true if consumed successfully or in recovery mode
     */
    protected boolean consumeSoft(TokenType token) {
        if (consumeIf(token)) {
            return true;
        }
        error(token);
        return errorRecoveryEnabled; // Continue if recovering, never reached otherwise
    }

    /**
     * Exit that tolerates incomplete nodes.
     * Even if the node is incomplete, it is added to the parent.
     */
    protected boolean exitSoft() {
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
        JsLexer lexer = gherkin
                ? new GherkinLexer(resource)
                : new JsLexer(resource);

        List<Token> list = new ArrayList<>();
        List<Token> comments = new ArrayList<>();
        Token prev = null;
        Token token;

        do {
            token = lexer.nextToken();
            token.prev = prev;
            if (prev != null) {
                prev.next = token;
            }
            prev = token;

            if (token.type.primary) {
                list.add(token);
                if (!comments.isEmpty()) {
                    token.comments = comments;
                    comments = new ArrayList<>();
                }
            } else if (token.type == L_COMMENT || token.type == G_COMMENT || token.type == B_COMMENT) {
                comments.add(token);
            }
        } while (token.type != EOF);

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
