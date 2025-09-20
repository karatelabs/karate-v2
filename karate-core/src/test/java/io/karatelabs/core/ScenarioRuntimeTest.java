package io.karatelabs.core;

import io.karatelabs.common.Resource;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.io.http.HttpServer;
import org.junit.jupiter.api.Test;

class ScenarioRuntimeTest {

    @Test
    void testScenario() {
        HttpServer server = HttpServer.start(9000);
        Resource root = Resource.path("src/test/resources/feature");
        KarateJs karate = new KarateJs(root);
        Feature feature = Feature.read(root.resolve("http-simple.feature"));
        Scenario scenario = feature.getSections().getFirst().getScenario();
        ScenarioRuntime runtime = new ScenarioRuntime(karate, scenario);
        runtime.run();
    }

}
