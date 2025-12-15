# Gherkin Parser Rewrite - AST Node Approach

## Overview

This document describes the plan to rewrite `GherkinParser.java` to use an AST (Abstract Syntax Tree) node approach, similar to how `JsParser.java` works. A key priority is **error-tolerant parsing** that produces a useful AST even when the source code is incomplete or invalid - essential for IDE features like syntax coloring and code completion.

## Motivation

1. **IDE Support** - Syntax coloring, code completion, and navigation while user is typing
2. **Error Recovery** - Parser produces best-effort AST even with syntax errors
3. **LSP Compatibility** - Support Language Server Protocol for any editor
4. **Code Formatting** - Tree structure enables precise reformatting
5. **Linting** - Structural analysis for code quality checks
6. **Better Error Reporting** - AST nodes track positions for precise diagnostics

## Priority: Error-Tolerant Parsing (Phase 0)

Before rewriting GherkinParser, we need to enhance the Parser infrastructure to support error recovery. This is critical for IDE use cases where users are actively typing incomplete code.

### How IntelliJ Does It

IntelliJ's PSI (Program Structure Interface) uses these key concepts:

1. **Incomplete nodes are valid** - A partial expression like `a +` creates an AST node with an ERROR child for the missing operand
2. **Recovery tokens** - Parser knows which tokens can "restart" parsing (like keywords, statement boundaries)
3. **Error elements** - Special AST nodes that hold unexpected/invalid tokens
4. **Lazy re-parsing** - Only affected subtrees are re-parsed on edits

### Recovery Points for Gherkin

Gherkin has natural recovery points at structural boundaries:

| Level | Recovery Tokens | Description |
|-------|-----------------|-------------|
| Feature | `G_FEATURE` | Start of feature file |
| Section | `G_SCENARIO`, `G_SCENARIO_OUTLINE`, `G_BACKGROUND`, `G_EXAMPLES` | Section headers |
| Step | `G_PREFIX` (`*`, `Given`, `When`, `Then`, `And`, `But`) | Step boundaries |
| Line | `WS_LF` | Line boundaries (within steps) |
| Table | `G_PIPE` at line start | Table row boundaries |

### Recovery Points for JavaScript

| Level | Recovery Tokens | Description |
|-------|-----------------|-------------|
| Statement | `IF`, `FOR`, `WHILE`, `FUNCTION`, `VAR`, `LET`, `CONST`, `RETURN`, `L_CURLY` | Statement starts |
| Block | `R_CURLY` | Block end |
| Expression | `SEMI`, `COMMA`, `R_PAREN`, `R_BRACKET` | Expression boundaries |
| Line | `WS_LF` | Line boundaries (for ASI) |

### Proposed Parser Infrastructure Changes

#### 1. Error Node Type

Add to `NodeType.java`:

```java
ERROR,  // Contains tokens that couldn't be parsed
```

#### 2. Error Collection in Parser

Add to `Parser.java`:

```java
public class Parser {

    // Existing fields...

    private boolean errorRecoveryEnabled = false;
    private final List<ParseError> errors = new ArrayList<>();

    public static class ParseError {
        public final Token token;
        public final String message;
        public final NodeType expected;

        ParseError(Token token, String message, NodeType expected) {
            this.token = token;
            this.message = message;
            this.expected = expected;
        }
    }

    public List<ParseError> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    // Enable error recovery mode
    protected void enableErrorRecovery() {
        this.errorRecoveryEnabled = true;
    }

    // Record error without throwing
    protected void recordError(String message) {
        if (errorRecoveryEnabled) {
            Token token = peekToken();
            errors.add(new ParseError(token, message, null));
        } else {
            error(message);
        }
    }

    protected void recordError(NodeType... expected) {
        if (errorRecoveryEnabled) {
            Token token = peekToken();
            errors.add(new ParseError(token, "expected: " + Arrays.asList(expected), expected[0]));
        } else {
            error(expected);
        }
    }
}
```

#### 3. Recovery Methods

```java
public class Parser {

    // Skip tokens until we find a recovery point
    protected boolean recoverTo(TokenType... recoveryTokens) {
        Set<TokenType> recoverySet = Set.of(recoveryTokens);
        while (true) {
            TokenType current = peek();
            if (current == EOF || recoverySet.contains(current)) {
                return current != EOF;
            }
            // Consume into ERROR node
            consumeNext();
        }
    }

    // Try to parse, recover on failure
    protected boolean tryParse(Runnable parseAction, TokenType... recoveryTokens) {
        int startPos = position;
        try {
            parseAction.run();
            return true;
        } catch (ParserException e) {
            if (!errorRecoveryEnabled) {
                throw e;
            }
            // Record error
            errors.add(new ParseError(tokens.get(startPos), e.getMessage(), null));
            // Create ERROR node with skipped tokens
            enter(NodeType.ERROR);
            recoverTo(recoveryTokens);
            exit();
            return false;
        }
    }

    // Enter with recovery - creates ERROR node on failure instead of throwing
    protected boolean enterWithRecovery(NodeType type, TokenType... startTokens) {
        if (peekAnyOf(startTokens)) {
            return enter(type, startTokens);
        }
        if (errorRecoveryEnabled) {
            // Create incomplete node anyway for IDE support
            enter(type);
            recordError("expected: " + Arrays.asList(startTokens));
            return true; // Allow parsing to continue with incomplete node
        }
        return false;
    }
}
```

#### 4. Soft Exit (Exit with Errors)

```java
public class Parser {

    // Exit that tolerates incomplete nodes
    protected boolean exitSoft() {
        // Even if node is incomplete, add it to parent
        // This preserves partial AST for IDE features
        return exit(true, false, Shift.NONE);
    }

    // Exit that marks node as having errors
    protected boolean exitWithError(String message) {
        recordError(message);
        return exit(true, false, Shift.NONE);
    }
}
```

### Example: Error-Tolerant Step Parsing

```java
// Before: Fails on incomplete step
private boolean step() {
    if (!enter(NodeType.G_STEP, G_PREFIX)) {
        return false;
    }
    consume(G_KEYWORD);  // Throws if missing
    step_line();
    return exit();
}

// After: Produces partial AST
private boolean step() {
    if (!enter(NodeType.G_STEP, G_PREFIX)) {
        return false;
    }

    // Keyword is optional - step might be incomplete
    if (peekIf(G_KEYWORD)) {
        consumeNext();
    } else if (!peekAnyOf(WS_LF, EOF, G_PREFIX, G_SCENARIO)) {
        // Not at end of step, but no keyword - record error, try to continue
        recordError(G_KEYWORD);
    }

    // Parse step content if present
    if (!peekAnyOf(WS_LF, EOF, G_PREFIX, G_SCENARIO)) {
        step_line();
    }

    // Check for docstring or table
    docString();
    table();

    return exit(); // Always exits successfully, may have recorded errors
}
```

### Example: Incomplete Expression

```javascript
// User is typing: let x = 1 +
// Should produce AST:
//   VAR_STMT
//   ├── TOKEN(let)
//   ├── VAR_NAMES
//   │   └── TOKEN(x)
//   ├── TOKEN(=)
//   └── EXPR
//       └── MATH_ADD_EXPR
//           ├── LIT_EXPR
//           │   └── TOKEN(1)
//           ├── TOKEN(+)
//           └── ERROR (missing operand)
```

### AST Completeness Guarantee

With error recovery enabled:

1. **Every token appears in the AST** - Either in a valid node or an ERROR node
2. **Structure is preserved** - Recognizable constructs get proper node types
3. **Errors are localized** - One error doesn't corrupt entire tree
4. **Positions are accurate** - Each node knows its source range

This enables:
- **Syntax coloring**: ERROR nodes get error highlighting, valid nodes get normal colors
- **Code completion**: Parser knows context (inside function, after keyword, etc.)
- **Navigation**: Jump to definition works for parsed identifiers
- **Formatting**: Valid portions can be formatted normally

---

## Phase 1: Gherkin AST Building

### New NodeTypes for Gherkin

Add to `NodeType.java`:

```java
// Gherkin AST node types
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
G_TABLE_ROW,        // Single table row
```

### Gherkin AST Structure

```
G_FEATURE
├── G_TAGS?
│   └── TOKEN(G_TAG)+
├── TOKEN(G_FEATURE)
├── G_NAME_DESC?
│   ├── TOKEN(G_DESC)     // name (first line)
│   └── TOKEN(G_DESC)*    // description (subsequent lines)
├── G_BACKGROUND?
│   ├── TOKEN(G_BACKGROUND)
│   ├── G_NAME_DESC?
│   └── G_STEP*
└── (G_SCENARIO | G_SCENARIO_OUTLINE)*
    ├── G_TAGS?
    ├── TOKEN(G_SCENARIO | G_SCENARIO_OUTLINE)
    ├── G_NAME_DESC?
    ├── G_STEP*
    └── G_EXAMPLES*        // only for outline
        ├── G_TAGS?
        ├── TOKEN(G_EXAMPLES)
        ├── G_NAME_DESC?
        └── G_TABLE
```

### Step AST Structure

```
G_STEP
├── TOKEN(G_PREFIX)           // *, Given, When, Then, And, But
├── TOKEN(G_KEYWORD)?         // def, match, url, method, etc.
├── G_STEP_LINE?              // remaining tokens on line
│   └── TOKEN(G_KEYWORD | IDENT | EQ | G_RHS)+
└── (G_DOC_STRING | G_TABLE)?
```

### Table AST Structure

```
G_TABLE
└── G_TABLE_ROW+
    ├── TOKEN(G_PIPE)
    ├── TOKEN(G_TABLE_CELL)
    ├── TOKEN(G_PIPE)
    ├── TOKEN(G_TABLE_CELL)
    └── TOKEN(G_PIPE)
```

### GherkinParser Implementation

```java
public class GherkinParser extends Parser {

    public GherkinParser(Resource resource) {
        super(resource, true);
        enableErrorRecovery(); // Enable for IDE support
    }

    public Node parse() {
        enter(NodeType.G_FEATURE);
        tags();
        if (!consumeIf(G_FEATURE)) {
            recordError(G_FEATURE);
            // Try to recover - look for any section start
            recoverTo(G_SCENARIO, G_SCENARIO_OUTLINE, G_BACKGROUND, EOF);
        }
        name_desc();
        background();
        while (true) {
            if (peek() == EOF) break;
            if (!scenario() && !scenarioOutline()) {
                // Unknown token - create ERROR and skip to next section
                enter(NodeType.ERROR);
                recoverTo(G_SCENARIO, G_SCENARIO_OUTLINE, G_PREFIX, EOF);
                exit();
            }
        }
        consume(EOF);
        exit();
        return markerNode().getFirst(); // Return G_FEATURE node
    }

    private boolean tags() {
        if (!peekIf(G_TAG)) {
            return false;
        }
        enter(NodeType.G_TAGS);
        while (consumeIf(G_TAG)) {
            // Collect all tags
        }
        return exit();
    }

    private boolean name_desc() {
        if (!peekIf(G_DESC)) {
            return false;
        }
        enter(NodeType.G_NAME_DESC);
        while (consumeIf(G_DESC)) {
            // Collect name and description lines
        }
        return exit();
    }

    private boolean background() {
        if (!enter(NodeType.G_BACKGROUND, G_BACKGROUND)) {
            return false;
        }
        name_desc();
        while (step()) {
            // Collect steps
        }
        return exit();
    }

    private boolean scenario() {
        if (!peekIf(G_SCENARIO)) {
            return false;
        }
        tags(); // Tags before scenario
        if (!enter(NodeType.G_SCENARIO, G_SCENARIO)) {
            return false;
        }
        name_desc();
        while (step()) {
            // Collect steps
        }
        return exit();
    }

    private boolean scenarioOutline() {
        if (!peekIf(G_SCENARIO_OUTLINE) && !peekIf(G_TAG)) {
            return false;
        }
        tags(); // Tags before outline
        if (!enter(NodeType.G_SCENARIO_OUTLINE, G_SCENARIO_OUTLINE)) {
            // Tags were consumed but no outline - error
            recordError(G_SCENARIO_OUTLINE);
            return false;
        }
        name_desc();
        while (step()) {
            // Collect steps
        }
        while (examples()) {
            // Collect examples tables
        }
        return exit();
    }

    private boolean examples() {
        if (!peekIf(G_EXAMPLES) && !peekIf(G_TAG)) {
            return false;
        }
        tags();
        if (!enter(NodeType.G_EXAMPLES, G_EXAMPLES)) {
            return false;
        }
        name_desc();
        table();
        return exit();
    }

    private boolean step() {
        if (!enter(NodeType.G_STEP, G_PREFIX)) {
            return false;
        }
        // Optional keyword
        consumeIf(G_KEYWORD);
        // Step line content
        step_line();
        // Optional docstring or table
        docString();
        table();
        return exit();
    }

    private boolean step_line() {
        if (!peekAnyOf(G_KEYWORD, IDENT, EQ, G_RHS)) {
            return false;
        }
        enter(NodeType.G_STEP_LINE);
        while (peekAnyOf(G_KEYWORD, IDENT, EQ, G_RHS)) {
            consumeNext();
        }
        return exit();
    }

    private boolean docString() {
        if (!enter(NodeType.G_DOC_STRING, G_TRIPLE_QUOTE)) {
            return false;
        }
        // Consume content until closing quotes
        while (!peekIf(G_TRIPLE_QUOTE) && !peekIf(EOF)) {
            consumeNext(); // G_RHS, WS_LF
        }
        if (!consumeIf(G_TRIPLE_QUOTE)) {
            recordError(G_TRIPLE_QUOTE); // Unclosed docstring
        }
        return exit();
    }

    private boolean table() {
        if (!peekIf(G_PIPE)) {
            return false;
        }
        enter(NodeType.G_TABLE);
        while (tableRow()) {
            // Collect rows
        }
        return exit();
    }

    private boolean tableRow() {
        if (!enter(NodeType.G_TABLE_ROW, G_PIPE)) {
            return false;
        }
        while (true) {
            if (consumeIf(G_TABLE_CELL)) {
                if (!consumeIf(G_PIPE)) {
                    recordError(G_PIPE);
                    break;
                }
            } else {
                break;
            }
        }
        return exit();
    }
}
```

---

## Phase 2: AST to Domain Transformation

### GherkinTransformer

```java
public class GherkinTransformer {

    private final Resource resource;
    private final List<Parser.ParseError> errors;

    public GherkinTransformer(Resource resource, List<Parser.ParseError> errors) {
        this.resource = resource;
        this.errors = errors;
    }

    public Feature transform(Node ast) {
        Feature feature = new Feature(resource);

        for (Node child : ast) {
            switch (child.type) {
                case G_TAGS -> feature.setTags(transformTags(child));
                case TOKEN -> {
                    if (child.token.type == G_FEATURE) {
                        feature.setLine(child.token.line + 1);
                    }
                }
                case G_NAME_DESC -> {
                    Pair<String> nd = transformNameDesc(child);
                    feature.setName(nd.left);
                    feature.setDescription(nd.right);
                }
                case G_BACKGROUND -> feature.setBackground(transformBackground(child));
                case G_SCENARIO -> feature.addSection(transformScenarioSection(child));
                case G_SCENARIO_OUTLINE -> feature.addSection(transformOutlineSection(child));
                case ERROR -> {
                    // Skip error nodes for runtime, but could log/report
                }
            }
        }
        return feature;
    }

    private List<Tag> transformTags(Node tagsNode) {
        List<Tag> tags = new ArrayList<>();
        for (Node child : tagsNode) {
            if (child.isToken() && child.token.type == G_TAG) {
                String text = child.token.text;
                tags.add(new Tag(child.token.line + 1, text));
            }
        }
        return tags;
    }

    private Pair<String> transformNameDesc(Node node) {
        String name = null;
        StringBuilder desc = new StringBuilder();
        boolean first = true;
        for (Node child : node) {
            if (child.isToken() && child.token.type == G_DESC) {
                String text = StringUtils.trimToNull(child.token.text);
                if (first) {
                    name = text;
                    first = false;
                } else if (text != null) {
                    if (!desc.isEmpty()) desc.append('\n');
                    desc.append(text);
                }
            }
        }
        String description = desc.isEmpty() ? null : desc.toString();
        return Pair.of(name, description);
    }

    private Background transformBackground(Node node) {
        Background bg = new Background();
        List<Step> steps = new ArrayList<>();

        for (Node child : node) {
            switch (child.type) {
                case TOKEN -> {
                    if (child.token.type == G_BACKGROUND) {
                        bg.setLine(child.token.line + 1);
                    }
                }
                case G_STEP -> steps.add(transformStep(null, steps.size(), child));
            }
        }
        bg.setSteps(steps);
        return bg;
    }

    private Step transformStep(Scenario scenario, int index, Node node) {
        Feature feature = scenario != null ? scenario.getFeature() : this.feature;
        Step step = scenario != null ? new Step(scenario, index) : new Step(feature, index);

        for (Node child : node) {
            if (child.isToken()) {
                switch (child.token.type) {
                    case G_PREFIX -> {
                        step.setPrefix(child.token.text.trim());
                        step.setLine(child.token.line + 1);
                    }
                    case G_KEYWORD -> step.setKeyword(child.token.text);
                }
            } else {
                switch (child.type) {
                    case G_STEP_LINE -> step.setText(extractStepText(child));
                    case G_DOC_STRING -> step.setDocString(extractDocString(child));
                    case G_TABLE -> step.setTable(transformTable(child));
                }
            }
        }

        // Calculate end line
        Token lastToken = node.getLastToken();
        step.setEndLine(lastToken.line + 1);

        return step;
    }

    // ... additional transform methods
}
```

---

## Phase 3: Testing Strategy

### 1. Error Recovery Tests

```java
@Test
void testIncompleteStep() {
    // Step without RHS
    feature("""
            Feature: test
            Scenario: incomplete
              * def
            """);
    // Should parse without throwing
    assertNotNull(scenario);
    assertEquals(1, scenario.getSteps().size());
    Step step = scenario.getSteps().get(0);
    assertEquals("def", step.getKeyword());
    assertNull(step.getText()); // Missing RHS
}

@Test
void testMissingFeatureKeyword() {
    feature("""
            @tag
            Scenario: orphan
              * print 'hello'
            """);
    // Should recover and parse scenario
    assertNotNull(scenario);
}

@Test
void testUnclosedDocString() {
    feature("""
            Feature: test
            Scenario: unclosed
              * text =
              \"\"\"
              some content
            Scenario: next
              * print 'ok'
            """);
    // Should recover at next Scenario
    assertEquals(2, feature.getSections().size());
}
```

### 2. Position Accuracy Tests

```java
@Test
void testTokenPositions() {
    String source = """
            Feature: test
            Scenario: first
              * print 'hello'
            """;
    GherkinParser parser = new GherkinParser(Resource.text(source));
    Node ast = parser.parse();

    Node step = ast.findFirstChild(NodeType.G_STEP);
    Token prefix = step.getFirstToken();

    assertEquals(2, prefix.line);      // 0-indexed
    assertEquals(2, prefix.col);       // After "  "
    assertEquals(38, prefix.pos);      // Character offset
}
```

### 3. AST Structure Tests

```java
@Test
void testAstStructure() {
    Node ast = parse("""
            @tag1
            Feature: name
              description
            Scenario: test
              * def x = 1
            """);

    assertEquals(NodeType.G_FEATURE, ast.type);

    Node tags = ast.findFirstChild(NodeType.G_TAGS);
    assertNotNull(tags);
    assertEquals(1, tags.size());

    Node scenario = ast.findFirstChild(NodeType.G_SCENARIO);
    assertNotNull(scenario);

    Node step = scenario.findFirstChild(NodeType.G_STEP);
    assertNotNull(step);
    assertEquals("def", step.findFirstChild(TokenType.G_KEYWORD).token.text);
}
```

---

## Implementation Order

### Phase 0: Parser Infrastructure (Error Recovery)
- [ ] 0.1: Add `ERROR` to NodeType
- [ ] 0.2: Add `SyntaxError` class and error collection to Parser
- [ ] 0.3: Add `enableErrorRecovery()` method
- [ ] 0.4: Add `recordError()` methods (soft error recording)
- [ ] 0.5: Add `recoverTo()` method
- [ ] 0.6: Add `enterWithRecovery()` method
- [ ] 0.7: Unit tests for error recovery infrastructure

### Phase 1: Gherkin AST
- [ ] 1.1: Add Gherkin NodeTypes
- [ ] 1.2: Rewrite GherkinParser with error recovery
- [ ] 1.3: Unit tests for AST structure
- [ ] 1.4: Error recovery tests for Gherkin

### Phase 2: Domain Classes Redesign
- [ ] 2.1: Create new `Tag` record
- [ ] 2.2: Create new `Table` class with cleaner API
- [ ] 2.3: Create new `Step` class (immutable, with builder)
- [ ] 2.4: Create new `Scenario`, `ScenarioOutline`, `Background` classes
- [ ] 2.5: Create new `Feature` class with AST linkage
- [ ] 2.6: Verify `toKarateJson()` compatibility

### Phase 3: AST to Domain Transformation
- [ ] 3.1: Create `GherkinTransformer`
- [ ] 3.2: Update `Feature.read()` entry point
- [ ] 3.3: Ensure all existing `GherkinParserTest` tests pass
- [ ] 3.4: Add transformer-specific tests

### Phase 4: JavaScript Error Recovery (Parallel Track)
- [ ] 4.1: Apply error recovery to JsParser
- [ ] 4.2: Define JS recovery points
- [ ] 4.3: Tests for incomplete JS expressions

### Phase 5: Cleanup
- [ ] 5.1: Remove old domain classes (if any remain)
- [ ] 5.2: Documentation updates
- [ ] 5.3: Performance benchmarking

### Phase 6: Source Reconstitution
- [ ] 6.1: Add `toSource()` method to Feature
- [ ] 6.2: Add `toSource()` method to Scenario, Step
- [ ] 6.3: Add `toSource()` method to Table with alignment preservation
- [ ] 6.4: Add `toSource(Map<Node, String> replacements)` for modifications
- [ ] 6.5: Round-trip tests (parse → reconstitute → identical)

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

## Files to Modify/Create

| File | Action | Phase |
|------|--------|-------|
| `NodeType.java` | Add ERROR, G_* types | 0, 1 |
| `Parser.java` | Add error recovery infrastructure | 0 |
| `SyntaxError.java` | New file | 0 |
| `GherkinParser.java` | Complete rewrite | 1 |
| `Tag.java` | Rewrite as record | 2 |
| `Table.java` | Rewrite with cleaner API | 2 |
| `Step.java` | Rewrite immutable with builder | 2 |
| `Scenario.java` | Rewrite cleaner | 2 |
| `ScenarioOutline.java` | Rewrite cleaner | 2 |
| `Background.java` | Rewrite cleaner | 2 |
| `Feature.java` | Rewrite with AST linkage | 2 |
| `FeatureSection.java` | Evaluate if needed | 2 |
| `GherkinTransformer.java` | New file | 3 |
| `GherkinParserTest.java` | Add new tests | 1, 3 |
| `ParserTest.java` | Add error recovery tests | 0 |

---

## Naming: SyntaxError vs ParseError

Use `SyntaxError` instead of `ParseError`:
- More user-friendly, aligns with JavaScript terminology
- `ParserException` already exists for fatal errors
- `SyntaxError` is for recoverable errors during lenient parsing

```java
public class SyntaxError {
    public final Token token;
    public final String message;
    public final NodeType expected;

    // Position convenience methods
    public int getLine() { return token.line + 1; }
    public int getColumn() { return token.col + 1; }
    public long getOffset() { return token.pos; }
}
```

---

## Domain Class Redesign

The existing gherkin domain classes (Feature, Scenario, Step, etc.) were copied from Karate v1. We have the freedom to rewrite them from scratch with cleaner code.

### Design Principles

1. **Immutability where practical** - Reduce mutable state
2. **Builder pattern for construction** - Cleaner than setters
3. **Clear separation** - Parse-time data vs runtime data
4. **AST linkage** - Domain objects can reference their AST nodes

### Compatibility Constraint: karate JSON

The `toKarateJson()` structure must remain compatible for reports. We add new fields for source reconstitution.

**Step JSON** (v2 - with new fields for reconstitution):
```
{
  "index": 0,
  "line": 5,
  "col": 4,                                    // NEW: column position (indentation)
  "offset": 156,                               // NEW: character offset in source
  "endLine": 7,
  "endCol": 25,                                // NEW: end column
  "endOffset": 210,                            // NEW: end character offset
  "comments": ["# comment"],
  "prefix": "*",
  "text": "'hello world'",
  "docString": "...",
  "table": [
    {"row": ["name", "age"], "line": 8, "col": 6, "widths": [8, 5]}  // NEW: col, widths
  ],
  "background": false
}
```

**Scenario JSON** (v2):
```
{
  "sectionIndex": 0,
  "exampleIndex": -1,
  "name": "test scenario",
  "description": "...",
  "line": 10,
  "col": 0,                                    // NEW
  "offset": 89,                                // NEW
  "tags": ["@tag1"],
  "exampleData": {"key": "value"}
}
```

**Feature JSON** (v2):
```
{
  "name": "My Feature",
  "description": "...",
  "line": 1,
  "col": 0,                                    // NEW
  "offset": 0,                                 // NEW
  "tags": [
    {"name": "tag1", "line": 1, "col": 0}     // NEW: Tag now has position
  ]
}
```

### New Fields Summary

| Field | Type | Description |
|-------|------|-------------|
| `col` | int | 0-indexed column position (for indentation) |
| `offset` | long | Character offset from start of file |
| `endCol` | int | End column position |
| `endOffset` | long | End character offset |
| `widths` | int[] | Table cell widths for alignment |

These fields enable:
- Perfect source reconstitution
- IDE features (go to definition, find usages)
- Source maps for debugging
- Diff-friendly refactoring

### Proposed New Domain Classes

```java
// Immutable after construction, linked to AST
public class Feature {
    private final Resource resource;
    private final Node ast;           // Link to AST for IDE features
    private final List<Tag> tags;
    private final String name;
    private final String description;
    private final int line;
    private final Background background;
    private final List<FeatureSection> sections;

    // Factory method from AST
    public static Feature fromAst(Resource resource, Node ast) {
        return new GherkinTransformer(resource).transform(ast);
    }

    // Convenience factory
    public static Feature read(Resource resource) {
        GherkinParser parser = new GherkinParser(resource);
        Node ast = parser.parse();
        return fromAst(resource, ast);
    }

    // All getters, no setters
    public List<Tag> getTags() { return tags; }
    public String getName() { return name; }
    // ...
}

// Step with clear separation of parse-time vs runtime
public class Step {
    // Parse-time data (immutable)
    private final int index;
    private final int line;
    private final int endLine;
    private final String prefix;
    private final String keyword;
    private final String text;
    private final String docString;
    private final Table table;
    private final List<String> comments;
    private final Node ast;  // Link to AST

    // Context (set during construction)
    private final Feature feature;
    private final Scenario scenario;  // null for background steps

    // Builder for construction
    public static class Builder {
        // ...
    }

    // karate JSON compatibility
    public Map<String, Object> toKarateJson() {
        Map<String, Object> map = new HashMap<>();
        map.put("index", index);
        map.put("line", line);
        if (endLine != line) map.put("endLine", endLine);
        if (comments != null && !comments.isEmpty()) map.put("comments", comments);
        map.put("prefix", prefix);
        map.put("text", text);
        if (docString != null) map.put("docString", docString);
        if (table != null) map.put("table", table.toKarateJson());
        if (scenario == null) map.put("background", true);
        return map;
    }
}

// Tag as a simple record (Java 16+)
public record Tag(int line, String name, List<String> values) {

    public static Tag parse(int line, String text) {
        // @name or @name=value1,value2
        String clean = text.startsWith("@") ? text.substring(1) : text;
        int eq = clean.indexOf('=');
        if (eq < 0) {
            return new Tag(line, clean, List.of());
        }
        String name = clean.substring(0, eq);
        String[] vals = clean.substring(eq + 1).split(",");
        return new Tag(line, name, List.of(vals));
    }

    @Override
    public String toString() {
        return values.isEmpty() ? "@" + name : "@" + name + "=" + String.join(",", values);
    }
}

// Table with cleaner API
public class Table {
    private final List<List<String>> rows;
    private final List<Integer> lineNumbers;
    private final List<String> headers;
    private final Map<String, Integer> headerIndex;

    public Table(List<List<String>> rows, List<Integer> lineNumbers) {
        this.rows = List.copyOf(rows);  // Immutable copy
        this.lineNumbers = List.copyOf(lineNumbers);
        this.headers = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0));
        this.headerIndex = buildHeaderIndex(headers);
    }

    public List<String> getHeaders() { return headers; }
    public int getRowCount() { return rows.size() - 1; }  // Exclude header
    public String getValue(int row, String column) {
        Integer idx = headerIndex.get(column);
        return idx == null ? null : rows.get(row + 1).get(idx);
    }

    // Iteration over data rows (excludes header)
    public Stream<Map<String, String>> stream() {
        return IntStream.range(1, rows.size())
            .mapToObj(i -> rowToMap(rows.get(i)));
    }
}
```

### AST to Domain: Clean Transformer

```java
public class GherkinTransformer {

    private final Resource resource;
    private Feature feature;  // Set during transform

    public Feature transform(Node ast) {
        // Single pass through AST
        Feature.Builder fb = Feature.builder(resource);

        for (Node child : ast) {
            switch (child.type) {
                case G_TAGS -> fb.tags(transformTags(child));
                case TOKEN -> handleFeatureToken(fb, child);
                case G_NAME_DESC -> {
                    var nd = transformNameDesc(child);
                    fb.name(nd.name()).description(nd.description());
                }
                case G_BACKGROUND -> fb.background(transformBackground(child));
                case G_SCENARIO -> fb.addSection(transformScenario(child));
                case G_SCENARIO_OUTLINE -> fb.addSection(transformOutline(child));
                case ERROR -> {} // Skip errors for runtime
            }
        }

        return feature = fb.build();
    }

    private List<Tag> transformTags(Node node) {
        return node.stream()
            .filter(n -> n.isToken() && n.token.type == G_TAG)
            .map(n -> Tag.parse(n.token.line + 1, n.token.text))
            .toList();
    }

    // Clean record for name/desc pair
    record NameDesc(String name, String description) {}

    private NameDesc transformNameDesc(Node node) {
        var lines = node.stream()
            .filter(n -> n.isToken() && n.token.type == G_DESC)
            .map(n -> n.token.text.trim())
            .filter(s -> !s.isEmpty())
            .toList();

        String name = lines.isEmpty() ? null : lines.get(0);
        String desc = lines.size() <= 1 ? null :
            String.join("\n", lines.subList(1, lines.size()));
        return new NameDesc(name, desc);
    }
}
```

### Migration Path

1. **Phase 2A**: Create new domain classes alongside old ones
2. **Phase 2B**: Update GherkinTransformer to use new classes
3. **Phase 2C**: Update runtime to use new classes
4. **Phase 2D**: Remove old classes

---

## Source Reconstitution

### Goal
Enable perfect round-trip: `source → parse → karate JSON → reconstitute → identical source`

This is important for:
- Code formatting that preserves user preferences
- Diff-friendly automated changes
- IDE refactoring (rename, move)
- Source maps for debugging

### Current Limitations (v1 karate JSON)

The current JSON format loses whitespace information:

| Lost Information | Example |
|------------------|---------|
| Indentation | `    * def x` → only stores `*`, `def x` |
| Column position | Can't tell if step starts at column 2 or 4 |
| Table cell padding | `\| name    \|` → stores `name` |
| Doc string margin | Triple-quote content is normalized |
| Blank lines | Not tracked between elements |
| Trailing whitespace | Stripped |

### Proposed Enhancement: Source Spans

Add source span information to karate JSON that enables reconstitution:

```
// Option A: Store full spans
{
  "step": {
    "index": 0,
    "span": { "start": 156, "end": 178 },  // Character offsets
    "line": 5,
    "col": 4,                               // Column position (indentation)
    "prefix": "*",
    "text": "def x = 1"
  }
}

// Option B: Store indent level only (simpler)
{
  "step": {
    "index": 0,
    "line": 5,
    "indent": 4,                            // Number of spaces/tabs
    "prefix": "*",
    "text": "def x = 1"
  }
}
```

### AST-Based Reconstitution (Better Approach)

With the AST approach, we have **all tokens including whitespace** via the linked list:

```java
// Token has prev/next links that include whitespace tokens
Token prefix = step.getFirstToken();       // The "*"
Token beforePrefix = prefix.prev;          // WS token with "    "
Token afterPrefix = prefix.next;           // WS token with " "

// Reconstitute original text
String original = source.substring(
    step.getFirstToken().pos,
    step.getLastToken().pos + step.getLastToken().text.length()
);
```

### Reconstitution Strategies

**Strategy 1: AST Preservation**
- Keep AST linked to domain objects
- Reconstitute by walking AST tokens
- Pros: Perfect fidelity, simple
- Cons: Memory overhead, can't serialize to JSON

**Strategy 2: Enhanced karate JSON**
- Store `indent` (column) and `span` (offsets)
- Store `blankLinesBefore` count
- Store table cell widths for alignment
- Pros: Serializable, smaller than AST
- Cons: More complex JSON, still lossy for edge cases

**Strategy 3: Hybrid**
- karate JSON for runtime data (results, timing)
- Separate "source map" for formatting data
- Pros: Clean separation, backwards compatible
- Cons: Two files to manage

### v2 Approach: Position Fields are Standard

All domain objects store position info from AST during construction:

```java
public class Step {
    private final int line;
    private final int col;           // For indentation
    private final long offset;       // For source location
    private final int endLine;
    private final int endCol;
    private final long endOffset;
    private final Node ast;          // Optional: for toSource()

    // karate JSON always includes position
    public Map<String, Object> toKarateJson() {
        Map<String, Object> map = new HashMap<>();
        map.put("line", line);
        map.put("col", col);
        map.put("offset", offset);
        map.put("endLine", endLine);
        map.put("endCol", endCol);
        map.put("endOffset", endOffset);
        // ... other fields ...
        return map;
    }

    // Reconstitute from AST (if available)
    public String toSource() {
        if (ast == null) return null;
        return ast.getTextIncludingWhitespace();
    }
}
```

### Table Reconstitution

Tables need special handling for alignment:

```java
public class Table {
    // Store original cell widths for reconstitution
    private final List<List<Integer>> cellWidths;  // Per-row, per-column

    // Or compute from AST
    public List<Integer> getColumnWidths() {
        // Walk G_TABLE_ROW nodes, measure G_TABLE_CELL tokens
    }

    public String toSource() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < rows.size(); row++) {
            sb.append("| ");
            for (int col = 0; col < rows.get(row).size(); col++) {
                String cell = rows.get(row).get(col);
                int width = getColumnWidth(col);
                sb.append(String.format("%-" + width + "s", cell));
                sb.append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
```

### Implementation Note

Position fields (`col`, `offset`, `endCol`, `endOffset`) are populated during Phase 3 (AST to Domain Transformation) when we extract data from AST nodes. The `toSource()` methods are added in Phase 6.

---

## UI Integration (Code Editor)

### Compatibility with RichTextFX

The AST approach is fully compatible with existing RichTextFX-based editors:

1. **Token positioning** - `token.pos` maps directly to `StyleSpans` offsets
2. **AST walking** - Recursive traversal maps tokens to CSS classes
3. **Comments** - `token.getComments()` already supported

### Error Recovery Benefits for Editors

**Before (current):**
```java
try {
    Node node = parser.parse();
    // Apply highlighting
} catch (Exception e) {
    return null;  // No highlighting on parse error
}
```

**After (with error recovery):**
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

### Styling ERROR Nodes

ERROR nodes in AST should get special styling:

```java
private static String nodeTypeToClassName(NodeType type) {
    return switch (type) {
        case ERROR -> "syntax-error";  // Red underline or background
        // ... other cases
    };
}
```

### CSS Classes

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

### Code Completion Context

With AST, can determine context for code completion:

```java
public CompletionContext getContextAtPosition(int offset) {
    Node nodeAtPos = ast.findNodeAt(offset);

    if (nodeAtPos == null) return CompletionContext.UNKNOWN;

    return switch (nodeAtPos.type) {
        case G_STEP -> CompletionContext.STEP_KEYWORD;
        case G_STEP_LINE -> CompletionContext.EXPRESSION;
        case G_TAGS -> CompletionContext.TAG;
        case ERROR -> {
            // Look at parent to determine expected context
            Node parent = nodeAtPos.getParent();
            yield contextFromParent(parent);
        }
        default -> CompletionContext.UNKNOWN;
    };
}
```

### Node.findNodeAt() Method

Add to `Node.java` for editor support:

```java
public Node findNodeAt(long offset) {
    // Binary search through children by position
    for (Node child : this) {
        Token first = child.getFirstToken();
        Token last = child.getLastToken();
        if (first != null && last != null) {
            long start = first.pos;
            long end = last.pos + last.text.length();
            if (offset >= start && offset <= end) {
                // Recurse to find most specific node
                Node deeper = child.findNodeAt(offset);
                return deeper != null ? deeper : child;
            }
        }
    }
    return null;
}
```

### Implementation Priority

For editor support, implement in this order:

1. **Phase 0**: Error recovery (enables highlighting during typing)
2. **Phase 1**: Gherkin AST (enables Gherkin syntax coloring)
3. **Add**: `Node.findNodeAt()` method
4. **Add**: `SyntaxError` list accessible from parser

---

## Open Questions

1. **Error limit**: Cap at 100 errors (beyond that, file is severely malformed)
2. **Error severity**: Just errors for now; warnings can be a linting concern
3. **Incremental parsing**: Design hooks now, implement later

---

## References

- IntelliJ Platform SDK: [PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- Tree-sitter: [Error Recovery](https://tree-sitter.github.io/tree-sitter/creating-parsers#error-recovery)
- Old parser: `/Users/peter/dev/zcode/karate/karate-core/src/main/java/com/intuit/karate/core/FeatureParser.java`
- JsParser: `karate-js/src/main/java/io/karatelabs/js/JsParser.java`
- Lexer: `karate-js/src/main/jflex/js.flex`
