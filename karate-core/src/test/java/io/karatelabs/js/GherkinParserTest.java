package io.karatelabs.js;

import io.karatelabs.common.Source;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GherkinParserTest {

    static final Logger logger = LoggerFactory.getLogger(GherkinParserTest.class);

    private static void equals(String text, String json, Type type) {
        GherkinParser parser = new GherkinParser(Source.of(text));
        Node node = parser.parse();
        Node child;
        if (type == null) {
            child = node;
        } else {
            Node found = node.findFirst(type);
            child = found.children.get(0);
        }
        NodeUtils.assertEquals(text, child, json);
    }

    private static void feature(String text, String json) {
        equals(text, json, null);
    }

    @Test
    void testFeatureBasics() {
        feature("""
                Feature:
                """, "Feature:");
        feature("""
                Feature: description
                """, "['Feature: ', 'description']");
        feature("""
                Feature: feature description
                Scenario: scenario description
                """, "['Feature: ', 'feature description', ['Scenario: ', 'scenario description', []]]");
        feature("""
                Feature: multi
                    line1
                Scenario: multi
                    line2
                """, "['Feature: ', ['multi', '    line1'], ['Scenario: ', ['multi', '    line2'],[]]]");
        feature("""
                Feature: f
                Scenario: s
                  * print 'hello'
                  # just a comment
                  * print 'world'
                """, "['Feature: ', 'f', ['Scenario: ', 's',[['* ', \"print 'hello'\"], ['* ', \"print 'world'\"]]]]");
        feature("""
                # comment before feature
                Feature:
                # comment after feature
                Scenario:
                # comment after scenario
                """, "['Feature:', ['Scenario:', []]]");
        feature("""
                # comment
                @tag1 @tag2
                Feature:
                """, "[[@tag1, @tag2], 'Feature:']");
        feature("""
                @tag1 @tag2
                # comment
                Feature:
                """, "[[@tag1, @tag2], 'Feature:']");
        feature("""
                Feature:
                @tag1 @tag2
                Scenario:
                """, "['Feature:', [[@tag1, @tag2], 'Scenario:', []]]");
        feature("""
                Feature:
                Background:
                Scenario:
                """, "['Feature:', ['Background:', []], ['Scenario:', []]]");
        feature("""
                Feature:
                Scenario Outline:
                """, "['Feature:', ['Scenario Outline:', [], []]]");
        feature("""
                Feature:
                Scenario Outline:
                Examples:
                """, "['Feature:', ['Scenario Outline:', [], ['Examples:', []]]]");
        feature("""
                Feature:
                Scenario Outline:
                Examples:
                | h1 | h2 |
                | c1 | c2 |
                """, "['Feature:', ['Scenario Outline:', [], ['Examples:', [['|', ' h1 ', '|', ' h2 ', '|'], ['|', ' c1 ', '|', ' c2 ', '|']]]]]");
    }


}
