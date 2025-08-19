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

import java.util.ArrayDeque;

import static io.karatelabs.js.TokenType.*;
%%

%class Lexer
%unicode
%type TokenType

%{
private ArrayDeque<Integer> kkStack;
private void kkPush() { if (kkStack == null) kkStack = new ArrayDeque<>(); kkStack.push(yystate()); }
private int kkPop() { return kkStack.pop(); }
private boolean regexAllowed = true; // start with true since a regex can appear at the start of a program
private TokenType tt(TokenType type) { if (type.regexAllowed != null) regexAllowed = type.regexAllowed; return type; }
%}

WS = [ \t]
LF = \R

DIGIT = [0-9]
HEX_DIGIT = [0-9a-fA-F]
EXPONENT = [Ee] ["+""-"]? {DIGIT}*
NUMBER = ({DIGIT}+ ("." {DIGIT}+)? {EXPONENT}?) | ("." {DIGIT}+ {EXPONENT}?)
HEX = 0 [Xx] {HEX_DIGIT}*

L_COMMENT = "//".*
B_COMMENT = "/*"([^*]|\*+[^*/])*(\*+"/")?
D_STRING = \"([^\"]|\\\")*\"?
S_STRING = '([^']|\\')*'?
IDENT = [:jletter:][:jletterdigit:]*
T_STRING = [^`$]+ | "$"[^{$`]+
REGEX = "/" [^*/\n] ([^/\\\n]|\\[^\n])* "/" [:jletter:]*

%state TEMPLATE PLACEHOLDER

// gherkin macros and states
GM_LINE = [^\r\n]+

GM_HEADER = "Scenario:"|"Scenario Outline:"|"Examples:"|"Background:"
GM_PREFIX = "*"|"Given"|"When"|"Then"|"And"|"But"
GM_TRIPLE_QUOTE = \"\"\"
GM_TAG = "@" [^ \t\r\n]+

%state GHERKIN GS_DESC GS_STEP GS_DOC_STRING GS_TABLE_ROW GS_TAGS GS_COMMENT

%%

// applies to all states
<<EOF>>                         { return EOF; }

<YYINITIAL, PLACEHOLDER> {
  {WS}* {LF} {WS}*              { return tt(WS_LF); }
  {WS}+                         { return tt(WS); }
  "`"                           { kkPush(); yybegin(TEMPLATE); return tt(BACKTICK); }
  "{"                           { return tt(L_CURLY); }
  //====                        { return tt(R_CURLY); }
  "["                           { return tt(L_BRACKET); }
  "]"                           { return tt(R_BRACKET); }
  "("                           { return tt(L_PAREN); }
  ")"                           { return tt(R_PAREN); }
  ","                           { return tt(COMMA); }
  ":"                           { return tt(COLON); }
  ";"                           { return tt(SEMI); }
  "..."                         { return tt(DOT_DOT_DOT); }
  "?."                          { return tt(QUES_DOT); }
  "."                           { return tt(DOT); }
  //====
  "null"                        { return tt(NULL); }
  "true"                        { return tt(TRUE); }
  "false"                       { return tt(FALSE); }
  "function"                    { return tt(FUNCTION); }
  "return"                      { return tt(RETURN); }
  "try"                         { return tt(TRY); }
  "catch"                       { return tt(CATCH); }
  "finally"                     { return tt(FINALLY); }
  "throw"                       { return tt(THROW); }
  "new"                         { return tt(NEW); }
  "var"                         { return tt(VAR); }
  "let"                         { return tt(LET); }
  "const"                       { return tt(CONST); }
  "if"                          { return tt(IF); }
  "else"                        { return tt(ELSE); }
  "typeof"                      { return tt(TYPEOF); }
  "instanceof"                  { return tt(INSTANCEOF); }
  "delete"                      { return tt(DELETE); }
  "for"                         { return tt(FOR); }
  "in"                          { return tt(IN); }
  "of"                          { return tt(OF); }
  "do"                          { return tt(DO); }
  "while"                       { return tt(WHILE); }
  "switch"                      { return tt(SWITCH); }
  "case"                        { return tt(CASE); }
  "default"                     { return tt(DEFAULT); }
  "break"                       { return tt(BREAK); }
  //====
  "==="                         { return tt(EQ_EQ_EQ); }
  "=="                          { return tt(EQ_EQ); }
  "="                           { return tt(EQ); }
  "=>"                          { return tt(EQ_GT); }
  "<<="                         { return tt(LT_LT_EQ); }
  "<<"                          { return tt(LT_LT); }
  "<="                          { return tt(LT_EQ); }
  "<"                           { return tt(LT); }
  ">>>="                        { return tt(GT_GT_GT_EQ); }
  ">>>"                         { return tt(GT_GT_GT); }
  ">>="                         { return tt(GT_GT_EQ); }
  ">>"                          { return tt(GT_GT); }
  ">="                          { return tt(GT_EQ); }
  ">"                           { return tt(GT); }
  //====
  "!=="                         { return tt(NOT_EQ_EQ); }
  "!="                          { return tt(NOT_EQ); }
  "!"                           { return tt(NOT); }
  "||="                         { return tt(PIPE_PIPE_EQ); }
  "||"                          { return tt(PIPE_PIPE); }
  "|="                          { return tt(PIPE_EQ); }
  "|"                           { return tt(PIPE); }
  "&&="                         { return tt(AMP_AMP_EQ); }
  "&&"                          { return tt(AMP_AMP); }
  "&="                          { return tt(AMP_EQ); }
  "&"                           { return tt(AMP); }
  "^="                          { return tt(CARET_EQ); }
  "^"                           { return tt(CARET); }
  "??"                          { return tt(QUES_QUES); }
  "?"                           { return tt(QUES); }
  //====
  "++"                          { return tt(PLUS_PLUS); }
  "+="                          { return tt(PLUS_EQ); }
  "+"                           { return tt(PLUS); }
  "--"                          { return tt(MINUS_MINUS); }
  "-="                          { return tt(MINUS_EQ); }
  "-"                           { return tt(MINUS); }
  "**="                         { return tt(STAR_STAR_EQ); }
  "**"                          { return tt(STAR_STAR); }
  "*="                          { return tt(STAR_EQ); }
  "*"                           { return tt(STAR); }
  "/="                          { return tt(SLASH_EQ); }
  "%="                          { return tt(PERCENT_EQ); }
  "%"                           { return tt(PERCENT); }
  "~"                           { return tt(TILDE); }
  "/"                           { return regexAllowed ? tt(REGEX) : tt(SLASH); }
  //====
  {REGEX}                       { if (regexAllowed) return tt(REGEX); yypushback(yylength() - 1); return tt(SLASH); }
  {L_COMMENT}                   { return tt(L_COMMENT); }
  {B_COMMENT}                   { return tt(B_COMMENT); }
  {S_STRING}                    { return tt(S_STRING); }
  {D_STRING}                    { return tt(D_STRING); }
  {NUMBER} | {HEX}              { return tt(NUMBER); }
  {IDENT}                       { return tt(IDENT); }
}

<YYINITIAL> "}"                 { return tt(R_CURLY); }

<PLACEHOLDER> "}"               { yybegin(kkPop()); return tt(R_CURLY); }

<TEMPLATE> {
    "`"                         { yybegin(kkPop()); return tt(BACKTICK); }
    "$"                         { return tt(T_STRING); }
    "${"                        { kkPush(); yybegin(PLACEHOLDER); return tt(DOLLAR_L_CURLY); }
    {T_STRING}                  { return tt(T_STRING); }
}

<GHERKIN> {
  {WS}* {LF} {WS}*              { return WS_LF; }
  {WS}+                         { return WS; }
  {GM_PREFIX} {WS}+             { yybegin(GS_STEP); return G_PREFIX; }
  "#"                           { yypushback(1); yybegin(GS_COMMENT); }
  "Scenario:" {WS}*             { yybegin(GS_DESC); return G_SCENARIO; }
  "Scenario Outline:" {WS}*     { yybegin(GS_DESC); return G_SCENARIO_OUTLINE; }
  "Examples:" {WS}*             { yybegin(GS_DESC); return G_EXAMPLES; }
  "Feature:" {WS}*              { yybegin(GS_DESC); return G_FEATURE; }
  "Background:" {WS}*           { yybegin(GS_DESC); return G_BACKGROUND; }
  {GM_TRIPLE_QUOTE} {WS}*       { yybegin(GS_DOC_STRING); return G_TRIPLE_QUOTE; }
  "|"                           { yybegin(GS_TABLE_ROW); return G_PIPE_FIRST; }
  {GM_TAG}                      { yybegin(GS_TAGS); return G_TAG; }
}

<GS_COMMENT> {
  {GM_LINE}                     { return G_COMMENT; }
  {LF}                          { yybegin(GHERKIN); return WS_LF; }
}

<GS_DESC> {
  {GM_LINE}                     { return G_DESC; }
  {LF} {WS}* / ({GM_HEADER}|{GM_PREFIX}|"#"|"@"|"|") { yybegin(GHERKIN); return WS_LF; }
  {LF}                          { return WS_LF; }
}

<GS_TAGS> {
  {GM_TAG}                      { return G_TAG; }
  {WS}+                         { return WS; }
  {LF}                          { yybegin(GHERKIN); return WS_LF; }
}

<GS_TABLE_ROW> {
  [^\|\r\n]+                    { return G_TABLE_CELL; }
  "|"                           { return G_PIPE; }
  {LF}                          { yybegin(GHERKIN); return WS_LF; }
}

<GS_STEP> {
  {GM_LINE}                     { return G_STEP_TEXT; }
  {LF}                          { yybegin(GHERKIN); return WS_LF; }
}
