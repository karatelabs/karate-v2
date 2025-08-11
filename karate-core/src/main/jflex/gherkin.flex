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
package io.karatelabs.gherkin;

import static io.karatelabs.gherkin.GherkinToken.*;

%%

%class GherkinLexer
%unicode
%type GherkinToken

%{

%}

WS = [ \t]
LF = \R
NOT_LF = [^\r\n]+

HEADER = "Scenario:"|"Scenario Outline:"|"Examples:"|"Background:"
PREFIX = "*"|"Given"|"When"|"Then"|"And"|"But"
TRIPLE_QUOTE = \"\"\"

%state DESC STEP DOC_STRING TABLE_ROW TAGS G_COMMENT

%%

// applies to all states
<<EOF>>                         { return EOF; }

<YYINITIAL> {
  {WS}* {LF} {WS}*              { return WS_LF; }
  {WS}+                         { return WS; }
  {PREFIX}                      { yybegin(STEP); return PREFIX; }
  "#"                           { yypushback(1); yybegin(G_COMMENT); }
  "Scenario:"                   { yybegin(DESC); return SCENARIO; }
  "Scenario Outline:"           { yybegin(DESC); return SCENARIO_OUTLINE; }
  "Examples:"                   { yybegin(DESC); return EXAMPLES; }
  "Feature:"                    { yybegin(DESC); return FEATURE; }
  "Background:"                 { yybegin(DESC); return BACKGROUND; }
  {TRIPLE_QUOTE}                { yybegin(DOC_STRING); return TRIPLE_QUOTE; }
  "|"                           { yybegin(TABLE_ROW); return PIPE; }
  "@" [^ \t\r\n]+               { yybegin(TAGS); return TAG; }
}

<G_COMMENT> {
  {NOT_LF}                      { return COMMENT; }
  {LF}                          { yybegin(YYINITIAL); return WS_LF; }
}

<DESC> {
  {LF} {WS}* / ({HEADER}|{PREFIX}|"#"|"@"|"|") { yybegin(YYINITIAL); return WS_LF; } // starting LF is important
  {LF}                          { return WS_LF; }
  {NOT_LF}                      { return DESCRIPTION; }
}

<TAGS> {
  "@" [^ \t\r\n]+               { return TAG; }
  {LF}                          { yybegin(YYINITIAL); return WS_LF; }
}

<TABLE_ROW> {
  [^ \t\|\r\n]+                 { return TABLE_CELL; }
  "|"                           { return PIPE; }
  {LF}                          { yybegin(YYINITIAL); return WS_LF; }
}

<STEP> {
  {LF}                          { yybegin(YYINITIAL); return WS_LF; }
  {NOT_LF}                      { return STEP_TEXT; }
}

