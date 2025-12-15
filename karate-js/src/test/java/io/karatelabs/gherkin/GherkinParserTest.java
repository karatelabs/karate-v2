package io.karatelabs.gherkin;

import io.karatelabs.common.Resource;
import io.karatelabs.js.GherkinParser;
import io.karatelabs.js.Node;
import io.karatelabs.js.NodeType;
import io.karatelabs.js.SyntaxError;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GherkinParserTest {

    static final Logger logger = LoggerFactory.getLogger(GherkinParserTest.class);

    Feature feature;
    Scenario scenario;
    ScenarioOutline outline;

    private void feature(String text) {
        Resource resource = Resource.text(text);
        GherkinParser parser = new GherkinParser(resource);
        feature = parser.parse();
        scenario = null;
        if (!feature.getSections().isEmpty()) {
            FeatureSection section = feature.getSections().getFirst();
            scenario = section.getScenario();
            outline = section.getScenarioOutline();
        }
    }

    @Test
    void testFeatureBasics() {
        feature("""
                Feature:
                """);
        assertNull(feature.getName());

        feature("""
                Feature: foo
                """);
        assertEquals("foo", feature.getName());

        feature("""
                Feature: foo
                    bar
                """);
        assertEquals("foo", feature.getName());
        assertEquals("bar", feature.getDescription());
        assertTrue(feature.getTags().isEmpty());

        feature("""
                @tag1 @tag2
                Feature: foo
                """);
        List<Tag> tags = feature.getTags();
        assertEquals(2, tags.size());
        assertEquals("tag1", tags.get(0).getName());
        assertEquals("tag2", tags.get(1).getName());

    }

    @Test
    void testScenarioBasics() {
        feature("""
                Feature:
                Scenario: foo
                  bar
                  * print 'hello world'
                """);
        assertEquals("foo", scenario.getName());
        assertEquals("bar", scenario.getDescription());
        assertEquals(1, scenario.getSteps().size());
        Step step = scenario.getSteps().getFirst();
        assertEquals("*", step.getPrefix());
        assertEquals("print", step.getKeyword());
        assertEquals("'hello world'", step.getText());
    }

    @Test
    void testSimpleHttp() {
        feature("""
                Feature:
                Scenario:
                  * url 'http://httpbin.org/get'
                  * method get
                """);
        Step step1 = scenario.getSteps().getFirst();
        assertEquals("url", step1.getKeyword());
        assertEquals("'http://httpbin.org/get'", step1.getText());
        Step step2 = scenario.getSteps().get(1);
        assertEquals("method", step2.getKeyword());
        assertEquals("get", step2.getText());
    }

    // ========== AST Structure Tests ==========

    private GherkinParser parser;

    private void parseWithAst(String text) {
        parseWithAst(text, false);
    }

    private void parseWithAst(String text, boolean errorRecovery) {
        Resource resource = Resource.text(text);
        parser = new GherkinParser(resource, errorRecovery);
        feature = parser.parse();
        scenario = null;
        outline = null;
        if (!feature.getSections().isEmpty()) {
            FeatureSection section = feature.getSections().getFirst();
            scenario = section.getScenario();
            outline = section.getScenarioOutline();
        }
    }

    @Test
    void testAstStructure() {
        parseWithAst("""
                @tag1
                Feature: name
                  description
                Scenario: test
                  * def x = 1
                """);

        Node ast = parser.getAst();
        assertNotNull(ast);
        assertEquals(NodeType.G_FEATURE, ast.type);

        // Verify tags node exists
        Node tags = ast.findFirstChild(NodeType.G_TAGS);
        assertNotNull(tags);

        // Verify scenario node exists
        Node scenarioNode = ast.findFirstChild(NodeType.G_SCENARIO);
        assertNotNull(scenarioNode);

        // Verify step node exists
        Node step = ast.findFirstChild(NodeType.G_STEP);
        assertNotNull(step);
    }

    @Test
    void testAstAvailable() {
        parseWithAst("""
                Feature: test
                Scenario: first
                  * print 'hello'
                """);

        Node ast = parser.getAst();
        assertNotNull(ast);
        assertEquals(NodeType.G_FEATURE, ast.type);
    }

    // ========== Error Recovery Tests ==========

    @Test
    void testMissingFeatureKeyword() {
        parseWithAst("""
                @tag
                Scenario: orphan
                  * print 'hello'
                """, true);
        // Should recover and parse scenario
        assertNotNull(scenario);
        assertEquals("orphan", scenario.getName());
        assertEquals(1, scenario.getSteps().size());

        // Should have recorded an error
        assertTrue(parser.hasErrors());
        List<SyntaxError> errors = parser.getErrors();
        assertFalse(errors.isEmpty());
    }

    @Test
    void testIncompleteStep() {
        parseWithAst("""
                Feature: test
                Scenario: incomplete
                  * def
                """, true);
        // Should parse without throwing
        assertNotNull(scenario);
        assertEquals(1, scenario.getSteps().size());
        Step step = scenario.getSteps().getFirst();
        assertEquals("def", step.getKeyword());
        // Missing RHS - text should be null
        assertNull(step.getText());
    }

    @Test
    void testMultipleScenarios() {
        parseWithAst("""
                Feature: multi
                Scenario: first
                  * print 'one'
                Scenario: second
                  * print 'two'
                """);
        assertEquals(2, feature.getSections().size());
        assertEquals("first", feature.getSections().get(0).getScenario().getName());
        assertEquals("second", feature.getSections().get(1).getScenario().getName());
    }

    @Test
    void testScenarioWithTags() {
        parseWithAst("""
                Feature: test
                @smoke @regression
                Scenario: tagged
                  * print 'hello'
                """);
        assertNotNull(scenario);
        assertNotNull(scenario.getTags());
        assertEquals(2, scenario.getTags().size());
        assertEquals("smoke", scenario.getTags().get(0).getName());
        assertEquals("regression", scenario.getTags().get(1).getName());
    }

    @Test
    void testBackground() {
        parseWithAst("""
                Feature: with background
                Background:
                  * def base = 'http://localhost'
                Scenario: test
                  * print base
                """);
        assertNotNull(feature.getBackground());
        assertEquals(1, feature.getBackground().getSteps().size());
        Step bgStep = feature.getBackground().getSteps().getFirst();
        assertEquals("def", bgStep.getKeyword());
    }

    @Test
    void testTable() {
        parseWithAst("""
                Feature: test
                Scenario: with table
                  * def data =
                    | name | age |
                    | John | 30  |
                    | Jane | 25  |
                """);
        assertNotNull(scenario);
        assertEquals(1, scenario.getSteps().size());
        Step step = scenario.getSteps().getFirst();
        assertNotNull(step.getTable());
        assertEquals(3, step.getTable().getRows().size()); // header + 2 data rows
    }

    @Test
    void testScenarioOutlineWithExamples() {
        parseWithAst("""
                Feature: outline test
                Scenario Outline: parameterized
                  * print '<name>'
                Examples:
                  | name  |
                  | Alice |
                  | Bob   |
                """);
        assertNotNull(outline);
        assertEquals("parameterized", outline.getName());
        assertEquals(1, outline.getSteps().size());
        assertEquals(1, outline.getExamplesTables().size());
        assertNotNull(outline.getExamplesTables().get(0).getTable());
    }

    @Test
    void testNoErrors() {
        parseWithAst("""
                Feature: valid
                Scenario: test
                  * def x = 1
                  * print x
                """);
        assertFalse(parser.hasErrors());
        assertTrue(parser.getErrors().isEmpty());
    }

}
