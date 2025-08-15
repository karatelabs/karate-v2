package io.karatelabs.js;

import io.karatelabs.common.Source;

public class JsParser extends Parser {

    public JsParser(Source source) {
        super(source, false);
    }

    public Node parse() {
        enter(Type.PROGRAM);
        final Node program = marker.node;
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        if (peek() != Token.EOF) {
            error("cannot parse statement");
        }
        exit();
        return program;
    }

    private boolean statement(boolean mandatory) {
        enter(Type.STATEMENT);
        boolean result = if_stmt();
        result = result || (var_stmt() && eos());
        result = result || (return_stmt() && eos());
        result = result || (throw_stmt() && eos());
        result = result || try_stmt();
        result = result || for_stmt();
        result = result || while_stmt();
        result = result || do_while_stmt();
        result = result || switch_stmt();
        result = result || (break_stmt() && eos());
        result = result || (delete_stmt() && eos());
        result = result || (expr_list() && eos());
        result = result || block(false);
        result = result || consumeIf(Token.SEMI); // empty statement
        return exit(result, mandatory);
    }

    private boolean eos() {
        if (peek() == Token.EOF) {
            return true;
        }
        Chunk chunk = chunks.get(position);
        if (chunk.token == Token.R_CURLY) {
            return true;
        }
        if (enter(Type.EOS, Token.SEMI)) {
            return exit();
        }
        return chunk.prev != null && chunk.prev.token == Token.WS_LF;
    }

    private boolean expr_list() {
        enter(Type.EXPR_LIST);
        boolean atLeastOne = false;
        while (true) {
            if (expr(-1, false)) {
                atLeastOne = true;
            } else {
                break;
            }
            if (consumeIf(Token.COMMA)) {
                // continue;
            } else {
                break;
            }
        }
        return exit(atLeastOne, false);
    }

    private boolean if_stmt() {
        if (!enter(Type.IF_STMT, Token.IF)) {
            return false;
        }
        consume(Token.L_PAREN);
        expr(-1, true);
        consume(Token.R_PAREN);
        statement(true);
        if (consumeIf(Token.ELSE)) {
            statement(true);
        }
        return exit();
    }

    private boolean var_stmt() {
        if (!enter(Type.VAR_STMT, Token.VAR, Token.CONST, Token.LET)) {
            return false;
        }
        if (!var_stmt_names()) {
            error(Type.VAR_STMT_NAMES);
        }
        if (consumeIf(Token.EQ)) {
            expr(-1, true);
        }
        return exit();
    }

    private boolean var_stmt_names() {
        if (!enter(Type.VAR_STMT_NAMES, Token.IDENT)) {
            return false;
        }
        while (consumeIf(Token.COMMA)) {
            consume(Token.IDENT);
        }
        return exit();
    }

    private boolean return_stmt() {
        if (!enter(Type.RETURN_STMT, Token.RETURN)) {
            return false;
        }
        expr(-1, false);
        return exit();
    }

    private boolean throw_stmt() {
        if (!enter(Type.THROW_STMT, Token.THROW)) {
            return false;
        }
        expr(-1, true);
        return exit();
    }

    private boolean try_stmt() {
        if (!enter(Type.TRY_STMT, Token.TRY)) {
            return false;
        }
        block(true);
        if (consumeIf(Token.CATCH)) {
            if (consumeIf(Token.L_PAREN) && consumeIf(Token.IDENT) && consumeIf(Token.R_PAREN) && block(true)) {
                if (consumeIf(Token.FINALLY)) {
                    block(true);
                }
            } else if (block(false)) { // catch without exception variable
                // done
            } else {
                error(Token.CATCH);
            }
        } else if (consumeIf(Token.FINALLY)) {
            block(true);
        } else {
            error("expected " + Token.CATCH + " or " + Token.FINALLY);
        }
        return exit();
    }

    private boolean for_stmt() {
        if (!enter(Type.FOR_STMT, Token.FOR)) {
            return false;
        }
        consume(Token.L_PAREN);
        if (peekIf(Token.SEMI) || var_stmt() || expr(-1, false)) {
            // ok
        } else {
            error(Type.VAR_STMT, Type.EXPR);
        }
        if (consumeIf(Token.SEMI)) {
            if (peekIf(Token.SEMI) || expr(-1, false)) {
                if (consumeIf(Token.SEMI)) {
                    if (peekIf(Token.R_PAREN) || expr(-1, false)) {
                        // ok
                    } else {
                        error(Type.EXPR);
                    }
                } else {
                    error(Token.SEMI);
                }
            } else {
                error(Type.EXPR);
            }
        } else if (anyOf(Token.IN, Token.OF)) {
            expr(-1, true);
        } else {
            error(Token.SEMI, Token.IN, Token.OF);
        }
        consume(Token.R_PAREN);
        statement(true);
        return exit();
    }

    private boolean while_stmt() {
        if (!enter(Type.WHILE_STMT, Token.WHILE)) {
            return false;
        }
        consume(Token.L_PAREN);
        expr(-1, true);
        consume(Token.R_PAREN);
        statement(true);
        return exit();
    }

    private boolean do_while_stmt() {
        if (!enter(Type.DO_WHILE_STMT, Token.DO)) {
            return false;
        }
        statement(true);
        consume(Token.WHILE);
        consume(Token.L_PAREN);
        expr(-1, true);
        consume(Token.R_PAREN);
        return exit();
    }

    private boolean switch_stmt() {
        if (!enter(Type.SWITCH_STMT, Token.SWITCH)) {
            return false;
        }
        consume(Token.L_PAREN);
        expr(-1, true);
        consume(Token.R_PAREN);
        consume(Token.L_CURLY);
        while (true) {
            if (!case_block()) {
                break;
            }
        }
        default_block();
        consume(Token.R_CURLY);
        return exit();
    }

    private boolean case_block() {
        if (!enter(Type.CASE_BLOCK, Token.CASE)) {
            return false;
        }
        expr(-1, true);
        consume(Token.COLON);
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        return exit();
    }

    private boolean default_block() {
        if (!enter(Type.DEFAULT_BLOCK, Token.DEFAULT)) {
            return false;
        }
        consume(Token.COLON);
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        return exit();
    }

    private boolean break_stmt() {
        if (!enter(Type.BREAK_STMT, Token.BREAK)) {
            return false;
        }
        return exit();
    }

    // as per spec this is an expression
    private boolean delete_stmt() {
        if (!enter(Type.DELETE_STMT, Token.DELETE)) {
            return false;
        }
        expr(8, true);
        return exit();
    }

    private boolean block(boolean mandatory) {
        if (!enter(Type.BLOCK, Token.L_CURLY)) {
            if (mandatory) {
                error(Type.BLOCK);
            }
            return false;
        }
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        consume(Token.R_CURLY);
        return exit();
    }

    //==================================================================================================================
    //
    private boolean expr(int priority, boolean mandatory) {
        enter(Type.EXPR);
        boolean result = fn_arrow_expr();
        result = result || fn_expr();
        result = result || new_expr();
        result = result || typeof_expr();
        result = result || ref_expr();
        result = result || lit_expr();
        result = result || paren_expr();
        result = result || unary_expr();
        result = result || math_pre_expr();
        expr_rhs(priority);
        return exit(result, mandatory);
    }

    private void expr_rhs(int priority) {
        while (true) {
            if (priority < 0 && enter(Type.ASSIGN_EXPR,
                    Token.EQ, Token.PLUS_EQ, Token.MINUS_EQ,
                    Token.STAR_EQ, Token.SLASH_EQ, Token.PERCENT_EQ, Token.STAR_STAR_EQ,
                    Token.GT_GT_EQ, Token.LT_LT_EQ, Token.GT_GT_GT_EQ)) {
                expr(-1, true);
                exit(Shift.RIGHT);
            } else if (priority < 1 && enter(Type.LOGIC_TERN_EXPR, Token.QUES)) {
                expr(-1, true);
                consume(Token.COLON);
                expr(-1, true);
                exit(Shift.RIGHT);
            } else if (priority < 2 && enter(Type.LOGIC_AND_EXPR, Token.AMP_AMP, Token.PIPE_PIPE)) {
                expr(2, true);
                exit(Shift.LEFT);
            } else if (priority < 3 && enter(Type.LOGIC_EXPR,
                    Token.EQ_EQ_EQ, Token.NOT_EQ_EQ, Token.EQ_EQ, Token.NOT_EQ,
                    Token.LT, Token.GT, Token.LT_EQ, Token.GT_EQ)) {
                expr(3, true);
                exit(Shift.LEFT);
            } else if (priority < 4 && enter(Type.LOGIC_BIT_EXPR, Token.AMP, Token.PIPE, Token.CARET,
                    Token.GT_GT, Token.LT_LT, Token.GT_GT_GT)) {
                expr(4, true);
                exit(Shift.LEFT);
            } else if (priority < 5 && enter(Type.MATH_ADD_EXPR, Token.PLUS, Token.MINUS)) {
                expr(5, true);
                exit(Shift.LEFT);
            } else if (priority < 6 && enter(Type.MATH_MUL_EXPR, Token.STAR, Token.SLASH, Token.PERCENT)) {
                expr(6, true);
                exit(Shift.LEFT);
            } else if (priority < 7 && peekIf(Token.STAR_STAR)) {
                while (true) {
                    enter(Type.MATH_EXP_EXPR);
                    consumeNext();
                    expr(7, true);
                    exit(Shift.RIGHT);
                    if (!peekIf(Token.STAR_STAR)) {
                        break;
                    }
                }
            } else if (enter(Type.FN_CALL_EXPR, Token.L_PAREN)) {
                fn_call_args();
                consume(Token.R_PAREN);
                exit(Shift.LEFT);
            } else if (enter(Type.REF_DOT_EXPR, Token.DOT)) {
                Token next = peek();
                // allow reserved words as property accessors
                if (next == Token.IDENT || next.keyword) {
                    consumeNext();
                } else {
                    error(Token.IDENT);
                }
                exit(Shift.LEFT);
            } else if (enter(Type.REF_BRACKET_EXPR, Token.L_BRACKET)) {
                expr(-1, true);
                consume(Token.R_BRACKET);
                exit(Shift.LEFT);
            } else if (enter(Type.MATH_POST_EXPR, Token.PLUS_PLUS, Token.MINUS_MINUS)) {
                exit(Shift.LEFT);
            } else if (enter(Type.INSTANCEOF_EXPR, Token.INSTANCEOF)) {
                consume(Token.IDENT);
                exit(Shift.LEFT);
            } else {
                break;
            }
        }
    }

    private boolean fn_arrow_expr() {
        enter(Type.FN_ARROW_EXPR);
        boolean result = consumeIf(Token.IDENT);
        result = result || (consumeIf(Token.L_PAREN) && fn_decl_args() && consumeIf(Token.R_PAREN));
        result = result && consumeIf(Token.EQ_GT);
        result = result && (block(false) || expr(-1, false));
        return exit(result, false);
    }

    private boolean fn_expr() {
        if (!enter(Type.FN_EXPR, Token.FUNCTION)) {
            return false;
        }
        consumeIf(Token.IDENT);
        consume(Token.L_PAREN);
        fn_decl_args();
        consume(Token.R_PAREN);
        block(true);
        return exit();
    }

    private boolean fn_decl_args() {
        enter(Type.FN_DECL_ARGS);
        while (true) {
            if (peekIf(Token.R_PAREN)) {
                break;
            }
            if (!fn_decl_arg()) {
                break;
            }
        }
        return exit();
    }

    private boolean fn_decl_arg() {
        enter(Type.FN_DECL_ARG);
        if (consumeIf(Token.DOT_DOT_DOT)) {
            consume(Token.IDENT);
            if (!peekIf(Token.R_PAREN)) {
                error(Token.R_PAREN);
            }
            return exit();
        }
        boolean result = consumeIf(Token.IDENT);
        result = result && (consumeIf(Token.COMMA) || peekIf(Token.R_PAREN));
        return exit(result, false);
    }

    private boolean fn_call_args() {
        enter(Type.FN_CALL_ARGS);
        while (true) {
            if (peekIf(Token.R_PAREN)) {
                break;
            }
            if (!fn_call_arg()) {
                break;
            }
        }
        return exit();
    }

    private boolean fn_call_arg() {
        enter(Type.FN_CALL_ARG);
        consumeIf(Token.DOT_DOT_DOT);
        boolean result = expr(-1, false);
        result = result && (consumeIf(Token.COMMA) || peekIf(Token.R_PAREN));
        return exit(result, false);
    }

    private boolean new_expr() {
        if (!enter(Type.NEW_EXPR, Token.NEW)) {
            return false;
        }
        expr(8, true);
        return exit();
    }

    private boolean typeof_expr() {
        if (!enter(Type.TYPEOF_EXPR, Token.TYPEOF)) {
            return false;
        }
        expr(8, true);
        return exit();
    }

    private boolean ref_expr() {
        if (!enter(Type.REF_EXPR, Token.IDENT)) {
            return false;
        }
        return exit();
    }

    private boolean lit_expr() {
        enter(Type.LIT_EXPR);
        boolean result = lit_object() || lit_array();
        result = result || anyOf(Token.S_STRING, Token.D_STRING, Token.NUMBER, Token.TRUE, Token.FALSE, Token.NULL);
        result = result || lit_template() || regex_literal();
        return exit(result, false);
    }

    private boolean lit_template() {
        if (!enter(Type.LIT_TEMPLATE, Token.BACKTICK)) {
            return false;
        }
        while (true) {
            if (peek() == Token.EOF) { // unbalanced backticks
                error(Token.BACKTICK);
            }
            if (consumeIf(Token.BACKTICK)) {
                break;
            }
            if (!consumeIf(Token.T_STRING)) {
                if (consumeIf(Token.DOLLAR_L_CURLY)) {
                    expr(-1, false);
                    consume(Token.R_CURLY);
                }
            }
        }
        return exit();
    }

    private boolean unary_expr() {
        if (!enter(Type.UNARY_EXPR, Token.NOT, Token.TILDE)) {
            return false;
        }
        expr(-1, true);
        return exit();
    }

    private boolean math_pre_expr() {
        if (!enter(Type.MATH_PRE_EXPR, Token.PLUS_PLUS, Token.MINUS_MINUS, Token.MINUS, Token.PLUS)) {
            return false;
        }
        if (expr(8, false) || consumeIf(Token.NUMBER)) {
            // all good
        } else {
            error(Type.EXPR);
        }
        return exit();
    }

    private boolean lit_object() {
        if (!enter(Type.LIT_OBJECT, Token.L_CURLY)) {
            return false;
        }
        while (true) {
            if (peekIf(Token.R_CURLY)) {
                break;
            }
            if (!object_elem()) {
                break;
            }
        }
        boolean result = consumeIf(Token.R_CURLY);
        return exit(result, false);
    }

    private boolean object_elem() {
        if (!enter(Type.OBJECT_ELEM, Token.IDENT, Token.S_STRING, Token.D_STRING, Token.NUMBER, Token.DOT_DOT_DOT)) {
            return false;
        }
        if (consumeIf(Token.COMMA) || peekIf(Token.R_CURLY)) { // es6 enhanced object literals
            return exit();
        }
        boolean spread = false;
        if (!consumeIf(Token.COLON)) {
            if (peekPrev() == Token.DOT_DOT_DOT) { // spread operator
                if (consumeIf(Token.IDENT)) {
                    spread = true;
                } else {
                    error(Token.IDENT);
                }
            } else {
                return exit(false, false); // could be block
            }
        }
        if (!spread) {
            expr(-1, true);
        }
        if (consumeIf(Token.COMMA) || peekIf(Token.R_CURLY)) {
            // all good
        } else {
            error(Token.COMMA, Token.R_CURLY);
        }
        return exit();
    }

    private boolean lit_array() {
        if (!enter(Type.LIT_ARRAY, Token.L_BRACKET)) {
            return false;
        }
        while (true) {
            if (peekIf(Token.R_BRACKET)) {
                break;
            }
            if (!array_elem()) {
                break;
            }
        }
        consume(Token.R_BRACKET);
        return exit();
    }

    private boolean array_elem() {
        enter(Type.ARRAY_ELEM);
        consumeIf(Token.DOT_DOT_DOT); // spread operator
        expr(-1, false); // optional for sparse array
        if (consumeIf(Token.COMMA) || peekIf(Token.R_BRACKET)) {
            // all good
        } else {
            error(Token.COMMA, Token.R_BRACKET);
        }
        return exit();
    }

    private boolean regex_literal() {
        if (!enter(Type.REGEX_LITERAL, Token.REGEX)) {
            return false;
        }
        return exit();
    }

    private boolean paren_expr() {
        if (!enter(Type.PAREN_EXPR, Token.L_PAREN)) {
            return false;
        }
        expr(-1, true);
        consume(Token.R_PAREN);
        return exit();
    }

}
