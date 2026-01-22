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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class Node implements Iterable<Node> {

    static final Logger logger = LoggerFactory.getLogger(Node.class);

    // Shared empty list for nodes without children (TOKEN nodes never have children)
    private static final List<Node> EMPTY_CHILDREN = Collections.emptyList();

    public final NodeType type;
    public final Token token;
    // Lazy init: null until first child added, saves memory for TOKEN nodes
    private List<Node> children;

    private Node parent;

    // For read operations: return empty list if null
    private List<Node> children() {
        return children == null ? EMPTY_CHILDREN : children;
    }

    // For write operations: create list if null (capacity 4 for typical AST nodes)
    private List<Node> ensureChildren() {
        if (children == null) {
            children = new ArrayList<>(4);
        }
        return children;
    }

    public Node(NodeType type) {
        this.type = type;
        token = Token.EMPTY;
    }

    public Node(Token token) {
        this.token = token;
        type = NodeType.TOKEN;
    }

    public Node getParent() {
        return parent;
    }

    public boolean isToken() {
        return type == NodeType.TOKEN;
    }

    public boolean isEof() {
        return type == NodeType.TOKEN && token.type == TokenType.EOF;
    }

    public Token getFirstToken() {
        if (isToken()) {
            return token;
        }
        if (children().isEmpty()) {
            return Token.EMPTY;
        }
        return children().getFirst().getFirstToken();
    }

    public Token getLastToken() {
        if (isToken()) {
            return token;
        }
        if (children().isEmpty()) {
            return Token.EMPTY;
        }
        return children().getLast().getLastToken();
    }

    public String toStringError(String message) {
        Token first = getFirstToken();
        if (first.resource.isFile()) {
            return first.getPositionDisplay() + " " + type + "\n" + first.resource.getRelativePath() + "\n" + message;
        } else if (first.line == 0) {
            return message;
        } else {
            return first.getPositionDisplay() + " " + type + "\n" + message;
        }
    }

    @Override
    public String toString() {
        if (isToken()) {
            return token.text;
        }
        return "[" + type + "] " + getTextIncludingWhitespace();
    }

    public String toStringWithoutType() {
        if (isToken()) {
            return token.text;
        }
        StringBuilder sb = new StringBuilder();
        List<Node> c = children();
        for (int i = 0; i < c.size(); i++) {
            if (i != 0) {
                sb.append(' ');
            }
            sb.append(c.get(i).toStringWithoutType());
        }
        return sb.toString();
    }

    public Node findFirstChild(NodeType type) {
        for (Node child : children()) {
            if (child.type == type) {
                return child;
            }
            Node temp = child.findFirstChild(type);
            if (temp != null) {
                return temp;
            }
        }
        return null;
    }

    public List<Node> findAll(NodeType type) {
        List<Node> results = new ArrayList<>();
        findAll(type, results);
        return results;
    }

    private void findAll(NodeType type, List<Node> results) {
        for (Node child : children()) {
            if (child.type == type) {
                results.add(child);
            }
            child.findAll(type, results);
        }
    }

    public Node findFirstChild(TokenType token) {
        for (Node child : children()) {
            if (child.token.type == token) {
                return child;
            }
            Node temp = child.findFirstChild(token);
            if (temp != null) {
                return temp;
            }
        }
        return null;
    }

    public Node findParent(NodeType type) {
        Node temp = this.parent;
        while (temp != null && temp.type != type) {
            temp = temp.parent;
        }
        return temp;
    }

    public List<Node> findImmediateChildren(NodeType type) {
        List<Node> results = new ArrayList<>();
        for (Node child : children()) {
            if (child.type == type) {
                results.add(child);
            }
        }
        return results;
    }

    public List<Node> findChildren(TokenType token) {
        List<Node> results = new ArrayList<>();
        findChildren(token, results);
        return results;
    }

    private void findChildren(TokenType token, List<Node> results) {
        for (Node child : children()) {
            if (!child.isToken()) {
                child.findChildren(token, results);
            } else if (child.token.type == token) {
                results.add(child);
            }
        }
    }

    public String getText() {
        if (isToken()) {
            return token.text;
        }
        StringBuilder sb = new StringBuilder();
        for (Node child : children()) {
            sb.append(child.getText());
        }
        return sb.toString();
    }

    public String getTextIncludingWhitespace() {
        if (isToken()) {
            return token.text;
        }
        int start = (int) getFirstToken().pos;
        Token last = getLastToken();
        int end = ((int) last.pos) + last.text.length();
        return last.resource.getText().substring(start, end);
    }

    public Node removeFirst() {
        return ensureChildren().removeFirst();
    }

    public void addFirst(Node child) {
        child.parent = this;
        ensureChildren().addFirst(child);
    }

    public void add(Node child) {
        child.parent = this;
        ensureChildren().add(child);
    }

    public Node getFirst() {
        return children().getFirst();
    }

    public Node getLast() {
        return children().getLast();
    }

    public Node get(int index) {
        return children().get(index);
    }

    public int size() {
        return children().size();
    }

    public boolean isEmpty() {
        return children().isEmpty();
    }

    @Override
    public Iterator<Node> iterator() {
        return children().iterator();
    }

}
