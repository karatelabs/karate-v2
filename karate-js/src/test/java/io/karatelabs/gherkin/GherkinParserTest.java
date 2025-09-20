package io.karatelabs.gherkin;

import io.karatelabs.common.Resource;
import io.karatelabs.js.GherkinParser;
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

}
