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
import io.karatelabs.gherkin.FeatureSection;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.ScenarioOutline;
import io.karatelabs.gherkin.ExamplesTable;
import io.karatelabs.gherkin.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class FeatureRuntime implements Callable<FeatureResult> {

    private final Suite suite;
    private final Feature feature;
    private final FeatureRuntime caller;
    private final Map<String, Object> callArg;

    // Caches (feature-level)
    final Map<String, Object> CALLONCE_CACHE = new ConcurrentHashMap<>();
    final Map<String, Object> SETUPONCE_CACHE = new ConcurrentHashMap<>();

    // State
    private ScenarioRuntime lastExecuted;
    private FeatureResult result;

    public FeatureRuntime(Feature feature) {
        this(null, feature, null, null);
    }

    public FeatureRuntime(Suite suite, Feature feature) {
        this(suite, feature, null, null);
    }

    public FeatureRuntime(Suite suite, Feature feature, FeatureRuntime caller, Map<String, Object> callArg) {
        this.suite = suite;
        this.feature = feature;
        this.caller = caller;
        this.callArg = callArg;
        this.result = new FeatureResult(feature);
    }

    public static FeatureRuntime of(Feature feature) {
        return new FeatureRuntime(feature);
    }

    public static FeatureRuntime of(Suite suite, Feature feature) {
        return new FeatureRuntime(suite, feature);
    }

    @Override
    public FeatureResult call() {
        result.setStartTime(System.currentTimeMillis());

        try {
            beforeFeature();

            for (Scenario scenario : selectedScenarios()) {
                ScenarioRuntime sr = new ScenarioRuntime(this, scenario);
                ScenarioResult scenarioResult = sr.call();
                result.addScenarioResult(scenarioResult);
                lastExecuted = sr;
            }

            afterFeature();
        } finally {
            result.setEndTime(System.currentTimeMillis());
        }

        return result;
    }

    private void beforeFeature() {
        // Hook for before feature - can be extended for RuntimeHook
        if (suite != null) {
            // Suite-level before feature hooks
        }
    }

    private void afterFeature() {
        // Hook for after feature - can be extended for RuntimeHook
        if (suite != null) {
            // Suite-level after feature hooks
        }
    }

    /**
     * Returns an iterable of scenarios to execute, including expanded outlines.
     * Applies tag filtering if configured.
     */
    private Iterable<Scenario> selectedScenarios() {
        return () -> new ScenarioIterator();
    }

    /**
     * Iterator that expands scenario outlines into individual scenarios.
     */
    private class ScenarioIterator implements Iterator<Scenario> {

        private final List<FeatureSection> sections;
        private int sectionIndex = 0;
        private int exampleTableIndex = 0;
        private int exampleRowIndex = 0;
        private Scenario nextScenario = null;

        ScenarioIterator() {
            this.sections = feature.getSections();
            advance();
        }

        @Override
        public boolean hasNext() {
            return nextScenario != null;
        }

        @Override
        public Scenario next() {
            Scenario current = nextScenario;
            advance();
            return current;
        }

        private void advance() {
            nextScenario = null;

            while (sectionIndex < sections.size()) {
                FeatureSection section = sections.get(sectionIndex);

                if (section.isOutline()) {
                    ScenarioOutline outline = section.getScenarioOutline();
                    List<ExamplesTable> tables = outline.getExamplesTables();

                    while (exampleTableIndex < tables.size()) {
                        ExamplesTable table = tables.get(exampleTableIndex);
                        List<Map<String, String>> rows = table.getTable().getRowsAsMaps();

                        if (exampleRowIndex < rows.size()) {
                            Map<String, String> row = rows.get(exampleRowIndex);
                            // Convert Map<String, String> to Map<String, Object>
                            Map<String, Object> exampleData = new HashMap<>(row);
                            int exampleIndex = exampleRowIndex;
                            exampleRowIndex++;

                            // Create scenario from outline
                            Scenario scenario = outline.toScenario(
                                    null,
                                    exampleIndex,
                                    table.getLine(),
                                    table.getTags()
                            );
                            scenario.setExampleData(exampleData);

                            // Check if scenario should be selected
                            if (shouldSelect(scenario)) {
                                nextScenario = scenario;
                                return;
                            }
                            continue;
                        }

                        // Move to next examples table
                        exampleTableIndex++;
                        exampleRowIndex = 0;
                    }

                    // Move to next section
                    sectionIndex++;
                    exampleTableIndex = 0;
                    exampleRowIndex = 0;

                } else {
                    Scenario scenario = section.getScenario();
                    sectionIndex++;

                    if (shouldSelect(scenario)) {
                        nextScenario = scenario;
                        return;
                    }
                }
            }
        }

        private boolean shouldSelect(Scenario scenario) {
            // Check for @ignore tag
            List<Tag> tags = scenario.getTags();
            if (tags != null) {
                for (Tag tag : tags) {
                    if (Tag.IGNORE.equals(tag.getName())) {
                        return false;
                    }
                }
            }

            // Apply suite tag filter if configured
            if (suite != null && suite.getTagSelector() != null) {
                return matchesTags(scenario, suite.getTagSelector());
            }

            return true;
        }

        private boolean matchesTags(Scenario scenario, String tagSelector) {
            // Simple tag matching - can be expanded for complex expressions
            List<Tag> tags = scenario.getTags();
            if (tags == null || tags.isEmpty()) {
                // Scenario has no tags - check if selector requires tags
                return !tagSelector.startsWith("@");
            }

            // Simple implementation - checks if any tag matches
            for (Tag tag : tags) {
                if (tagSelector.contains(tag.getName())) {
                    return true;
                }
            }

            // Check if it's a negation
            if (tagSelector.startsWith("~")) {
                String required = tagSelector.substring(1);
                for (Tag tag : tags) {
                    if (required.equals(tag.getName())) {
                        return false;
                    }
                }
                return true;
            }

            return false;
        }
    }

    // ========== Resource Resolution ==========

    public Resource resolve(String path) {
        return feature.getResource().resolve(path);
    }

    // ========== Accessors ==========

    public Suite getSuite() {
        return suite;
    }

    public Feature getFeature() {
        return feature;
    }

    public FeatureRuntime getCaller() {
        return caller;
    }

    public Map<String, Object> getCallArg() {
        return callArg;
    }

    public ScenarioRuntime getLastExecuted() {
        return lastExecuted;
    }

    public FeatureResult getResult() {
        return result;
    }

}
