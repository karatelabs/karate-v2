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

public enum TokenType {

    WS_LF,
    WS,
    EOF,
    BACKTICK,
    L_CURLY,
    R_CURLY,
    L_BRACKET,
    R_BRACKET,
    L_PAREN,
    R_PAREN,
    COMMA,
    COLON,
    SEMI,
    DOT_DOT_DOT,
    QUES_DOT,
    DOT,
    //==== keywords
    NULL(true),
    TRUE(true),
    FALSE(true),
    FUNCTION(true),
    RETURN(true),
    TRY(true),
    CATCH(true),
    FINALLY(true),
    THROW(true),
    NEW(true),
    VAR(true),
    LET(true),
    CONST(true),
    IF(true),
    ELSE(true),
    TYPEOF(true),
    INSTANCEOF(true),
    DELETE(true),
    FOR(true),
    IN(true),
    OF(true),
    DO(true),
    WHILE(true),
    SWITCH(true),
    CASE(true),
    DEFAULT(true),
    BREAK(true),
    THIS(true),
    VOID(true),
    //====
    EQ_EQ_EQ,
    EQ_EQ,
    EQ,
    EQ_GT, // arrow
    LT_LT_EQ,
    LT_LT,
    LT_EQ,
    LT,
    GT_GT_GT_EQ,
    GT_GT_GT,
    GT_GT_EQ,
    GT_GT,
    GT_EQ,
    GT,
    //====
    NOT_EQ_EQ,
    NOT_EQ,
    NOT,
    PIPE_PIPE_EQ,
    PIPE_PIPE,
    PIPE_EQ,
    PIPE,
    AMP_AMP_EQ,
    AMP_AMP,
    AMP_EQ,
    AMP,
    CARET_EQ,
    CARET,
    QUES_QUES,
    QUES,
    //====
    PLUS_PLUS,
    PLUS_EQ,
    PLUS,
    MINUS_MINUS,
    MINUS_EQ,
    MINUS,
    STAR_STAR_EQ,
    STAR_STAR,
    STAR_EQ,
    STAR,
    SLASH_EQ,
    SLASH,
    PERCENT_EQ,
    PERCENT,
    TILDE,
    //====
    L_COMMENT,
    B_COMMENT,
    S_STRING,
    D_STRING,
    NUMBER,
    IDENT,
    //====
    REGEX,
    DOLLAR_L_CURLY,
    T_STRING,
    //====
    G_PREFIX,
    G_STEP,
    G_STEP_TEXT,
    G_COMMENT,
    G_DESC,
    G_FEATURE,
    G_SCENARIO,
    G_SCENARIO_OUTLINE,
    G_EXAMPLES,
    G_BACKGROUND,
    G_TAG,
    G_TRIPLE_QUOTE,
    G_PIPE,
    G_PIPE_FIRST,
    G_TABLE_CELL;

    public final boolean primary;
    public final boolean keyword;
    public final Boolean regexAllowed;

    TokenType() {
        this(false);
    }

    TokenType(boolean keyword) {
        this.primary = !isCommentOrWhitespace(this);
        this.keyword = keyword;
        regexAllowed = isRegexAllowed(this);
    }

    private static boolean isCommentOrWhitespace(TokenType type) {
        switch (type) {
            case L_COMMENT:
            case B_COMMENT:
            case G_COMMENT:
            case WS:
            case WS_LF:
            case EOF:
                return true;
        }
        return false;
    }

    private static Boolean isRegexAllowed(TokenType type) {
        switch (type) {
            // after these tokens, a regex literal is allowed (rather than division)
            case L_PAREN:
            case L_BRACKET:
            case L_CURLY:
            case COMMA:
            case SEMI:
            case COLON:
            case EQ:
            case EQ_EQ:
            case EQ_EQ_EQ:
            case NOT_EQ:
            case NOT_EQ_EQ:
            case LT:
            case LT_EQ:
            case GT:
            case GT_EQ:
            case PLUS:
            case PLUS_EQ:
            case MINUS:
            case MINUS_EQ:
            case STAR:
            case STAR_EQ:
            case STAR_STAR:
            case STAR_STAR_EQ:
            case SLASH_EQ:
            case PERCENT:
            case PERCENT_EQ:
            case AMP:
            case AMP_EQ:
            case AMP_AMP:
            case AMP_AMP_EQ:
            case PIPE:
            case PIPE_EQ:
            case PIPE_PIPE:
            case PIPE_PIPE_EQ:
            case CARET:
            case CARET_EQ:
            case QUES:
            case QUES_QUES:
            case TILDE:
            case NOT:
            case RETURN:
            case TYPEOF:
            case DELETE:
            case INSTANCEOF:
            case IN:
            case DO:
            case IF:
            case ELSE:
            case CASE:
            case DEFAULT:
            case THROW:
                return true;
            // after these tokens, a regex literal is not allowed
            case R_PAREN:
            case R_BRACKET:
            case R_CURLY:
            case IDENT:
            case NUMBER:
            case S_STRING:
            case D_STRING:
            case TRUE:
            case FALSE:
            case NULL:
                return false;
        }
        // for other tokens, keep the current value of regexAllowed
        return null;
    }

}
