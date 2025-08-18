package io.karatelabs.js;

import io.karatelabs.common.Resource;

import static io.karatelabs.js.TokenType.*;

public class JsParser extends Parser {

    public JsParser(Resource resource) {
        super(resource, false);
    }

    public Node parse() {
        enter(NodeType.PROGRAM);
        final Node program = marker.node;
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        if (peek() != EOF) {
            error("cannot parse statement");
        }
        exit();
        return program;
    }

    private boolean statement(boolean mandatory) {
        enter(NodeType.STATEMENT);
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
        result = result || consumeIf(SEMI); // empty statement
        return exit(result, mandatory);
    }

    private boolean eos() {
        if (peek() == EOF) {
            return true;
        }
        Token chunk = chunks.get(position);
        if (chunk.type == R_CURLY) {
            return true;
        }
        if (enter(NodeType.EOS, SEMI)) {
            return exit();
        }
        return chunk.prev != null && chunk.prev.type == WS_LF;
    }

    private boolean expr_list() {
        enter(NodeType.EXPR_LIST);
        boolean atLeastOne = false;
        while (true) {
            if (expr(-1, false)) {
                atLeastOne = true;
            } else {
                break;
            }
            if (consumeIf(COMMA)) {
                // continue;
            } else {
                break;
            }
        }
        return exit(atLeastOne, false);
    }

    private boolean if_stmt() {
        if (!enter(NodeType.IF_STMT, IF)) {
            return false;
        }
        consume(L_PAREN);
        expr(-1, true);
        consume(R_PAREN);
        statement(true);
        if (consumeIf(ELSE)) {
            statement(true);
        }
        return exit();
    }

    private boolean var_stmt() {
        if (!enter(NodeType.VAR_STMT, VAR, CONST, LET)) {
            return false;
        }
        if (!var_stmt_names()) {
            error(NodeType.VAR_STMT_NAMES);
        }
        if (consumeIf(EQ)) {
            expr(-1, true);
        }
        return exit();
    }

    private boolean var_stmt_names() {
        if (!enter(NodeType.VAR_STMT_NAMES, IDENT)) {
            return false;
        }
        while (consumeIf(COMMA)) {
            consume(IDENT);
        }
        return exit();
    }

    private boolean return_stmt() {
        if (!enter(NodeType.RETURN_STMT, RETURN)) {
            return false;
        }
        expr(-1, false);
        return exit();
    }

    private boolean throw_stmt() {
        if (!enter(NodeType.THROW_STMT, THROW)) {
            return false;
        }
        expr(-1, true);
        return exit();
    }

    private boolean try_stmt() {
        if (!enter(NodeType.TRY_STMT, TRY)) {
            return false;
        }
        block(true);
        if (consumeIf(CATCH)) {
            if (consumeIf(L_PAREN) && consumeIf(IDENT) && consumeIf(R_PAREN) && block(true)) {
                if (consumeIf(FINALLY)) {
                    block(true);
                }
            } else if (block(false)) { // catch without exception variable
                // done
            } else {
                error(CATCH);
            }
        } else if (consumeIf(FINALLY)) {
            block(true);
        } else {
            error("expected " + CATCH + " or " + FINALLY);
        }
        return exit();
    }

    private boolean for_stmt() {
        if (!enter(NodeType.FOR_STMT, FOR)) {
            return false;
        }
        consume(L_PAREN);
        if (peekIf(SEMI) || var_stmt() || expr(-1, false)) {
            // ok
        } else {
            error(NodeType.VAR_STMT, NodeType.EXPR);
        }
        if (consumeIf(SEMI)) {
            if (peekIf(SEMI) || expr(-1, false)) {
                if (consumeIf(SEMI)) {
                    if (peekIf(R_PAREN) || expr(-1, false)) {
                        // ok
                    } else {
                        error(NodeType.EXPR);
                    }
                } else {
                    error(SEMI);
                }
            } else {
                error(NodeType.EXPR);
            }
        } else if (anyOf(IN, OF)) {
            expr(-1, true);
        } else {
            error(SEMI, IN, OF);
        }
        consume(R_PAREN);
        statement(true);
        return exit();
    }

    private boolean while_stmt() {
        if (!enter(NodeType.WHILE_STMT, WHILE)) {
            return false;
        }
        consume(L_PAREN);
        expr(-1, true);
        consume(R_PAREN);
        statement(true);
        return exit();
    }

    private boolean do_while_stmt() {
        if (!enter(NodeType.DO_WHILE_STMT, DO)) {
            return false;
        }
        statement(true);
        consume(WHILE);
        consume(L_PAREN);
        expr(-1, true);
        consume(R_PAREN);
        return exit();
    }

    private boolean switch_stmt() {
        if (!enter(NodeType.SWITCH_STMT, SWITCH)) {
            return false;
        }
        consume(L_PAREN);
        expr(-1, true);
        consume(R_PAREN);
        consume(L_CURLY);
        while (true) {
            if (!case_block()) {
                break;
            }
        }
        default_block();
        consume(R_CURLY);
        return exit();
    }

    private boolean case_block() {
        if (!enter(NodeType.CASE_BLOCK, CASE)) {
            return false;
        }
        expr(-1, true);
        consume(COLON);
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        return exit();
    }

    private boolean default_block() {
        if (!enter(NodeType.DEFAULT_BLOCK, DEFAULT)) {
            return false;
        }
        consume(COLON);
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        return exit();
    }

    private boolean break_stmt() {
        if (!enter(NodeType.BREAK_STMT, BREAK)) {
            return false;
        }
        return exit();
    }

    // as per spec this is an expression
    private boolean delete_stmt() {
        if (!enter(NodeType.DELETE_STMT, DELETE)) {
            return false;
        }
        expr(8, true);
        return exit();
    }

    private boolean block(boolean mandatory) {
        if (!enter(NodeType.BLOCK, L_CURLY)) {
            if (mandatory) {
                error(NodeType.BLOCK);
            }
            return false;
        }
        while (true) {
            if (!statement(false)) {
                break;
            }
        }
        consume(R_CURLY);
        return exit();
    }

    //==================================================================================================================
    //
    private boolean expr(int priority, boolean mandatory) {
        enter(NodeType.EXPR);
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
            if (priority < 0 && enter(NodeType.ASSIGN_EXPR,
                    EQ, PLUS_EQ, MINUS_EQ,
                    STAR_EQ, SLASH_EQ, PERCENT_EQ, STAR_STAR_EQ,
                    GT_GT_EQ, LT_LT_EQ, GT_GT_GT_EQ)) {
                expr(-1, true);
                exit(Shift.RIGHT);
            } else if (priority < 1 && enter(NodeType.LOGIC_TERN_EXPR, QUES)) {
                expr(-1, true);
                consume(COLON);
                expr(-1, true);
                exit(Shift.RIGHT);
            } else if (priority < 2 && enter(NodeType.LOGIC_AND_EXPR, AMP_AMP, PIPE_PIPE)) {
                expr(2, true);
                exit(Shift.LEFT);
            } else if (priority < 3 && enter(NodeType.LOGIC_EXPR,
                    EQ_EQ_EQ, NOT_EQ_EQ, EQ_EQ, NOT_EQ,
                    LT, GT, LT_EQ, GT_EQ)) {
                expr(3, true);
                exit(Shift.LEFT);
            } else if (priority < 4 && enter(NodeType.LOGIC_BIT_EXPR, AMP, PIPE, CARET,
                    GT_GT, LT_LT, GT_GT_GT)) {
                expr(4, true);
                exit(Shift.LEFT);
            } else if (priority < 5 && enter(NodeType.MATH_ADD_EXPR, PLUS, MINUS)) {
                expr(5, true);
                exit(Shift.LEFT);
            } else if (priority < 6 && enter(NodeType.MATH_MUL_EXPR, STAR, SLASH, PERCENT)) {
                expr(6, true);
                exit(Shift.LEFT);
            } else if (priority < 7 && peekIf(STAR_STAR)) {
                while (true) {
                    enter(NodeType.MATH_EXP_EXPR);
                    consumeNext();
                    expr(7, true);
                    exit(Shift.RIGHT);
                    if (!peekIf(STAR_STAR)) {
                        break;
                    }
                }
            } else if (enter(NodeType.FN_CALL_EXPR, L_PAREN)) {
                fn_call_args();
                consume(R_PAREN);
                exit(Shift.LEFT);
            } else if (enter(NodeType.REF_DOT_EXPR, DOT)) {
                TokenType next = peek();
                // allow reserved words as property accessors
                if (next == IDENT || next.keyword) {
                    consumeNext();
                } else {
                    error(IDENT);
                }
                exit(Shift.LEFT);
            } else if (enter(NodeType.REF_BRACKET_EXPR, L_BRACKET)) {
                expr(-1, true);
                consume(R_BRACKET);
                exit(Shift.LEFT);
            } else if (enter(NodeType.MATH_POST_EXPR, PLUS_PLUS, MINUS_MINUS)) {
                exit(Shift.LEFT);
            } else if (enter(NodeType.INSTANCEOF_EXPR, INSTANCEOF)) {
                consume(IDENT);
                exit(Shift.LEFT);
            } else {
                break;
            }
        }
    }

    private boolean fn_arrow_expr() {
        enter(NodeType.FN_ARROW_EXPR);
        boolean result = consumeIf(IDENT);
        result = result || (consumeIf(L_PAREN) && fn_decl_args() && consumeIf(R_PAREN));
        result = result && consumeIf(EQ_GT);
        result = result && (block(false) || expr(-1, false));
        return exit(result, false);
    }

    private boolean fn_expr() {
        if (!enter(NodeType.FN_EXPR, FUNCTION)) {
            return false;
        }
        consumeIf(IDENT);
        consume(L_PAREN);
        fn_decl_args();
        consume(R_PAREN);
        block(true);
        return exit();
    }

    private boolean fn_decl_args() {
        enter(NodeType.FN_DECL_ARGS);
        while (true) {
            if (peekIf(R_PAREN)) {
                break;
            }
            if (!fn_decl_arg()) {
                break;
            }
        }
        return exit();
    }

    private boolean fn_decl_arg() {
        enter(NodeType.FN_DECL_ARG);
        if (consumeIf(DOT_DOT_DOT)) {
            consume(IDENT);
            if (!peekIf(R_PAREN)) {
                error(R_PAREN);
            }
            return exit();
        }
        boolean result = consumeIf(IDENT);
        result = result && (consumeIf(COMMA) || peekIf(R_PAREN));
        return exit(result, false);
    }

    private boolean fn_call_args() {
        enter(NodeType.FN_CALL_ARGS);
        while (true) {
            if (peekIf(R_PAREN)) {
                break;
            }
            if (!fn_call_arg()) {
                break;
            }
        }
        return exit();
    }

    private boolean fn_call_arg() {
        enter(NodeType.FN_CALL_ARG);
        consumeIf(DOT_DOT_DOT);
        boolean result = expr(-1, false);
        result = result && (consumeIf(COMMA) || peekIf(R_PAREN));
        return exit(result, false);
    }

    private boolean new_expr() {
        if (!enter(NodeType.NEW_EXPR, NEW)) {
            return false;
        }
        expr(8, true);
        return exit();
    }

    private boolean typeof_expr() {
        if (!enter(NodeType.TYPEOF_EXPR, TYPEOF)) {
            return false;
        }
        expr(8, true);
        return exit();
    }

    private boolean ref_expr() {
        if (!enter(NodeType.REF_EXPR, IDENT)) {
            return false;
        }
        return exit();
    }

    private boolean lit_expr() {
        enter(NodeType.LIT_EXPR);
        boolean result = lit_object() || lit_array();
        result = result || anyOf(S_STRING, D_STRING, NUMBER, TRUE, FALSE, NULL);
        result = result || lit_template() || regex_literal();
        return exit(result, false);
    }

    private boolean lit_template() {
        if (!enter(NodeType.LIT_TEMPLATE, BACKTICK)) {
            return false;
        }
        while (true) {
            if (peek() == EOF) { // unbalanced backticks
                error(BACKTICK);
            }
            if (consumeIf(BACKTICK)) {
                break;
            }
            if (!consumeIf(T_STRING)) {
                if (consumeIf(DOLLAR_L_CURLY)) {
                    expr(-1, false);
                    consume(R_CURLY);
                }
            }
        }
        return exit();
    }

    private boolean unary_expr() {
        if (!enter(NodeType.UNARY_EXPR, NOT, TILDE)) {
            return false;
        }
        expr(-1, true);
        return exit();
    }

    private boolean math_pre_expr() {
        if (!enter(NodeType.MATH_PRE_EXPR, PLUS_PLUS, MINUS_MINUS, MINUS, PLUS)) {
            return false;
        }
        if (expr(8, false) || consumeIf(NUMBER)) {
            // all good
        } else {
            error(NodeType.EXPR);
        }
        return exit();
    }

    private boolean lit_object() {
        if (!enter(NodeType.LIT_OBJECT, L_CURLY)) {
            return false;
        }
        while (true) {
            if (peekIf(R_CURLY)) {
                break;
            }
            if (!object_elem()) {
                break;
            }
        }
        boolean result = consumeIf(R_CURLY);
        return exit(result, false);
    }

    private boolean object_elem() {
        if (!enter(NodeType.OBJECT_ELEM, IDENT, S_STRING, D_STRING, NUMBER, DOT_DOT_DOT)) {
            return false;
        }
        if (consumeIf(COMMA) || peekIf(R_CURLY)) { // es6 enhanced object literals
            return exit();
        }
        boolean spread = false;
        if (!consumeIf(COLON)) {
            if (peekPrev() == DOT_DOT_DOT) { // spread operator
                if (consumeIf(IDENT)) {
                    spread = true;
                } else {
                    error(IDENT);
                }
            } else {
                return exit(false, false); // could be block
            }
        }
        if (!spread) {
            expr(-1, true);
        }
        if (consumeIf(COMMA) || peekIf(R_CURLY)) {
            // all good
        } else {
            error(COMMA, R_CURLY);
        }
        return exit();
    }

    private boolean lit_array() {
        if (!enter(NodeType.LIT_ARRAY, L_BRACKET)) {
            return false;
        }
        while (true) {
            if (peekIf(R_BRACKET)) {
                break;
            }
            if (!array_elem()) {
                break;
            }
        }
        consume(R_BRACKET);
        return exit();
    }

    private boolean array_elem() {
        enter(NodeType.ARRAY_ELEM);
        consumeIf(DOT_DOT_DOT); // spread operator
        expr(-1, false); // optional for sparse array
        if (consumeIf(COMMA) || peekIf(R_BRACKET)) {
            // all good
        } else {
            error(COMMA, R_BRACKET);
        }
        return exit();
    }

    private boolean regex_literal() {
        if (!enter(NodeType.REGEX_LITERAL, REGEX)) {
            return false;
        }
        return exit();
    }

    private boolean paren_expr() {
        if (!enter(NodeType.PAREN_EXPR, L_PAREN)) {
            return false;
        }
        expr(-1, true);
        consume(R_PAREN);
        return exit();
    }

}
