package io.karatelabs.js;

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
    void testSimple() {
        feature("""
                Feature:
                """, "Feature:");
        feature("""
                Feature: description
                """, "['Feature: ', 'description']");
        feature("""
                Feature: feature description
                Scenario: scenario description
                """, "['Feature: ', 'feature description', ['Scenario: ', 'scenario description',[]]]");
        feature("""
                Feature: f
                Scenario: s
                  * print 'hello'
                  # just a comment
                  * print 'world'
                """, "['Feature: ', 'f', ['Scenario: ', 's',[['* ', \"print 'hello'\"], ['* ', \"print 'world'\"]]]]");
    }


}
