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

import static io.karatelabs.js.Token.*;
%%

%class Lexer
%unicode
%type Token

%{
boolean regexAllowed = true; // start with true since a regex can appear at the start of a program
ArrayDeque<Integer> kkStack;
void kkPush() { if (kkStack == null) kkStack = new ArrayDeque<>(); kkStack.push(yystate()); }
int kkPop() { return kkStack.pop(); }
private Token update(Token token) { if (token.regexAllowed != null) regexAllowed = token.regexAllowed; return token; }
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
T_STRING = [^`$]+ ("$"[^{])?
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
  {WS}* {LF} {WS}*              { return update(WS_LF); }
  {WS}+                         { return update(WS); }
  "`"                           { kkPush(); yybegin(TEMPLATE); return update(BACKTICK); }
  "{"                           { return update(L_CURLY); }
  //====                        { return update(R_CURLY); }
  "["                           { return update(L_BRACKET); }
  "]"                           { return update(R_BRACKET); }
  "("                           { return update(L_PAREN); }
  ")"                           { return update(R_PAREN); }
  ","                           { return update(COMMA); }
  ":"                           { return update(COLON); }
  ";"                           { return update(SEMI); }
  "..."                         { return update(DOT_DOT_DOT); }
  "."                           { return update(DOT); }
  //====
  "null"                        { return update(NULL); }
  "true"                        { return update(TRUE); }
  "false"                       { return update(FALSE); }
  "function"                    { return update(FUNCTION); }
  "return"                      { return update(RETURN); }
  "try"                         { return update(TRY); }
  "catch"                       { return update(CATCH); }
  "finally"                     { return update(FINALLY); }
  "throw"                       { return update(THROW); }
  "new"                         { return update(NEW); }
  "var"                         { return update(VAR); }
  "let"                         { return update(LET); }
  "const"                       { return update(CONST); }
  "if"                          { return update(IF); }
  "else"                        { return update(ELSE); }
  "typeof"                      { return update(TYPEOF); }
  "instanceof"                  { return update(INSTANCEOF); }
  "delete"                      { return update(DELETE); }
  "for"                         { return update(FOR); }
  "in"                          { return update(IN); }
  "of"                          { return update(OF); }
  "do"                          { return update(DO); }
  "while"                       { return update(WHILE); }
  "switch"                      { return update(SWITCH); }
  "case"                        { return update(CASE); }
  "default"                     { return update(DEFAULT); }
  "break"                       { return update(BREAK); }
  //====
  "==="                         { return update(EQ_EQ_EQ); }
  "=="                          { return update(EQ_EQ); }
  "="                           { return update(EQ); }
  "=>"                          { return update(EQ_GT); }
  "<<="                         { return update(LT_LT_EQ); }
  "<<"                          { return update(LT_LT); }
  "<="                          { return update(LT_EQ); }
  "<"                           { return update(LT); }
  ">>>="                        { return update(GT_GT_GT_EQ); }
  ">>>"                         { return update(GT_GT_GT); }
  ">>="                         { return update(GT_GT_EQ); }
  ">>"                          { return update(GT_GT); }
  ">="                          { return update(GT_EQ); }
  ">"                           { return update(GT); }
  //====
  "!=="                         { return update(NOT_EQ_EQ); }
  "!="                          { return update(NOT_EQ); }
  "!"                           { return update(NOT); }
  "||="                         { return update(PIPE_PIPE_EQ); }
  "||"                          { return update(PIPE_PIPE); }
  "|="                          { return update(PIPE_EQ); }
  "|"                           { return update(PIPE); }
  "&&="                         { return update(AMP_AMP_EQ); }
  "&&"                          { return update(AMP_AMP); }
  "&="                          { return update(AMP_EQ); }
  "&"                           { return update(AMP); }
  "^="                          { return update(CARET_EQ); }
  "^"                           { return update(CARET); }
  "??"                          { return update(QUES_QUES); }
  "?"                           { return update(QUES); }
  //====
  "++"                          { return update(PLUS_PLUS); }
  "+="                          { return update(PLUS_EQ); }
  "+"                           { return update(PLUS); }
  "--"                          { return update(MINUS_MINUS); }
  "-="                          { return update(MINUS_EQ); }
  "-"                           { return update(MINUS); }
  "**="                         { return update(STAR_STAR_EQ); }
  "**"                          { return update(STAR_STAR); }
  "*="                          { return update(STAR_EQ); }
  "*"                           { return update(STAR); }
  "/="                          { return update(SLASH_EQ); }
  "%="                          { return update(PERCENT_EQ); }
  "%"                           { return update(PERCENT); }
  "~"                           { return update(TILDE); }
  "/"                           { return regexAllowed ? update(REGEX) : update(SLASH); }
  //====
  {REGEX}                       { if (regexAllowed) return update(REGEX); yypushback(yylength() - 1); return update(SLASH); }
  {L_COMMENT}                   { return update(L_COMMENT); }
  {B_COMMENT}                   { return update(B_COMMENT); }
  {S_STRING}                    { return update(S_STRING); }
  {D_STRING}                    { return update(D_STRING); }
  {NUMBER} | {HEX}              { return update(NUMBER); }
  {IDENT}                       { return update(IDENT); }
}

<YYINITIAL> "}"                 { return update(R_CURLY); }

<PLACEHOLDER> "}"               { yybegin(kkPop()); return update(R_CURLY); }

<TEMPLATE> {
    "`"                         { yybegin(kkPop()); return update(BACKTICK); }
    "$"                         { return update(T_STRING); }
    "${"                        { kkPush(); yybegin(PLACEHOLDER); return update(DOLLAR_L_CURLY); }
    {T_STRING}                  { return update(T_STRING); }
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
  "|"                           { yybegin(GS_TABLE_ROW); return G_PIPE; }
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
