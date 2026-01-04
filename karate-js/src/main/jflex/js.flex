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
private void rewind(int state) { yybegin(state); yypushback(yylength()); }
%}

WS = [ \t]
LF = \R
WS_ONE_LF = {WS}* {LF} {WS}*
NOT_LF = [^\n]
NOT_WSLF = [^ \t\n]

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
DOT_IDENT = {IDENT} ("." {IDENT})+
T_STRING = [^`$]+ | "$"[^{$`]+
REGEX = "/" [^*/\n] ([^/\\\n]|\\{NOT_LF})* "/" [:jletter:]*

%state TEMPLATE PLACEHOLDER

// gherkin macros (gm)
GM_HEADER = "Scenario:"|"Scenario Outline:"|"Examples:"|"Background:"
GM_PREFIX = "*"|"Given"|"When"|"Then"|"And"|"But"
GM_TYPE_KEYWORD = "def"|"json"|"xml"|"xmlstring"|"yaml"|"csv"|"string"|"bytes"|"copy"
GM_ASSIGN_KEYWORD = "configure"|"header"|"param"|"cookie"|"form field"|"multipart file"|"multipart field"
GM_SPACED_KEYWORD = "form fields"|"multipart fields"|"multipart files"|"soap action"|"retry until"|"multipart entity"
GM_MATCH_TYPE = ("=="|"!="|"contains"|"!contains"|"within"|"!within") ({WS}+("only"|"any"))? ({WS}+"deep")?
GM_TRIPLE_QUOTE = \"\"\"
GM_TAG = "@" {NOT_WSLF}+

// gherkin states (gs)
%state GHERKIN GS_DESC GS_DOC_STRING GS_TABLE_ROW GS_TAGS GS_COMMENT GS_STEP GS_STEP_MATCH GS_RHS

%%

// applies to all states
<<EOF>>                         { return EOF; }
{WS}+                           { return WS; }

<YYINITIAL, PLACEHOLDER> {
  {WS_ONE_LF}                   { return WS_LF; }
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
  "continue"                    { return tt(CONTINUE); }
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

<YYINITIAL> {
  "}"                           { return tt(R_CURLY); }
}

<TEMPLATE> {
  "`"                           { yybegin(kkPop()); return tt(BACKTICK); }
  "$"                           { return tt(T_STRING); }
  "${"                          { kkPush(); yybegin(PLACEHOLDER); return tt(DOLLAR_L_CURLY); }
  {T_STRING}                    { return tt(T_STRING); }
}

<PLACEHOLDER> {
  "}"                           { yybegin(kkPop()); return tt(R_CURLY); }
}

//======================================================================================================================

<GHERKIN> {
  {WS_ONE_LF}                   { return WS_LF; }
  {GM_PREFIX}                   { yybegin(GS_STEP); return G_PREFIX; }
  "#"                           { rewind(GS_COMMENT); }
  "Scenario:"                   { yybegin(GS_DESC); return G_SCENARIO; }
  "Scenario Outline:"           { yybegin(GS_DESC); return G_SCENARIO_OUTLINE; }
  "Examples:"                   { yybegin(GS_DESC); return G_EXAMPLES; }
  "Feature:"                    { yybegin(GS_DESC); return G_FEATURE; }
  "Background:"                 { yybegin(GS_DESC); return G_BACKGROUND; }
  {GM_TRIPLE_QUOTE}             { yybegin(GS_DOC_STRING); return G_TRIPLE_QUOTE; }
  "|"                           { rewind(GS_TABLE_ROW); }
  {GM_TAG}                      { yybegin(GS_TAGS); return G_TAG; }
}

<GS_COMMENT> {
  {NOT_LF}+                     { return G_COMMENT; }
  {WS_ONE_LF}                   { yybegin(GHERKIN); return WS_LF; }
}

<GS_DESC> {
  {NOT_LF}+                     { return G_DESC; }
  {WS_ONE_LF} / ({GM_PREFIX}|{GM_HEADER}|"@"|"|") { yybegin(GHERKIN); return WS_LF; }
  {WS_ONE_LF}                   { return WS_LF; }
}

<GS_TAGS> {
  {GM_TAG}                      { return G_TAG; }
  {WS_ONE_LF}                   { yybegin(GHERKIN); return WS_LF; }
}

<GS_TABLE_ROW> {
  [^\|\n]+                      { return G_TABLE_CELL; }
  "|"                           { return G_PIPE; }
  {WS_ONE_LF}                   { yybegin(GHERKIN); return WS_LF; }
}

<GS_STEP> {
  "match"                       { yybegin(GS_STEP_MATCH); return G_KEYWORD; }
  {GM_TYPE_KEYWORD} | {GM_ASSIGN_KEYWORD} { return G_KEYWORD; }
  {GM_SPACED_KEYWORD}           { yybegin(GS_RHS); return G_KEYWORD; }
  {DOT_IDENT} / {WS_ONE_LF}     { yybegin(GHERKIN); return G_KEYWORD; } // dotted keyword for docstring or table
  {IDENT} / {WS_ONE_LF}         { yybegin(GHERKIN); return G_KEYWORD; } // docstring or table
  "=" / {WS_ONE_LF}             { yybegin(GHERKIN); return EQ; } // docstring or table
  {DOT_IDENT} / [\[(]           { rewind(GS_RHS); } // js expression like foo.bar[x] or foo.bar()
  {IDENT} / [\[(]               { rewind(GS_RHS); } // js expression like foo[x]
  {DOT_IDENT}                   { return G_KEYWORD; } // dotted keyword like foo.bar
  {IDENT}                       { return G_KEYWORD; }
  "="                           { yybegin(GS_RHS); return EQ; }
  {NOT_WSLF}                    { rewind(GS_RHS); } // js
  {WS_ONE_LF}                   { yybegin(GHERKIN); return WS_LF; }
}

<GS_STEP_MATCH> {
  "each" | "header"             { return G_KEYWORD; }
  {GM_MATCH_TYPE} / {WS_ONE_LF} { yybegin(GHERKIN); return G_KEYWORD; } // docstring
  {GM_MATCH_TYPE}               { yybegin(GS_RHS); return G_KEYWORD; }
  {S_STRING}                    { return G_EXPR; } // quoted strings as JS expressions
  {D_STRING}                    { return G_EXPR; } // quoted strings as JS expressions
  {IDENT}(-{IDENT})*            { return IDENT; } // allow hyphenated like Content-Type
  ("$"|"@") {IDENT}?            { return IDENT; } // xpath
  "?" "(" [^)]+ ")"             { return IDENT; } // json path
  ".." | ":"                    { return DOT; } // json path
  "/" "/"? {IDENT}?             { return IDENT; } // xpath
  [^\s]                         { return G_EXPR; } // fallback: any non-whitespace is part of match expression
}

<GS_RHS> {
  {NOT_LF}+                     { return G_EXPR; }
  {WS_ONE_LF}                   { yybegin(GHERKIN); return WS_LF; }
}

<GS_DOC_STRING> {
  {GM_TRIPLE_QUOTE}             { yybegin(GHERKIN); return G_TRIPLE_QUOTE; }
  // Match content without quotes, or 1-2 quotes (not triple)
  // This prevents {NOT_LF}+ from matching """ as part of longer content
  [^\n\"]+                      { return G_EXPR; }
  \"\"?                         { return G_EXPR; }
  {WS_ONE_LF}                   { return WS_LF; }
}
