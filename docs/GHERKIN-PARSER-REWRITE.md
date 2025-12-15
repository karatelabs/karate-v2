# Parser Infrastructure & IDE Support

## Overview

This document describes the parser infrastructure for error-tolerant parsing and IDE features. The approach uses AST (Abstract Syntax Tree) nodes with error recovery, enabling syntax coloring, code completion, and formatting even when source code is incomplete or invalid.

## Status Summary

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 0 | Parser Infrastructure (Error Recovery) | **COMPLETE** |
| Phase 1 | Gherkin AST Building | **COMPLETE** |
| Phase 2 | Domain Classes Redesign | Planned |
| Phase 3 | AST to Domain Transformation | Integrated into Phase 1 |
| Phase 4 | JavaScript Error Recovery | **COMPLETE** |
| Phase 5 | Code Formatting (JSON-based options) | **NEXT** |
| Phase 6 | Source Reconstitution | Planned |
| Phase 7 | Embedded Language Support (JS in Gherkin) | Planned |

---

## Completed: Phase 0 - Parser Infrastructure

### Files Modified/Created

| File | Changes |
|------|---------|
| `NodeType.java` | Added `ERROR` node type |
| `SyntaxError.java` | **New file** - represents recoverable syntax errors |
| `Parser.java` | Added error recovery infrastructure |

### SyntaxError Class

```java
public class SyntaxError {
    public final Token token;
    public final String message;
    public final NodeType expected;

    public int getLine() { return token.line + 1; }
    public int getColumn() { return token.col + 1; }
    public long getOffset() { return token.pos; }
}
```

### Parser Constructor & Methods

```java
// Constructor with error recovery flag
Parser(Resource resource, boolean gherkin, boolean errorRecovery)

// Error recovery is a protected final field for JIT optimization
protected final boolean errorRecoveryEnabled;
```

| Method | Description |
|--------|-------------|
| `getErrors()` | Returns list of `SyntaxError` |
| `hasErrors()` | Returns true if any errors recorded |
| `error(String)` | Record error (or throw if not recovering) |
| `error(NodeType...)` | Record error for expected node types |
| `error(TokenType...)` | Record error for expected token types |
| `recoverTo(TokenType...)` | Skip tokens until recovery point |
| `consumeSoft(TokenType)` | Consume or record error if missing |
| `exitSoft()` | Exit tolerating incomplete nodes |

---

## Completed: Phase 1 - Gherkin AST Building

### NodeTypes Added

```java
// Gherkin AST node types (in NodeType.java)
G_FEATURE,          // Feature root node
G_TAGS,             // Tags container
G_NAME_DESC,        // Name and description block
G_BACKGROUND,       // Background section
G_SCENARIO,         // Scenario section
G_SCENARIO_OUTLINE, // Scenario Outline section
G_EXAMPLES,         // Examples section
G_STEP,             // Step node
G_STEP_LINE,        // Step text content (RHS)
G_DOC_STRING,       // Doc string (triple quoted)
G_TABLE,            // Table node
G_TABLE_ROW         // Single table row
```

### GherkinParser Rewrite

The parser now:
1. **Builds an AST** with all node types
2. **Enables error recovery** by default
3. **Transforms AST to domain objects** (Feature, Scenario, Step, etc.)
4. **Exposes AST** via `getAst()` for IDE features

```java
GherkinParser parser = new GherkinParser(resource);
Feature feature = parser.parse();      // Domain object
Node ast = parser.getAst();            // AST for IDE features
List<SyntaxError> errors = parser.getErrors();
```

### Lexer Fix

Fixed `js.flex` GS_DESC state to recognize table rows after `Examples:`:
```
{WS_ONE_LF} / ({GM_PREFIX}|{GM_HEADER}|"@"|"|") { yybegin(GHERKIN); return WS_LF; }
```

### Tests Added

- AST structure tests (`testAstStructure`, `testAstAvailable`)
- Error recovery tests (`testMissingFeatureKeyword`, `testIncompleteStep`)
- Feature tests (`testMultipleScenarios`, `testScenarioWithTags`, `testBackground`)
- Table tests (`testTable`)
- Scenario Outline tests (`testScenarioOutlineWithExamples`)

---

## Completed: Phase 4 - JavaScript Error Recovery

### Overview

Applied error recovery to `JsParser.java` so that syntax highlighting and code completion work while typing incomplete JavaScript.

### Files Modified

| File | Changes |
|------|---------|
| `JsParser.java` | Added error recovery mode, `getAst()` method, recovery logic |
| `Parser.java` | Added `consumeSoft()` method for soft token consumption |
| `Node.java` | Added `findAll()` method for AST traversal |

### JsParser API

```java
// Normal mode (throws on errors)
JsParser parser = new JsParser(Resource.text(source));

// Error recovery mode (for IDE)
JsParser parser = new JsParser(Resource.text(source), true);
Node ast = parser.parse();           // Returns AST even with errors
Node astRef = parser.getAst();       // Same as return value
List<SyntaxError> errors = parser.getErrors();
boolean hasErrors = parser.hasErrors();
```

### Recovery Points

| Level | Recovery Tokens | Description |
|-------|-----------------|-------------|
| Statement | `IF`, `FOR`, `WHILE`, `DO`, `SWITCH`, `TRY`, `RETURN`, `THROW`, `BREAK`, `CONTINUE`, `VAR`, `LET`, `CONST`, `FUNCTION`, `L_CURLY`, `R_CURLY`, `SEMI`, `EOF` | Statement starts and ends |

### Key Implementation Patterns

1. **Constructor overload** - `new JsParser(resource, errorRecovery)`
2. **Soft consumption** - `consumeSoft()` records error instead of throwing
3. **Incomplete statement preservation** - Statements with consumed tokens are kept in AST
4. **EOF handling** - All closing tokens (`}`, `)`, `]`) tolerate EOF in recovery mode

### Example AST for Incomplete Code

```javascript
// Input: let x = 1 +
// Produces partial AST with errors recorded:
PROGRAM
└── STATEMENT
    └── EXPR_LIST
        └── EXPR
            └── VAR_STMT
                ├── TOKEN(let)
                ├── VAR_NAMES
                │   └── TOKEN(x)
                ├── TOKEN(=)
                └── EXPR
                    └── MATH_ADD_EXPR
                        ├── LIT_EXPR (1)
                        ├── TOKEN(+)
                        └── ERROR (missing operand)
```

### Tests Added

- `testErrorRecoveryEnabled` - Basic enabling
- `testIncompleteExpression` - `let x = 1 +`
- `testIncompleteBlock` - Unclosed function body
- `testIncompleteIfStatement` - Missing closing paren
- `testIncompleteForLoop` - Incomplete for loop
- `testIncompleteFunctionCall` - Unclosed function call
- `testIncompleteArray` - Unclosed array
- `testIncompleteTemplate` - Unclosed template literal
- `testMultipleStatements` - Recovery between statements
- `testGetAstReturnsCorrectNode` - AST access
- `testErrorRecoveryPreservesAstStructure` - Complete code unchanged
- `testErrorPositionTracking` - Error position info
- `testIncompleteTernary` - Missing colon branch
- `testDotPropertyAccessIncomplete` - Missing property after dot
- `testIncompleteSwitchStatement` - Unclosed switch

---

## Planned: Phase 5 - Code Formatting

### Approach

**Token-based formatting** that adjusts whitespace between tokens while preserving comments and making minimal changes.

### FormatOptions (JSON-based)

Using a JSON-based approach for flexibility and extensibility. This mirrors VS Code's settings model and leverages the existing `Json` class from `karate-core`.

```java
public class FormatOptions {
    private final Json options;

    public FormatOptions() {
        this.options = Json.object();
    }

    public FormatOptions(Json json) {
        this.options = json;
    }

    // Generic accessors with defaults
    public int getInt(String key, int defaultValue) {
        return options.get(key, defaultValue);
    }

    public boolean getBool(String key, boolean defaultValue) {
        return options.get(key, defaultValue);
    }

    // Namespaced keys for JavaScript
    public static final String JS_INDENT_SIZE = "js.indentSize";
    public static final String JS_USE_TABS = "js.useTabs";
    public static final String JS_SPACE_BEFORE_BRACE = "js.spaceBeforeBlockBrace";
    public static final String JS_SPACE_AFTER_COMMA = "js.spaceAfterComma";
    public static final String JS_SPACE_AROUND_OPERATORS = "js.spaceAroundOperators";
    public static final String JS_SPACE_WITHIN_PARENS = "js.spaceWithinParens";
    public static final String JS_SPACE_WITHIN_BRACKETS = "js.spaceWithinBrackets";
    public static final String JS_SPACE_WITHIN_BRACES = "js.spaceWithinBraces";
    public static final String JS_NEWLINE_BEFORE_ELSE = "js.newlineBeforeElse";
    public static final String JS_MAX_LINE_LENGTH = "js.maxLineLength";

    // Namespaced keys for Gherkin
    public static final String GHERKIN_INDENT_SIZE = "gherkin.indentSize";
    public static final String GHERKIN_ALIGN_TABLES = "gherkin.alignTableColumns";
    public static final String GHERKIN_BLANK_LINES_BETWEEN_SCENARIOS = "gherkin.blankLinesBetweenScenarios";

    // Convenience typed accessors (with defaults)
    public int jsIndentSize() { return getInt(JS_INDENT_SIZE, 2); }
    public boolean jsUseTabs() { return getBool(JS_USE_TABS, false); }
    public boolean jsSpaceBeforeBrace() { return getBool(JS_SPACE_BEFORE_BRACE, true); }
    // ... etc
}
```

### Settings File (`format.json`)

User-editable JSON file:

```json
{
  "js.indentSize": 2,
  "js.useTabs": false,
  "js.spaceBeforeBlockBrace": true,
  "js.spaceAfterComma": true,
  "js.spaceAroundOperators": true,
  "js.spaceWithinParens": false,
  "js.spaceWithinBrackets": false,
  "js.spaceWithinBraces": true,
  "js.newlineBeforeElse": false,
  "js.maxLineLength": 120,
  "gherkin.indentSize": 2,
  "gherkin.alignTableColumns": true,
  "gherkin.blankLinesBetweenScenarios": 1
}
```

### Formatter Design

```java
public class Formatter {
    private final FormatOptions options;

    public Formatter(FormatOptions options) {
        this.options = options;
    }

    public String formatJs(String source) {
        List<Token> tokens = Parser.getTokens(Resource.text(source), false);
        StringBuilder result = new StringBuilder();
        for (Token token : tokens) {
            String spacing = computeSpacingBefore(token);
            result.append(spacing).append(token.text);
        }
        return result.toString();
    }

    private String computeSpacingBefore(Token token) {
        // Logic based on token.type, token.prev.type, and options
    }
}
```

### Formatting Strategies

| Strategy | Description | Use Case |
|----------|-------------|----------|
| Token-based | Adjust whitespace between tokens | Default - minimal diff, preserves style |
| AST-based | Reprint from AST | "Reformat entire file" - canonical output |
| Hybrid | Combine both | Best of both worlds |

The strategy can be configurable: `"formatStrategy": "token"` / `"ast"` / `"hybrid"`

### Benefits of JSON-based Approach

1. **User-editable** - settings file is just JSON
2. **Language-agnostic** - one mechanism for JS, Gherkin, future languages
3. **Extensible** - new options without code changes
4. **Serializable** - easy save/load
5. **Leverages existing code** - uses `Json` class from `karate-core`

### IntelliJ Parity

Options can grow to any granularity with namespaced keys:
```json
{
  "js.spaceAfterKeyword.if": true,
  "js.spaceAfterKeyword.for": true,
  "js.blankLinesAfterImports": 1,
  "js.alignConsecutiveAssignments": false
}
```

---

## Planned: Phase 7 - Embedded Language Support

### The Nesting Problem

Gherkin files contain embedded JavaScript, and JS can contain template literals with embedded expressions:

```
* def msg = `Hello ${user.name}!`
  ^^^       ^^^^^^^^^^^^^^^^^^^^^
  Gherkin   JS (embedded in Gherkin)
                   ^^^^^^^^^^^
                   JS expr (embedded in template literal)
```

**Nesting levels:**
1. **Gherkin → JS**: Step RHS contains JavaScript (needs implementation)
2. **JS → JS in templates**: `${expr}` inside template literals (already handled by JsParser)

### Current State

| Level | Parser | Status |
|-------|--------|--------|
| Gherkin | GherkinParser | Works - but captures RHS as `G_RHS` token (unparsed) |
| JS | JsParser | Works - including template literals with `PLACEHOLDER` nodes |
| JS in templates | JsParser | Works - `LIT_TEMPLATE` contains `PLACEHOLDER` with expressions |

### What Needs to Change

**1. Node.java - Add embedded AST support:**

```java
public class Node {
    // Existing fields...

    // For embedded languages
    private Node embeddedAst;
    private int embeddedOffset;

    public void setEmbeddedAst(Node ast, int offset) {
        this.embeddedAst = ast;
        this.embeddedOffset = offset;
    }

    public Node getEmbeddedAst() { return embeddedAst; }
    public int getEmbeddedOffset() { return embeddedOffset; }
    public boolean hasEmbeddedAst() { return embeddedAst != null; }
}
```

**2. GherkinParser - Parse embedded JS:**

```java
public Feature parse() {
    ast = parseAst();
    parseEmbeddedJs(ast);  // NEW: post-process steps
    return transformToFeature(ast);
}

private void parseEmbeddedJs(Node ast) {
    for (Node step : findAllSteps(ast)) {
        Node stepLine = step.findFirstChild(NodeType.G_STEP_LINE);
        if (stepLine == null || stepLine.size() == 0) continue;

        // Extract RHS text and offset
        Token firstToken = stepLine.getFirstToken();
        Token lastToken = stepLine.getLastToken();
        int startOffset = (int) firstToken.pos;
        int endOffset = (int) lastToken.pos + lastToken.text.length();
        String jsText = resource.getText().substring(startOffset, endOffset);

        // Parse as JS with error recovery
        JsParser jsParser = new JsParser(Resource.text(jsText));
        jsParser.enableErrorRecovery();
        try {
            Node jsAst = jsParser.parse();
            step.setEmbeddedAst(jsAst, startOffset);
        } catch (Exception e) {
            // JS parse failed - that's ok, just no embedded highlighting
        }
    }
}
```

**3. Editor - Layer highlighting:**

```java
void applyHighlighting(Node gherkinAst) {
    StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

    // First pass: Gherkin highlighting
    applyGherkinStyles(gherkinAst, spansBuilder);

    // Second pass: Embedded JS highlighting (overwrites step RHS regions)
    for (Node step : findAllSteps(gherkinAst)) {
        if (step.hasEmbeddedAst()) {
            int baseOffset = step.getEmbeddedOffset();
            applyJsStyles(step.getEmbeddedAst(), baseOffset, spansBuilder);
        }
    }

    codeArea.setStyleSpans(0, spansBuilder.create());
}
```

### Template Literal Handling (Already Works)

The JsParser already produces proper AST for template literals:

```javascript
`Hello ${user.name}!`
```

Produces:
```
LIT_TEMPLATE
├── TOKEN(BACKTICK)
├── TOKEN(T_STRING) "Hello "
├── PLACEHOLDER
│   └── MEMBER_EXPR
│       ├── TOKEN(IDENT) "user"
│       ├── TOKEN(DOT)
│       └── TOKEN(IDENT) "name"
├── TOKEN(T_STRING) "!"
└── TOKEN(BACKTICK)
```

The editor just needs to style:
- `T_STRING` tokens as string literals
- `PLACEHOLDER` contents as JS expressions

### Error Handling in Embedded Code

Errors in embedded JS should:
1. Be collected separately from Gherkin errors
2. Have offsets adjusted to file position
3. Show in the editor at correct location

```java
// Collect JS errors with adjusted positions
for (SyntaxError jsError : jsParser.getErrors()) {
    int adjustedOffset = step.getEmbeddedOffset() + (int) jsError.getOffset();
    embeddedErrors.add(new SyntaxError(
        jsError.token,  // might need offset adjustment
        jsError.message,
        jsError.expected
    ));
}
```

### Implementation Order

1. Add `embeddedAst`/`embeddedOffset` to `Node.java`
2. Add `parseEmbeddedJs()` to `GherkinParser`
3. Update editor to apply layered highlighting
4. Adjust error positions for embedded code
5. Test with complex nested cases

---

## UI Integration (Code Editor)

### Error Display with RichTextFX

```java
parser.enableErrorRecovery();
Node node = parser.parse();           // Never throws
List<SyntaxError> errors = parser.getErrors();

// Highlighting always works - even partial code
applyHighlighting(node);

// Underline errors with wavy red line
for (SyntaxError error : errors) {
    int start = (int) error.token.pos;
    int end = start + error.token.text.length();
    addErrorUnderline(start, end, error.message);
}
```

### CSS for Error Styling

```css
.syntax-error {
    -rtfx-underline-color: red;
    -rtfx-underline-width: 1px;
    -rtfx-underline-dash-array: 2 2;  /* Wavy/dashed */
}

.syntax-error-background {
    -rtfx-background-color: rgba(255, 0, 0, 0.1);
}
```

### Node.findNodeAt() for Context

```java
public Node findNodeAt(long offset) {
    for (Node child : this) {
        Token first = child.getFirstToken();
        Token last = child.getLastToken();
        if (first != null && last != null) {
            long start = first.pos;
            long end = last.pos + last.text.length();
            if (offset >= start && offset <= end) {
                Node deeper = child.findNodeAt(offset);
                return deeper != null ? deeper : child;
            }
        }
    }
    return null;
}
```

---

## RichTextFX Editor Session Prompt

Use this section as context when working on the RichTextFX-based code editor in a separate session.

### Context

The karate-v2 repository at `/Users/peter/dev/zcode/karate-v2` contains a parser infrastructure with:

1. **Lexer** (`karate-js/src/main/jflex/js.flex`) - JFlex-based lexer for JavaScript and Gherkin
2. **Parser base** (`karate-js/src/main/java/io/karatelabs/js/Parser.java`) - with error recovery support
3. **Token** (`karate-js/src/main/java/io/karatelabs/js/Token.java`) - includes `pos`, `line`, `col`, `prev`/`next` links
4. **Node** (`karate-js/src/main/java/io/karatelabs/js/Node.java`) - AST node with `type`, `token`, children
5. **SyntaxError** (`karate-js/src/main/java/io/karatelabs/js/SyntaxError.java`) - recoverable error info
6. **JsParser** (`karate-js/src/main/java/io/karatelabs/js/JsParser.java`) - JavaScript parser
7. **GherkinParser** (`karate-js/src/main/java/io/karatelabs/js/GherkinParser.java`) - Gherkin parser with error recovery

### Key APIs for Editor Integration

```java
// JavaScript with error recovery (for IDE)
JsParser parser = new JsParser(Resource.text(source), true);
Node ast = parser.parse();
List<SyntaxError> errors = parser.getErrors();
boolean hasErrors = parser.hasErrors();

// Gherkin with error recovery (for IDE)
GherkinParser gParser = new GherkinParser(Resource.text(source), true);
Feature feature = gParser.parse();
Node gAst = gParser.getAst();

// Token info for styling
Token token = node.getFirstToken();
int line = token.line;           // 0-indexed
int col = token.col;             // 0-indexed
long pos = token.pos;            // character offset
String text = token.text;
TokenType type = token.type;

// Whitespace access (for formatting)
Token prev = token.prev;         // includes whitespace tokens
Token next = token.next;

// AST traversal
Node child = ast.findFirstChild(NodeType.G_STEP);
Node atPos = ast.findNodeAt(offset);  // needs to be added
String source = node.getTextIncludingWhitespace();
```

### Tasks for Editor

1. **Syntax highlighting** - Map `TokenType` and `NodeType` to CSS classes
2. **Error display** - Show `SyntaxError` list with wavy underlines
3. **Real-time parsing** - Parse on keystroke with debouncing (~100ms)
4. **Code formatting** - Implement `JsFormatter` with `JsFormatOptions`
5. **Format on save** - Optional auto-format

### RichTextFX Specifics

- Use `StyleSpans` for syntax highlighting
- Use `token.pos` as offset for `StyleSpans.create()`
- For error underlines, RichTextFX supports `-rtfx-underline-color` CSS property
- Consider `CodeArea` vs `InlineCssTextArea` based on styling needs

### File References

| Purpose | File |
|---------|------|
| Token types | `karate-js/src/main/java/io/karatelabs/js/TokenType.java` |
| Node types | `karate-js/src/main/java/io/karatelabs/js/NodeType.java` |
| Error class | `karate-js/src/main/java/io/karatelabs/js/SyntaxError.java` |
| Parser base | `karate-js/src/main/java/io/karatelabs/js/Parser.java` |
| JS Parser | `karate-js/src/main/java/io/karatelabs/js/JsParser.java` |
| Gherkin Parser | `karate-js/src/main/java/io/karatelabs/js/GherkinParser.java` |
| Tests | `karate-js/src/test/java/io/karatelabs/gherkin/GherkinParserTest.java` |

---

## Position Tracking Summary

Token already has all necessary position information:

| Field | Type | Description |
|-------|------|-------------|
| `pos` | `long` | Character offset from start of file |
| `line` | `int` | 0-indexed line number |
| `col` | `int` | 0-indexed column number |
| `text` | `String` | Token text (for length: `text.length()`) |

Node position can be derived:
- Start: `node.getFirstToken().pos`, `.line`, `.col`
- End: `node.getLastToken().pos + node.getLastToken().text.length()`
- Range: All tokens between first and last (including whitespace via `token.prev`/`token.next`)

---

## References

- IntelliJ Platform SDK: [PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- Tree-sitter: [Error Recovery](https://tree-sitter.github.io/tree-sitter/creating-parsers#error-recovery)
- RichTextFX: [GitHub](https://github.com/FXMisc/RichTextFX)
- JsParser: `karate-js/src/main/java/io/karatelabs/js/JsParser.java`
- Lexer: `karate-js/src/main/jflex/js.flex`
