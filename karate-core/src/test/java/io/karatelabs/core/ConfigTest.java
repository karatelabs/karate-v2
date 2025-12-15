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

import io.karatelabs.common.Resource;
import io.karatelabs.gherkin.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for karate-config.js loading.
 */
class ConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void testConfigLoadsVariables() throws Exception {
        // Create a karate-config.js
        Path configFile = tempDir.resolve("karate-config.js");
        Files.writeString(configFile, """
            function fn() {
              return {
                baseUrl: 'http://localhost:8080',
                timeout: 5000
              };
            }
            fn();
            """);

        // Create a simple feature file
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Config Test
            Scenario: Use config variables
            * def url = baseUrl
            * match url == 'http://localhost:8080'
            * match timeout == 5000
            """);

        // Run with config
        Suite suite = Suite.of(featureFile.toString())
                .configPath(configFile.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertEquals(1, result.getScenarioCount());
        assertEquals(1, result.getScenarioPassedCount());
        assertEquals(0, result.getScenarioFailedCount());
    }

    @Test
    void testEnvSpecificConfig() throws Exception {
        // Create base config
        Path configFile = tempDir.resolve("karate-config.js");
        Files.writeString(configFile, """
            function fn() {
              return {
                baseUrl: 'http://localhost:8080',
                env: karate.env
              };
            }
            fn();
            """);

        // Create env-specific config
        Path devConfigFile = tempDir.resolve("karate-config-dev.js");
        Files.writeString(devConfigFile, """
            function fn() {
              return {
                baseUrl: 'http://dev.example.com'
              };
            }
            fn();
            """);

        // Create a feature file
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Env Config Test
            Scenario: Check env config
            * match baseUrl == 'http://dev.example.com'
            * match env == 'dev'
            """);

        // Run with env=dev
        Suite suite = Suite.of(featureFile.toString())
                .configPath(configFile.toString())
                .env("dev")
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Suite should pass");
        assertEquals(1, result.getScenarioPassedCount());
    }

    @Test
    void testMissingConfigIsIgnored() throws Exception {
        // Create a feature file that doesn't need config
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: No Config Test
            Scenario: Simple test
            * def a = 1 + 2
            * match a == 3
            """);

        // Run without config file (missing config should be ignored)
        Suite suite = Suite.of(featureFile.toString())
                .configPath(tempDir.resolve("nonexistent-config.js").toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed());
    }

    @Test
    void testConfigVariablesAvailableInFeature() throws Exception {
        // Create config that returns object
        Path configFile = tempDir.resolve("karate-config.js");
        Files.writeString(configFile, """
            ({
              appName: 'TestApp',
              version: '1.0'
            })
            """);

        // Create feature
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Config Variables
            Scenario: Access config vars
            * match appName == 'TestApp'
            * match version == '1.0'
            """);

        Suite suite = Suite.of(featureFile.toString())
                .configPath(configFile.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed());
    }

}
