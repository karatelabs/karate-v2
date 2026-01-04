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
package io.karatelabs.gatling;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.karatelabs.gatling.KarateDsl.*;

/**
 * Comprehensive Gatling simulation for testing karate-gatling integration.
 * <p>
 * This simulation tests:
 * - Basic feature execution with karateFeature()
 * - Session variable injection with karateSet()
 * - Feature chaining (passing data between features)
 * - Silent mode for warm-up scenarios
 * - Feeder integration
 * - URI pattern matching and pauses
 */
public class GatlingSimulation extends Simulation {

    // Start mock server before simulation runs
    static {
        CatsMockServer.start();
    }

    // Protocol with URI pattern configuration
    KarateProtocolBuilder protocol = karateProtocol(
            uri("/cats/{id}").nil(),
            uri("/cats").pauseFor(method("get", 5), method("post", 10)).build()
    ).nameResolver((req, vars) -> {
        // Custom name resolver for better Gatling reports
        String path = req.getPath();
        String method = req.getMethod();
        return method + " " + path;
    });

    // Feeder for data-driven tests
    Iterator<Map<String, Object>> catFeeder = Stream.iterate(0, i -> i + 1)
            .map(i -> Map.<String, Object>of(
                    "name", "Cat" + i,
                    "age", (i % 10) + 1
            ))
            .iterator();

    // Scenario 1: Basic CRUD operations
    ScenarioBuilder crudScenario = scenario("CRUD Operations")
            .exec(karateFeature("classpath:features/cats-crud.feature"));

    // Scenario 2: Chained features with feeder data
    ScenarioBuilder chainedScenario = scenario("Chained Operations")
            .feed(catFeeder)
            .exec(karateSet("name", s -> s.getString("name")))
            .exec(karateSet("age", s -> s.getInt("age")))
            .exec(karateFeature("classpath:features/cats-create.feature"))
            .exec(karateFeature("classpath:features/cats-read.feature"));

    // Scenario 3: Silent warm-up (not reported to Gatling stats)
    ScenarioBuilder warmupScenario = scenario("Warm-up")
            .exec(karateFeature("classpath:features/cats-crud.feature").silent());

    {
        setUp(
                // Run warm-up first (silent, not in stats)
                warmupScenario.injectOpen(atOnceUsers(1)),
                // Then run actual load test scenarios
                crudScenario.injectOpen(
                        nothingFor(1),  // Wait for warm-up
                        rampUsers(3).during(3)
                ),
                chainedScenario.injectOpen(
                        nothingFor(1),  // Wait for warm-up
                        rampUsers(2).during(3)
                )
        ).protocols(protocol);
    }

}
