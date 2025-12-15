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
package io.karatelabs.js;

import io.karatelabs.common.Pair;
import io.karatelabs.common.Resource;
import io.karatelabs.common.StringUtils;
import io.karatelabs.gherkin.*;

import java.util.ArrayList;
import java.util.List;

import static io.karatelabs.js.TokenType.*;

public class GherkinParser extends Parser {

    private Node ast;

    public GherkinParser(Resource resource) {
        super(resource, true, false);
    }

    public GherkinParser(Resource resource, boolean errorRecovery) {
        super(resource, true, errorRecovery);
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
        if (!peekAnyOf(G_KEYWORD, EQ, G_RHS)) {
            return false;
        }
        enter(NodeType.G_STEP_LINE);
        while (peekAnyOf(G_KEYWORD, EQ, G_RHS)) {
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
            consumeNext(); // G_RHS tokens
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
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (Node child : docStringNode) {
            if (child.isToken()) {
                if (child.token.type == G_TRIPLE_QUOTE) {
                    continue; // Skip the triple quotes
                }
                if (started) {
                    sb.append('\n');
                }
                sb.append(child.token.text);
                started = true;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private Table transformTable(Node tableNode) {
        List<List<String>> rows = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();

        for (Node rowNode : tableNode) {
            if (rowNode.type == NodeType.G_TABLE_ROW) {
                List<String> cells = new ArrayList<>();
                int line = 0;
                for (Node cellNode : rowNode) {
                    if (cellNode.isToken()) {
                        if (cellNode.token.type == G_TABLE_CELL) {
                            cells.add(cellNode.token.text.trim());
                        } else if (cellNode.token.type == G_PIPE && line == 0) {
                            line = cellNode.token.line + 1;
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

}
