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

import io.karatelabs.common.Pair;
import io.karatelabs.common.Resource;
import io.karatelabs.common.StringUtils;
import io.karatelabs.parser.*;

import java.util.ArrayList;
import java.util.List;

import static io.karatelabs.parser.TokenType.*;

public class GherkinParser extends BaseParser {

    private Node ast;

    public GherkinParser(Resource resource) {
        super(resource, getTokens(resource), false);
    }

    public GherkinParser(Resource resource, boolean errorRecovery) {
        super(resource, getTokens(resource), errorRecovery);
    }

    public static List<Token> getTokens(Resource resource) {
        return BaseLexer.tokenize(new GherkinLexer(resource));
    }

    /**
     * @return the AST root node (G_FEATURE) for IDE features
     */
    public Node getAst() {
        return ast;
    }

    public Feature parse() {
        ast = parseAst();
        return transformToFeature(ast);
    }

    // ========== AST Building ==========

    private Node parseAst() {
        enter(NodeType.G_FEATURE);
        tags();
        if (!consumeIf(G_FEATURE)) {
            error(G_FEATURE);
            // Try to recover - look for any section start
            recoverTo(G_SCENARIO, G_SCENARIO_OUTLINE, G_BACKGROUND, EOF);
        }
        nameDesc();
        background();
        while (peek() != EOF) {
            if (!scenarioOrOutline()) {
                // Unknown token - create ERROR and skip to next section
                enter(NodeType.ERROR);
                recoverTo(G_TAG, G_SCENARIO, G_SCENARIO_OUTLINE, G_PREFIX, EOF);
                exit();
            }
        }
        consume(EOF);
        exit();
        return markerNode().getFirst();
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

    private boolean nameDesc() {
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
        nameDesc();
        while (step()) {
            // Collect steps
        }
        return exit();
    }

    private boolean scenarioOrOutline() {
        // Check for tags before scenario/outline
        if (peekIf(G_TAG)) {
            tags();
        }
        if (peekIf(G_SCENARIO)) {
            return scenario();
        } else if (peekIf(G_SCENARIO_OUTLINE)) {
            return scenarioOutline();
        }
        return false;
    }

    private boolean scenario() {
        if (!enter(NodeType.G_SCENARIO, G_SCENARIO)) {
            return false;
        }
        nameDesc();
        while (step()) {
            // Collect steps
        }
        return exit();
    }

    private boolean scenarioOutline() {
        if (!enter(NodeType.G_SCENARIO_OUTLINE, G_SCENARIO_OUTLINE)) {
            return false;
        }
        nameDesc();
        while (step()) {
            // Collect steps
        }
        while (examples()) {
            // Collect examples tables
        }
        return exit();
    }

    private boolean examples() {
        // Check for tags before examples
        if (peekIf(G_TAG)) {
            tags();
        }
        if (!enter(NodeType.G_EXAMPLES, G_EXAMPLES)) {
            return false;
        }
        nameDesc();
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
        stepLine();
        // Optional docstring or table
        docString();
        table();
        return exit();
    }

    private boolean stepLine() {
        // Step line can contain: G_KEYWORD (match operators), EQ, IDENT, DOT, G_EXPR (expression content)
        if (!peekAnyOf(G_KEYWORD, EQ, IDENT, DOT, G_EXPR)) {
            return false;
        }
        enter(NodeType.G_STEP_LINE);
        while (peekAnyOf(G_KEYWORD, EQ, IDENT, DOT, G_EXPR)) {
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
            consumeNext(); // G_EXPR tokens
        }
        if (!consumeIf(G_TRIPLE_QUOTE)) {
            error(G_TRIPLE_QUOTE); // Unclosed docstring
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
        if (!peekIf(G_PIPE)) {
            return false;
        }
        int rowLine = peekToken().line;
        enter(NodeType.G_TABLE_ROW);
        consumeNext(); // Consume the first pipe
        while (true) {
            if (consumeIf(G_TABLE_CELL)) {
                if (!consumeIf(G_PIPE)) {
                    // End of row or error
                    break;
                }
            } else if (peekIf(G_PIPE)) {
                // Check if this pipe is on the same line (empty cell) or new line (next row)
                if (peekToken().line != rowLine) {
                    break; // This pipe belongs to the next row
                }
                consumeNext(); // Empty cell, consume the trailing pipe
            } else {
                break;
            }
        }
        return exit();
    }

    // ========== AST to Domain Transformation ==========

    private Feature transformToFeature(Node ast) {
        Feature feature = new Feature(resource);
        List<Tag> pendingTags = null;

        for (Node child : ast) {
            switch (child.type) {
                case G_TAGS -> pendingTags = transformTags(child);
                case TOKEN -> {
                    if (child.token.type == G_FEATURE) {
                        feature.setLine(child.token.line + 1);
                        if (pendingTags != null) {
                            feature.setTags(pendingTags);
                            pendingTags = null;
                        }
                    }
                }
                case G_NAME_DESC -> {
                    Pair<String> nd = transformNameDesc(child);
                    feature.setName(nd.left);
                    feature.setDescription(nd.right);
                }
                case G_BACKGROUND -> feature.setBackground(transformBackground(feature, child));
                case G_SCENARIO -> {
                    FeatureSection section = transformScenario(feature, child, pendingTags);
                    feature.addSection(section);
                    pendingTags = null;
                }
                case G_SCENARIO_OUTLINE -> {
                    FeatureSection section = transformScenarioOutline(feature, child, pendingTags);
                    feature.addSection(section);
                    pendingTags = null;
                }
                case ERROR -> {
                    // Skip error nodes for runtime
                }
            }
        }
        return feature;
    }

    private List<Tag> transformTags(Node tagsNode) {
        List<Tag> tags = new ArrayList<>();
        for (Node child : tagsNode) {
            if (child.isToken() && child.token.type == G_TAG) {
                tags.add(new Tag(child.token.line + 1, child.token.text));
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
                    if (!desc.isEmpty()) {
                        desc.append('\n');
                    }
                    desc.append(child.token.text);
                }
            }
        }
        String description = desc.isEmpty() ? null : desc.toString();
        return Pair.of(name, description);
    }

    private Background transformBackground(Feature feature, Node node) {
        Background bg = new Background();
        List<Step> steps = new ArrayList<>();

        for (Node child : node) {
            switch (child.type) {
                case TOKEN -> {
                    if (child.token.type == G_BACKGROUND) {
                        bg.setLine(child.token.line + 1);
                    }
                }
                case G_STEP -> steps.add(transformStep(feature, null, steps.size(), child));
            }
        }
        bg.setSteps(steps);
        return bg;
    }

    private FeatureSection transformScenario(Feature feature, Node node, List<Tag> tags) {
        FeatureSection section = new FeatureSection();
        Scenario scenario = new Scenario(feature, section, -1);
        section.setScenario(scenario);
        List<Step> steps = new ArrayList<>();

        for (Node child : node) {
            switch (child.type) {
                case G_TAGS -> {
                    if (tags == null) {
                        tags = transformTags(child);
                    } else {
                        tags.addAll(transformTags(child));
                    }
                }
                case TOKEN -> {
                    if (child.token.type == G_SCENARIO) {
                        scenario.setLine(child.token.line + 1);
                    }
                }
                case G_NAME_DESC -> {
                    Pair<String> nd = transformNameDesc(child);
                    scenario.setName(nd.left);
                    scenario.setDescription(nd.right);
                }
                case G_STEP -> steps.add(transformStep(feature, scenario, steps.size(), child));
            }
        }
        scenario.setTags(tags);
        scenario.setSteps(steps);
        return section;
    }

    private FeatureSection transformScenarioOutline(Feature feature, Node node, List<Tag> tags) {
        FeatureSection section = new FeatureSection();
        ScenarioOutline outline = new ScenarioOutline(feature, section);
        section.setScenarioOutline(outline);
        List<Step> steps = new ArrayList<>();
        List<ExamplesTable> examplesTables = new ArrayList<>();

        for (Node child : node) {
            switch (child.type) {
                case G_TAGS -> {
                    if (tags == null) {
                        tags = transformTags(child);
                    } else {
                        tags.addAll(transformTags(child));
                    }
                }
                case TOKEN -> {
                    if (child.token.type == G_SCENARIO_OUTLINE) {
                        outline.setLine(child.token.line + 1);
                    }
                }
                case G_NAME_DESC -> {
                    Pair<String> nd = transformNameDesc(child);
                    outline.setName(nd.left);
                    outline.setDescription(nd.right);
                }
                case G_STEP -> steps.add(transformStep(feature, null, steps.size(), child));
                case G_EXAMPLES -> examplesTables.add(transformExamples(child));
            }
        }
        outline.setTags(tags);
        outline.setSteps(steps);
        outline.setExamplesTables(examplesTables);
        return section;
    }

    private ExamplesTable transformExamples(Node node) {
        ExamplesTable examples = new ExamplesTable();
        List<Tag> tags = null;

        for (Node child : node) {
            switch (child.type) {
                case G_TAGS -> tags = transformTags(child);
                case TOKEN -> {
                    if (child.token.type == G_EXAMPLES) {
                        examples.setLine(child.token.line + 1);
                    }
                }
                case G_NAME_DESC -> {
                    Pair<String> nd = transformNameDesc(child);
                    examples.setName(nd.left);
                }
                case G_TABLE -> examples.setTable(transformTable(child));
            }
        }
        examples.setTags(tags);
        return examples;
    }

    private Step transformStep(Feature feature, Scenario scenario, int index, Node node) {
        Step step = scenario != null ? new Step(scenario, index) : new Step(feature, index);
        Token lastToken = null;

        for (Node child : node) {
            if (child.isToken()) {
                lastToken = child.token;
                switch (child.token.type) {
                    case G_PREFIX -> {
                        step.setPrefix(child.token.text.trim());
                        step.setLine(child.token.line + 1);
                        // Extract comments from preceding lines (for assertion labels)
                        List<Token> tokenComments = child.token.getComments();
                        if (!tokenComments.isEmpty()) {
                            List<String> comments = new ArrayList<>();
                            for (Token t : tokenComments) {
                                comments.add(t.text.trim());
                            }
                            step.setComments(comments);
                        }
                    }
                    case G_KEYWORD -> step.setKeyword(child.token.text);
                }
            } else {
                switch (child.type) {
                    case G_STEP_LINE -> {
                        String text = extractStepText(child);
                        step.setText(text);
                        lastToken = child.getLast().token;
                    }
                    case G_DOC_STRING -> step.setDocString(extractDocString(child));
                    case G_TABLE -> step.setTable(transformTable(child));
                }
            }
        }

        // Set end line
        if (lastToken != null) {
            step.setEndLine(lastToken.line + 1);
        } else {
            step.setEndLine(step.getLine());
        }

        return step;
    }

    private String extractStepText(Node stepLineNode) {
        if (stepLineNode.isEmpty()) {
            return null;
        }
        Token first = stepLineNode.getFirst().token;
        Token last = stepLineNode.getLast().token;
        int start = (int) first.pos;
        int end = (int) last.pos + last.text.length();
        return resource.getText().substring(start, end);
    }

    private String extractDocString(Node docStringNode) {
        // Find opening and closing triple quotes to get raw text positions
        Token openQuote = null;
        Token closeQuote = null;
        for (Node child : docStringNode) {
            if (child.isToken() && child.token.type == G_TRIPLE_QUOTE) {
                if (openQuote == null) {
                    openQuote = child.token;
                } else {
                    closeQuote = child.token;
                }
            }
        }
        if (openQuote == null || closeQuote == null) {
            return null;
        }
        // Extract raw text between the quotes (positions in source)
        int start = (int) openQuote.pos + openQuote.text.length();
        int end = (int) closeQuote.pos;
        String rawContent = resource.getText().substring(start, end).replace("\r", "");
        // Split into lines
        String[] linesArray = rawContent.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (String line : linesArray) {
            // Skip empty lines (V1 behavior)
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        // V1 algorithm: margin is set from first non-empty line's indentation
        int marginPos = indexOfFirstText(lines.get(0));
        // Build result, stripping margin from lines that have enough indentation
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int firstTextPos = indexOfFirstText(line);
            // Only strip if this line has at least marginPos characters AND
            // the first text is at or after marginPos
            if (marginPos < line.length() && marginPos <= firstTextPos) {
                line = line.substring(marginPos);
            }
            if (i < lines.size() - 1) {
                sb.append(line).append('\n');
            } else {
                sb.append(line);
            }
        }
        return sb.toString().trim();
    }

    private static int indexOfFirstText(String s) {
        int pos = 0;
        for (char c : s.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                return pos;
            }
            pos++;
        }
        return 0; // defensive coding for all-whitespace strings
    }

    private Table transformTable(Node tableNode) {
        List<List<String>> rows = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();

        for (Node rowNode : tableNode) {
            if (rowNode.type == NodeType.G_TABLE_ROW) {
                List<String> cells = new ArrayList<>();
                int line = 0;
                boolean expectingCell = false; // Track if we're expecting a cell after a pipe

                for (Node cellNode : rowNode) {
                    if (cellNode.isToken()) {
                        if (cellNode.token.type == G_PIPE) {
                            if (line == 0) {
                                line = cellNode.token.line + 1;
                            }
                            if (expectingCell) {
                                // Consecutive pipes mean empty cell
                                cells.add("");
                            }
                            expectingCell = true;
                        } else if (cellNode.token.type == G_TABLE_CELL) {
                            cells.add(cellNode.token.text.trim());
                            expectingCell = false;
                        }
                    }
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                    lineNumbers.add(line);
                }
            }
        }

        return rows.isEmpty() ? null : new Table(rows, lineNumbers);
    }

    // ========== Static Match Expression Parser ==========

    /**
     * Parses a match expression string into a MatchExpression object.
     * Uses token positions to preserve exact spacing in expressions like json['key'].
     * The LHS is evaluated as JS by the step executor.
     *
     * @param expression e.g., "foo == bar" or "each item contains { a: 1 }"
     * @return parsed MatchExpression
     * @throws RuntimeException if the expression cannot be parsed
     */
    public static MatchExpression parseMatchExpression(String expression) {
        // Create synthetic step to leverage existing lexer states
        String stepText = "* match " + expression;
        Resource resource = Resource.text(stepText);
        String text = resource.getText();

        // Tokenize with Gherkin mode
        List<Token> tokens = getTokens(resource);

        boolean each = false;
        String operator = null;

        // Track positions for extracting actual and expected expressions
        int actualStart = -1;
        int actualEnd = -1;
        int expectedStart = -1;
        int expectedEnd = -1;

        for (Token token : tokens) {
            // Skip whitespace, step prefix, and EOF
            if (token.type == WS || token.type == WS_LF ||
                token.type == G_PREFIX || token.type == EOF) {
                continue;
            }

            // Handle "match" keyword (skip it)
            if (token.type == G_KEYWORD && "match".equals(token.text)) {
                continue;
            }

            // Handle "each" modifier
            if (token.type == G_KEYWORD && "each".equals(token.text)) {
                each = true;
                continue;
            }

            // Handle match operators
            if (token.type == G_KEYWORD && isMatchOperator(token.text)) {
                operator = token.text;
                continue;
            }

            // Track positions for actual/expected expressions
            int tokenStart = (int) token.pos;
            int tokenEnd = tokenStart + token.text.length();

            if (operator == null) {
                // Before operator - this is part of the actual expression
                if (actualStart < 0) {
                    actualStart = tokenStart;
                }
                actualEnd = tokenEnd;
            } else {
                // After operator - this is part of the expected expression
                if (expectedStart < 0) {
                    expectedStart = tokenStart;
                }
                expectedEnd = tokenEnd;
            }
        }

        if (operator == null) {
            throw new RuntimeException("Invalid match expression, no operator found: " + expression);
        }

        String actualExpr = actualStart >= 0 ? text.substring(actualStart, actualEnd).trim() : "";
        String expectedExpr = expectedStart >= 0 ? text.substring(expectedStart, expectedEnd).trim() : "";

        return new MatchExpression(each, actualExpr, operator, expectedExpr);
    }

    private static boolean isMatchOperator(String text) {
        return text.equals("==") || text.equals("!=") ||
               text.startsWith("contains") || text.equals("!contains") ||
               text.equals("within") || text.equals("!within");
    }

}
