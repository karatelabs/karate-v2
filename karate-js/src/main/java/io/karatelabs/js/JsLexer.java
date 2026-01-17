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

import java.util.ArrayDeque;

import static io.karatelabs.js.TokenType.*;

/**
 * Hand-rolled lexer for JavaScript. This implementation is designed to be
 * portable to other languages (JS, Rust) and serves as the canonical spec.
 */
public class JsLexer {

    protected final Resource resource;
    protected final String source;
    protected final int length;

    protected int pos;
    protected int line;
    protected int col;
    protected int tokenStart;
    protected int tokenLine;
    protected int tokenCol;

    private boolean regexAllowed = true;
    private final ArrayDeque<LexerState> stateStack = new ArrayDeque<>();

    protected enum LexerState {
        INITIAL, TEMPLATE, PLACEHOLDER
    }

    public JsLexer(Resource resource) {
        this.resource = resource;
        this.source = resource.getText();
        this.length = source.length();
        this.pos = 0;
        this.line = 0;
        this.col = 0;
        stateStack.push(LexerState.INITIAL);
    }

    // ========== Public API ==========

    public Token nextToken() {
        tokenStart = pos;
        tokenLine = line;
        tokenCol = col;
        TokenType type = scanToken();
        String text = source.substring(tokenStart, pos);
        if (type.regexAllowed != null) {
            regexAllowed = type.regexAllowed;
        }
        return new Token(resource, type, tokenStart, tokenLine, tokenCol, text);
    }

    // ========== State Management ==========

    protected LexerState currentState() {
        return stateStack.peek();
    }

    protected void pushState(LexerState state) {
        stateStack.push(state);
    }

    protected LexerState popState() {
        if (stateStack.size() > 1) {
            return stateStack.pop();
        }
        return stateStack.peek();
    }

    // ========== Character Utilities ==========

    protected boolean isAtEnd() {
        return pos >= length;
    }

    protected char peek() {
        return isAtEnd() ? '\0' : source.charAt(pos);
    }

    protected char peek(int offset) {
        int index = pos + offset;
        return (index < 0 || index >= length) ? '\0' : source.charAt(index);
    }

    protected char advance() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 0;
        } else {
            col++;
        }
        return c;
    }

    protected boolean match(char expected) {
        if (isAtEnd() || source.charAt(pos) != expected) {
            return false;
        }
        advance();
        return true;
    }

    protected boolean matchSequence(String seq) {
        if (pos + seq.length() > length) {
            return false;
        }
        for (int i = 0; i < seq.length(); i++) {
            if (source.charAt(pos + i) != seq.charAt(i)) {
                return false;
            }
        }
        for (int i = 0; i < seq.length(); i++) {
            advance();
        }
        return true;
    }

    // ========== Main Scanner ==========

    protected TokenType scanToken() {
        if (isAtEnd()) {
            return EOF;
        }

        LexerState state = currentState();
        if (state == LexerState.TEMPLATE) {
            return scanTemplateContent();
        }

        char c = peek();

        // Whitespace
        if (c == ' ' || c == '\t') {
            return scanWhitespace();
        }
        if (c == '\r' || c == '\n') {
            return scanWhitespaceWithNewline();
        }

        // Comments and slash
        if (c == '/') {
            return scanSlash();
        }

        // Strings
        if (c == '"') {
            return scanDoubleString();
        }
        if (c == '\'') {
            return scanSingleString();
        }

        // Template literal
        if (c == '`') {
            advance();
            pushState(LexerState.TEMPLATE);
            return BACKTICK;
        }

        // Numbers
        if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
            return scanNumber();
        }

        // Identifiers and keywords
        if (isIdentifierStart(c)) {
            return scanIdentifier();
        }

        // Operators and punctuation
        return scanOperator();
    }

    // ========== Whitespace ==========

    private TokenType scanWhitespace() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t') {
                advance();
            } else if (c == '\r' || c == '\n') {
                return scanWhitespaceWithNewline();
            } else {
                break;
            }
        }
        return WS;
    }

    private TokenType scanWhitespaceWithNewline() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else {
                break;
            }
        }
        return WS_LF;
    }

    // ========== Comments and Slash ==========

    private TokenType scanSlash() {
        advance(); // consume '/'
        if (match('/')) {
            return scanLineComment();
        }
        if (match('*')) {
            return scanBlockComment();
        }
        if (regexAllowed) {
            return scanRegex();
        }
        return match('=') ? SLASH_EQ : SLASH;
    }

    private TokenType scanLineComment() {
        while (!isAtEnd() && peek() != '\n' && peek() != '\r') {
            advance();
        }
        return L_COMMENT;
    }

    private TokenType scanBlockComment() {
        while (!isAtEnd()) {
            if (peek() == '*' && peek(1) == '/') {
                advance(); // *
                advance(); // /
                break;
            }
            advance();
        }
        return B_COMMENT;
    }

    private TokenType scanRegex() {
        // Already consumed initial '/'
        // Scan until closing '/' handling escape sequences and character classes
        boolean inCharClass = false;
        while (!isAtEnd()) {
            char c = peek();
            if (c == '\n' || c == '\r') {
                // Unterminated regex
                break;
            }
            if (c == '\\') {
                advance(); // consume backslash
                if (!isAtEnd() && peek() != '\n' && peek() != '\r') {
                    advance(); // consume escaped char
                }
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                advance();
                continue;
            }
            if (c == ']' && inCharClass) {
                inCharClass = false;
                advance();
                continue;
            }
            if (c == '/' && !inCharClass) {
                advance(); // consume closing '/'
                // Scan flags
                while (!isAtEnd() && isIdentifierPart(peek())) {
                    advance();
                }
                break;
            }
            advance();
        }
        return REGEX;
    }

    // ========== Strings ==========

    private TokenType scanDoubleString() {
        advance(); // consume opening "
        while (!isAtEnd()) {
            char c = peek();
            if (c == '"') {
                advance();
                break;
            }
            if (c == '\\') {
                advance();
                if (!isAtEnd()) {
                    advance();
                }
                continue;
            }
            advance();
        }
        return D_STRING;
    }

    private TokenType scanSingleString() {
        advance(); // consume opening '
        while (!isAtEnd()) {
            char c = peek();
            if (c == '\'') {
                advance();
                break;
            }
            if (c == '\\') {
                advance();
                if (!isAtEnd()) {
                    advance();
                }
                continue;
            }
            advance();
        }
        return S_STRING;
    }

    // ========== Template Literals ==========

    private TokenType scanTemplateContent() {
        // In TEMPLATE state, scan until we hit `, ${, or end
        if (isAtEnd()) {
            return EOF;
        }

        char c = peek();

        // End of template
        if (c == '`') {
            advance();
            popState();
            return BACKTICK;
        }

        // Placeholder start
        if (c == '$' && peek(1) == '{') {
            advance(); // $
            advance(); // {
            pushState(LexerState.PLACEHOLDER);
            return DOLLAR_L_CURLY;
        }

        // Template string content
        while (!isAtEnd()) {
            c = peek();
            if (c == '`') {
                break;
            }
            if (c == '$' && peek(1) == '{') {
                break;
            }
            if (c == '\\') {
                advance();
                if (!isAtEnd()) {
                    advance();
                }
                continue;
            }
            advance();
        }
        return T_STRING;
    }

    // ========== Numbers ==========

    private TokenType scanNumber() {
        char c = peek();

        // Hex number
        if (c == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance(); // 0
            advance(); // x
            while (!isAtEnd() && isHexDigit(peek())) {
                advance();
            }
            return NUMBER;
        }

        // Decimal number
        // Integer part
        if (c == '.') {
            // Number starting with .
            advance();
        } else {
            while (!isAtEnd() && isDigit(peek())) {
                advance();
            }
            // Decimal part
            if (peek() == '.' && isDigit(peek(1))) {
                advance(); // .
            }
        }

        // Fractional part
        while (!isAtEnd() && isDigit(peek())) {
            advance();
        }

        // Exponent part
        if (peek() == 'e' || peek() == 'E') {
            advance();
            if (peek() == '+' || peek() == '-') {
                advance();
            }
            while (!isAtEnd() && isDigit(peek())) {
                advance();
            }
        }

        return NUMBER;
    }

    // ========== Identifiers and Keywords ==========

    private TokenType scanIdentifier() {
        while (!isAtEnd() && isIdentifierPart(peek())) {
            advance();
        }
        String text = source.substring(tokenStart, pos);
        return keywordOrIdent(text);
    }

    private TokenType keywordOrIdent(String text) {
        // Note: "this" and "void" are NOT keywords in the lexer - they're identifiers
        // that get special handling in the parser/evaluator
        return switch (text) {
            case "null" -> NULL;
            case "true" -> TRUE;
            case "false" -> FALSE;
            case "function" -> FUNCTION;
            case "return" -> RETURN;
            case "try" -> TRY;
            case "catch" -> CATCH;
            case "finally" -> FINALLY;
            case "throw" -> THROW;
            case "new" -> NEW;
            case "var" -> VAR;
            case "let" -> LET;
            case "const" -> CONST;
            case "if" -> IF;
            case "else" -> ELSE;
            case "typeof" -> TYPEOF;
            case "instanceof" -> INSTANCEOF;
            case "delete" -> DELETE;
            case "for" -> FOR;
            case "in" -> IN;
            case "of" -> OF;
            case "do" -> DO;
            case "while" -> WHILE;
            case "switch" -> SWITCH;
            case "case" -> CASE;
            case "default" -> DEFAULT;
            case "break" -> BREAK;
            case "continue" -> CONTINUE;
            default -> IDENT;
        };
    }

    // ========== Operators and Punctuation ==========

    private TokenType scanOperator() {
        char c = advance();

        switch (c) {
            case '{':
                return L_CURLY;
            case '}':
                if (currentState() == LexerState.PLACEHOLDER) {
                    popState();
                }
                return R_CURLY;
            case '[':
                return L_BRACKET;
            case ']':
                return R_BRACKET;
            case '(':
                return L_PAREN;
            case ')':
                return R_PAREN;
            case ',':
                return COMMA;
            case ':':
                return COLON;
            case ';':
                return SEMI;
            case '~':
                return TILDE;

            case '.':
                if (match('.')) {
                    if (match('.')) {
                        return DOT_DOT_DOT;
                    }
                    // Two dots without third - back up and return single dot
                    // This shouldn't happen in valid JS, but handle gracefully
                }
                return DOT;

            case '?':
                if (match('.')) {
                    return QUES_DOT;
                }
                if (match('?')) {
                    return QUES_QUES;
                }
                return QUES;

            case '=':
                if (match('=')) {
                    return match('=') ? EQ_EQ_EQ : EQ_EQ;
                }
                if (match('>')) {
                    return EQ_GT;
                }
                return EQ;

            case '<':
                if (match('<')) {
                    return match('=') ? LT_LT_EQ : LT_LT;
                }
                return match('=') ? LT_EQ : LT;

            case '>':
                if (match('>')) {
                    if (match('>')) {
                        return match('=') ? GT_GT_GT_EQ : GT_GT_GT;
                    }
                    return match('=') ? GT_GT_EQ : GT_GT;
                }
                return match('=') ? GT_EQ : GT;

            case '!':
                if (match('=')) {
                    return match('=') ? NOT_EQ_EQ : NOT_EQ;
                }
                return NOT;

            case '|':
                if (match('|')) {
                    return match('=') ? PIPE_PIPE_EQ : PIPE_PIPE;
                }
                return match('=') ? PIPE_EQ : PIPE;

            case '&':
                if (match('&')) {
                    return match('=') ? AMP_AMP_EQ : AMP_AMP;
                }
                return match('=') ? AMP_EQ : AMP;

            case '^':
                return match('=') ? CARET_EQ : CARET;

            case '+':
                if (match('+')) {
                    return PLUS_PLUS;
                }
                return match('=') ? PLUS_EQ : PLUS;

            case '-':
                if (match('-')) {
                    return MINUS_MINUS;
                }
                return match('=') ? MINUS_EQ : MINUS;

            case '*':
                if (match('*')) {
                    return match('=') ? STAR_STAR_EQ : STAR_STAR;
                }
                return match('=') ? STAR_EQ : STAR;

            case '%':
                return match('=') ? PERCENT_EQ : PERCENT;

            default:
                // Unknown character - return as IDENT (will likely cause parse error)
                return IDENT;
        }
    }

    // ========== Character Classification ==========

    protected static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    protected static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    protected static boolean isIdentifierStart(char c) {
        return Character.isJavaIdentifierStart(c);
    }

    protected static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

}
