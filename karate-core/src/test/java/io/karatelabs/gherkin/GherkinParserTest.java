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

    private void parse(String text) {
        Resource resource = Resource.text(text);
        GherkinParser parser = new GherkinParser(resource);
        feature = parser.parse();
    }

    @Test
    void testFeatureBasics() {
        parse("""
                Feature:
                """);
        assertNull(feature.getName());

        parse("""
                Feature: foo
                """);
        assertEquals("foo", feature.getName());

        parse("""
                Feature: foo
                    bar
                """);
        assertEquals("foo", feature.getName());
        assertEquals("    bar", feature.getDescription());
        assertTrue(feature.getTags().isEmpty());

        parse("""
                @tag1 @tag2
                Feature: foo
                """);
        List<Tag> tags = feature.getTags();
        assertEquals(2, tags.size());
        assertEquals("tag1", tags.get(0).getName());
        assertEquals("tag2", tags.get(1).getName());
    }


}
