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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Node {

    static final Logger logger = LoggerFactory.getLogger(Node.class);

    public final NodeType type;
    public final Token token;
    public final List<Node> children = new ArrayList<>();

    public Node(NodeType type) {
        this.type = type;
        token = Token.EMPTY;
    }

    public Node(Token chunk) {
        this.token = chunk;
        type = NodeType.TOKEN;
    }

    public boolean isToken() {
        return type == NodeType.TOKEN;
    }

    public Token getFirstToken() {
        if (isToken()) {
            return token;
        }
        if (children.isEmpty()) {
            return Token.EMPTY;
        }
        return children.get(0).getFirstToken();
    }

    public String toStringError(String message) {
        Token first = getFirstToken();
        if (first.resource.isUrlResource()) {
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
        StringBuilder sb = new StringBuilder();
        for (Node child : children) {
            if (sb.length() != 0) {
                sb.append(' ');
            }
            sb.append(child.toString());
        }
        return sb.toString();
    }

    public Node findFirst(NodeType type) {
        for (Node child : children) {
            if (child.type == type) {
                return child;
            }
            Node temp = child.findFirst(type);
            if (temp != null) {
                return temp;
            }
        }
        return null;
    }

    public Node findFirst(TokenType token) {
        for (Node child : children) {
            if (child.token.type == token) {
                return child;
            }
            Node temp = child.findFirst(token);
            if (temp != null) {
                return temp;
            }
        }
        return null;
    }

    public List<Node> findChildrenOfType(NodeType type) {
        List<Node> results = new ArrayList<>();
        for (Node child : children) {
            if (child.type == type) {
                results.add(child);
            }
        }
        return results;
    }

    public List<Node> findAll(TokenType token) {
        List<Node> results = new ArrayList<>();
        findAll(token, results);
        return results;
    }

    private void findAll(TokenType token, List<Node> results) {
        for (Node child : children) {
            if (!child.isToken()) {
                child.findAll(token, results);
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
        for (Node child : children) {
            sb.append(child.getText());
        }
        return sb.toString();
    }

}
