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
package io.karatelabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KarateConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void testParseMinimalConfig() {
        String json = """
            {
              "paths": ["src/test/features"]
            }
            """;

        KarateConfig config = KarateConfig.parse(json);

        assertEquals(List.of("src/test/features"), config.getPaths());
        assertTrue(config.getTags().isEmpty());
        assertNull(config.getEnv());
        assertEquals(1, config.getThreads());
        assertFalse(config.isDryRun());
        assertFalse(config.isClean());

        // Default output settings
        assertEquals("target/karate-reports", config.getOutput().getDir());
        assertTrue(config.getOutput().isHtml());
        assertFalse(config.getOutput().isJunitXml());
        assertFalse(config.getOutput().isCucumberJson());
        assertFalse(config.getOutput().isNdjson());
    }

    @Test
    void testParseFullConfig() {
        String json = """
            {
              "paths": ["src/test/features/api", "src/test/features/ui"],
              "tags": ["@smoke", "~@slow"],
              "env": "dev",
              "threads": 5,
              "scenarioName": ".*login.*",
              "configDir": "src/test/resources",
              "dryRun": true,
              "clean": true,
              "output": {
                "dir": "target/custom-reports",
                "html": true,
                "junitXml": true,
                "cucumberJson": true,
                "ndjson": true
              }
            }
            """;

        KarateConfig config = KarateConfig.parse(json);

        assertEquals(List.of("src/test/features/api", "src/test/features/ui"), config.getPaths());
        assertEquals(List.of("@smoke", "~@slow"), config.getTags());
        assertEquals("dev", config.getEnv());
        assertEquals(5, config.getThreads());
        assertEquals(".*login.*", config.getScenarioName());
        assertEquals("src/test/resources", config.getConfigDir());
        assertTrue(config.isDryRun());
        assertTrue(config.isClean());

        assertEquals("target/custom-reports", config.getOutput().getDir());
        assertTrue(config.getOutput().isHtml());
        assertTrue(config.getOutput().isJunitXml());
        assertTrue(config.getOutput().isCucumberJson());
        assertTrue(config.getOutput().isNdjson());
    }

    @Test
    void testParsePartialOutputConfig() {
        String json = """
            {
              "paths": ["features"],
              "output": {
                "dir": "reports",
                "junitXml": true
              }
            }
            """;

        KarateConfig config = KarateConfig.parse(json);

        assertEquals("reports", config.getOutput().getDir());
        assertTrue(config.getOutput().isHtml()); // default
        assertTrue(config.getOutput().isJunitXml()); // overridden
        assertFalse(config.getOutput().isCucumberJson()); // default
        assertFalse(config.getOutput().isNdjson()); // default
    }

    @Test
    void testLoadFromFile() throws Exception {
        Path configFile = tempDir.resolve("karate.json");
        Files.writeString(configFile, """
            {
              "paths": ["src/test/features"],
              "env": "test",
              "threads": 3
            }
            """);

        KarateConfig config = KarateConfig.load(configFile);

        assertEquals(List.of("src/test/features"), config.getPaths());
        assertEquals("test", config.getEnv());
        assertEquals(3, config.getThreads());
    }

    @Test
    void testLoadFromFilePath() throws Exception {
        Path configFile = tempDir.resolve("karate.json");
        Files.writeString(configFile, """
            {
              "paths": ["features"],
              "tags": ["@api"]
            }
            """);

        KarateConfig config = KarateConfig.load(configFile.toString());

        assertEquals(List.of("features"), config.getPaths());
        assertEquals(List.of("@api"), config.getTags());
    }

    @Test
    void testLoadFileNotFound() {
        assertThrows(RuntimeException.class, () -> {
            KarateConfig.load("nonexistent.json");
        });
    }

    @Test
    void testLoadInvalidJson() throws Exception {
        Path configFile = tempDir.resolve("invalid.json");
        // An array is valid JSON but not a valid config (must be object)
        Files.writeString(configFile, "[1, 2, 3]");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            KarateConfig.load(configFile);
        });
        // Exception is wrapped, check the cause
        assertTrue(ex.getCause().getMessage().contains("expected JSON object"));
    }

    @Test
    void testParseInvalidJsonDirectly() {
        // Test parse() directly with an array
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            KarateConfig.parse("[1, 2, 3]");
        });
        assertTrue(ex.getMessage().contains("expected JSON object"));
    }

    @Test
    void testApplyToRunner() throws Exception {
        // Create a simple feature for testing
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Test
            Scenario: Test scenario
            * def x = 1
            """);

        String json = """
            {
              "paths": ["%s"],
              "env": "dev",
              "threads": 2,
              "output": {
                "html": false,
                "junitXml": true
              }
            }
            """.formatted(featureFile.toString().replace("\\", "\\\\"));

        KarateConfig config = KarateConfig.parse(json);
        Runner.Builder builder = Runner.builder();
        config.applyTo(builder);

        // Build suite to verify settings were applied
        Suite suite = builder.buildSuite();
        assertNotNull(suite);
        assertEquals("dev", suite.getEnv());
    }

    @Test
    void testEmptyConfig() {
        String json = "{}";

        KarateConfig config = KarateConfig.parse(json);

        assertTrue(config.getPaths().isEmpty());
        assertTrue(config.getTags().isEmpty());
        assertNull(config.getEnv());
        assertEquals(1, config.getThreads());
    }

    @Test
    void testParseWithClasspathPaths() {
        String json = """
            {
              "paths": ["classpath:features/api", "classpath:features/ui"],
              "env": "ci"
            }
            """;

        KarateConfig config = KarateConfig.parse(json);

        assertEquals(List.of("classpath:features/api", "classpath:features/ui"), config.getPaths());
        assertEquals("ci", config.getEnv());
    }

    @Test
    void testParseSingleTag() {
        String json = """
            {
              "paths": ["features"],
              "tags": ["@smoke"]
            }
            """;

        KarateConfig config = KarateConfig.parse(json);

        assertEquals(List.of("@smoke"), config.getTags());
    }

    @Test
    void testSettersAndGetters() {
        KarateConfig config = new KarateConfig();

        config.setPaths(List.of("path1", "path2"));
        config.setTags(List.of("@tag1"));
        config.setEnv("prod");
        config.setThreads(10);
        config.setScenarioName("test.*");
        config.setConfigDir("config");
        config.setDryRun(true);
        config.setClean(true);

        KarateConfig.OutputConfig output = new KarateConfig.OutputConfig();
        output.setDir("output");
        output.setHtml(false);
        output.setJunitXml(true);
        output.setCucumberJson(true);
        output.setNdjson(true);
        config.setOutput(output);

        assertEquals(List.of("path1", "path2"), config.getPaths());
        assertEquals(List.of("@tag1"), config.getTags());
        assertEquals("prod", config.getEnv());
        assertEquals(10, config.getThreads());
        assertEquals("test.*", config.getScenarioName());
        assertEquals("config", config.getConfigDir());
        assertTrue(config.isDryRun());
        assertTrue(config.isClean());

        assertEquals("output", config.getOutput().getDir());
        assertFalse(config.getOutput().isHtml());
        assertTrue(config.getOutput().isJunitXml());
        assertTrue(config.getOutput().isCucumberJson());
        assertTrue(config.getOutput().isNdjson());
    }

    @Test
    void testSetPathsWithNull() {
        KarateConfig config = new KarateConfig();
        config.setPaths(null);
        assertNotNull(config.getPaths());
        assertTrue(config.getPaths().isEmpty());
    }

    @Test
    void testSetTagsWithNull() {
        KarateConfig config = new KarateConfig();
        config.setTags(null);
        assertNotNull(config.getTags());
        assertTrue(config.getTags().isEmpty());
    }

    @Test
    void testSetOutputWithNull() {
        KarateConfig config = new KarateConfig();
        config.setOutput(null);
        assertNotNull(config.getOutput());
        assertEquals("target/karate-reports", config.getOutput().getDir());
    }

    @Test
    void testParseWorkingDir() {
        String json = """
            {
              "paths": ["src/test/features"],
              "workingDir": "/home/user/project"
            }
            """;

        KarateConfig config = KarateConfig.parse(json);

        assertEquals("/home/user/project", config.getWorkingDir());
    }

    @Test
    void testWorkingDirSetterGetter() {
        KarateConfig config = new KarateConfig();
        assertNull(config.getWorkingDir());

        config.setWorkingDir("/path/to/project");
        assertEquals("/path/to/project", config.getWorkingDir());
    }

    @Test
    void testApplyWorkingDirToRunner() throws Exception {
        // Create a simple feature for testing
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Test
            Scenario: Test scenario
            * def x = 1
            """);

        String json = """
            {
              "paths": ["%s"],
              "workingDir": "/custom/workdir"
            }
            """.formatted(featureFile.toString().replace("\\", "\\\\"));

        KarateConfig config = KarateConfig.parse(json);
        Runner.Builder builder = Runner.builder();
        config.applyTo(builder);

        // Build suite to verify workingDir was applied
        Suite suite = builder.buildSuite();
        assertNotNull(suite);
        assertNotNull(suite.getWorkingDir());
        assertTrue(suite.getWorkingDir().toString().contains("workdir") ||
                   suite.getWorkingDir().isAbsolute());
    }

}
