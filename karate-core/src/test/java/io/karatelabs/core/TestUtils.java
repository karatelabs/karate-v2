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
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.io.http.HttpClient;
import io.karatelabs.match.Match;
import io.karatelabs.match.Result;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test utilities for runtime tests.
 * Pattern: Create scenarios from step lines and run them with optional mock HTTP.
 */
public class TestUtils {

    /**
     * Run steps with an in-memory HTTP client (no network).
     */
    public static ScenarioRuntime run(String... lines) {
        return run(new InMemoryHttpClient(), lines);
    }

    /**
     * Run steps with a custom HTTP client.
     */
    public static ScenarioRuntime run(HttpClient client, String... lines) {
        Feature feature = toFeature(lines);
        Scenario scenario = feature.getSections().getFirst().getScenario();
        Resource root = Resource.path("src/test/resources");
        KarateJs karate = new KarateJs(root, client);
        ScenarioRuntime sr = new ScenarioRuntime(karate, scenario);
        sr.call();
        return sr;
    }

    /**
     * Create a Feature from step lines.
     * Lines starting with "|" or triple-quote are kept as-is (for tables/docstrings).
     * Other lines are prefixed with "* " automatically.
     */
    public static Feature toFeature(String... lines) {
        StringBuilder sb = new StringBuilder("Feature:\nScenario:\n");
        for (String line : lines) {
            if (line.startsWith("|") || line.startsWith("\"\"\"")) {
                sb.append(line).append('\n');
            } else {
                sb.append("* ").append(line).append('\n');
            }
        }
        return Feature.read(Resource.text(sb.toString()));
    }

    /**
     * Get a variable from the runtime.
     */
    public static Object get(ScenarioRuntime sr, String name) {
        return sr.getVariable(name);
    }

    /**
     * Assert a variable matches an expected value using Karate's match engine.
     */
    public static void matchVar(ScenarioRuntime sr, String name, Object expected) {
        Object actual = sr.getVariable(name);
        Result result = Match.that(actual)._equals(expected);
        if (!result.pass) {
            throw new AssertionError("Variable '" + name + "': " + result.message);
        }
    }

    /**
     * Assert the scenario passed (no failures).
     */
    public static void assertPassed(ScenarioRuntime sr) {
        assertTrue(sr.getResult().isPassed(),
                "Expected pass but: " + sr.getResult().getFailureMessage());
    }

    /**
     * Assert the scenario failed.
     */
    public static void assertFailed(ScenarioRuntime sr) {
        assertTrue(sr.getResult().isFailed(), "Expected failure but scenario passed");
    }

    /**
     * Assert the scenario failed with a message containing the given text.
     */
    public static void assertFailedWith(ScenarioRuntime sr, String messageContains) {
        assertTrue(sr.getResult().isFailed(), "Expected failure but scenario passed");
        String msg = sr.getResult().getFailureMessage();
        assertTrue(msg != null && msg.contains(messageContains),
                "Expected failure message to contain '" + messageContains + "' but was: " + msg);
    }

}
