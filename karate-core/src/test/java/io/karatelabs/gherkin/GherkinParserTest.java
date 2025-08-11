package io.karatelabs.gherkin;

import io.karatelabs.js.Source;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

class GherkinParserTest {

    static final Logger logger = LoggerFactory.getLogger(GherkinParserTest.class);

    @Test
    void test01() {
        File file = new File("src/test/resources/feature/test-01.feature");
        GherkinParser parser = new GherkinParser(Source.of(file));
    }


}
