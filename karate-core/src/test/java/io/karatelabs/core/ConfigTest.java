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
        Suite suite = Suite.of(tempDir, featureFile.toString())
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
        Suite suite = Suite.of(tempDir, featureFile.toString())
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
        Suite suite = Suite.of(tempDir, featureFile.toString())
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

        Suite suite = Suite.of(tempDir, featureFile.toString())
                .configPath(configFile.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed());
    }

    // ========== Working Directory Fallback Tests (V2 Enhancement) ==========

    @Test
    void testConfigFromWorkingDirectory() throws Exception {
        // Create karate-config.js in the working directory (tempDir)
        // This simulates a user running Karate from a directory without classpath setup
        Path configFile = tempDir.resolve("karate-config.js");
        Files.writeString(configFile, """
            function fn() {
              return {
                fromWorkingDir: true,
                baseUrl: 'http://workingdir.example.com'
              };
            }
            """);

        // Create a feature file
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Working Dir Config Test
            Scenario: Config loaded from working directory
            * match fromWorkingDir == true
            * match baseUrl == 'http://workingdir.example.com'
            """);

        // Run WITHOUT specifying configPath - should use default 'classpath:karate-config.js'
        // which won't be found on classpath, but will be found in working directory
        Suite suite = Suite.of(tempDir, featureFile.toString())
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Config should be loaded from working directory");
    }

    @Test
    void testEnvConfigFromWorkingDirectory() throws Exception {
        // Create base karate-config.js in working directory
        Path configFile = tempDir.resolve("karate-config.js");
        Files.writeString(configFile, """
            function fn() {
              return {
                env: karate.env,
                baseUrl: 'http://default.example.com'
              };
            }
            """);

        // Create env-specific config in working directory
        Path devConfig = tempDir.resolve("karate-config-staging.js");
        Files.writeString(devConfig, """
            function fn() {
              return {
                baseUrl: 'http://staging.example.com'
              };
            }
            """);

        // Create a feature file
        Path featureFile = tempDir.resolve("test.feature");
        Files.writeString(featureFile, """
            Feature: Env Config from Working Dir
            Scenario: Both configs loaded from working directory
            * match env == 'staging'
            * match baseUrl == 'http://staging.example.com'
            """);

        // Run with env=staging, no explicit configPath
        Suite suite = Suite.of(tempDir, featureFile.toString())
                .env("staging")
                .writeReport(false);
        SuiteResult result = suite.run();

        assertTrue(result.isPassed(), "Base and env configs should load from working directory");
    }

}
