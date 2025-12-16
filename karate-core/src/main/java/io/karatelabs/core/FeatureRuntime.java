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

        // Notify listeners of feature start
        if (suite != null) {
            for (ResultListener listener : suite.getResultListeners()) {
                listener.onFeatureStart(feature);
            }
        }

        try {
            beforeFeature();

            for (Scenario scenario : selectedScenarios()) {
                // Notify listeners of scenario start
                if (suite != null) {
                    for (ResultListener listener : suite.getResultListeners()) {
                        listener.onScenarioStart(scenario);
                    }
                }

                ScenarioRuntime sr = new ScenarioRuntime(this, scenario);
                ScenarioResult scenarioResult = sr.call();
                result.addScenarioResult(scenarioResult);
                lastExecuted = sr;

                // Notify listeners of scenario completion
                if (suite != null) {
                    for (ResultListener listener : suite.getResultListeners()) {
                        listener.onScenarioEnd(scenarioResult);
                    }
                }
            }

            afterFeature();
        } finally {
            result.setEndTime(System.currentTimeMillis());

            // Notify listeners of feature end
            if (suite != null) {
                for (ResultListener listener : suite.getResultListeners()) {
                    listener.onFeatureEnd(result);
                }
            }
        }

        return result;
    }

    private void beforeFeature() {
        if (suite != null) {
            for (RuntimeHook hook : suite.getHooks()) {
                if (!hook.beforeFeature(this)) {
                    // Hook returned false - skip this feature
                    return;
                }
            }
        }
    }

    private void afterFeature() {
        if (suite != null) {
            for (RuntimeHook hook : suite.getHooks()) {
                hook.afterFeature(this);
            }
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

        // For dynamic scenarios
        private List<?> dynamicData = null;
        private Scenario dynamicTemplateScenario = null;

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

            // Continue processing dynamic data if available
            if (dynamicData != null && dynamicTemplateScenario != null) {
                if (processDynamicData()) {
                    return;
                }
            }

            while (sectionIndex < sections.size()) {
                FeatureSection section = sections.get(sectionIndex);

                if (section.isOutline()) {
                    ScenarioOutline outline = section.getScenarioOutline();
                    List<ExamplesTable> tables = outline.getExamplesTables();

                    while (exampleTableIndex < tables.size()) {
                        ExamplesTable table = tables.get(exampleTableIndex);

                        // Check if this is a dynamic Examples table
                        if (table.getTable().isDynamic()) {
                            // Create template scenario for dynamic expansion
                            Scenario templateScenario = outline.toScenario(
                                    table.getTable().getDynamicExpression(),
                                    -1,
                                    table.getLine(),
                                    table.getTags()
                            );

                            // Evaluate the dynamic expression
                            List<?> data = evaluateDynamicExpression(templateScenario);
                            if (data != null && !data.isEmpty()) {
                                dynamicData = data;
                                dynamicTemplateScenario = templateScenario;
                                exampleRowIndex = 0;

                                if (processDynamicData()) {
                                    exampleTableIndex++;
                                    return;
                                }
                            }

                            // Move to next examples table
                            exampleTableIndex++;
                            exampleRowIndex = 0;
                            dynamicData = null;
                            dynamicTemplateScenario = null;
                            continue;
                        }

                        int rowCount = table.getTable().getRows().size() - 1; // exclude header row

                        if (exampleRowIndex < rowCount) {
                            // Use getExampleData which handles type hints (columns ending with !)
                            Map<String, Object> exampleData = table.getTable().getExampleData(exampleRowIndex);
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

                            // Substitute placeholders in steps
                            for (String key : exampleData.keySet()) {
                                Object value = exampleData.get(key);
                                scenario.replace("<" + key + ">", value != null ? value.toString() : null);
                            }

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

                    // Skip @setup scenarios - they're only run via karate.setup()
                    if (scenario.isSetup()) {
                        continue;
                    }

                    if (shouldSelect(scenario)) {
                        nextScenario = scenario;
                        return;
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private boolean processDynamicData() {
            while (exampleRowIndex < dynamicData.size()) {
                Object item = dynamicData.get(exampleRowIndex);
                int rowIndex = exampleRowIndex;
                exampleRowIndex++;

                if (item instanceof Map) {
                    Map<String, Object> exampleData = (Map<String, Object>) item;

                    // Create a copy of the template scenario for this row
                    Scenario scenario = dynamicTemplateScenario.copy(rowIndex);
                    scenario.setExampleData(exampleData);

                    // Substitute placeholders in steps
                    for (String key : exampleData.keySet()) {
                        Object value = exampleData.get(key);
                        scenario.replace("<" + key + ">", value != null ? value.toString() : null);
                    }

                    if (shouldSelect(scenario)) {
                        nextScenario = scenario;
                        return true;
                    }
                }
                // Skip non-map items
            }

            // Done with this dynamic data
            dynamicData = null;
            dynamicTemplateScenario = null;
            return false;
        }

        @SuppressWarnings("unchecked")
        private List<?> evaluateDynamicExpression(Scenario templateScenario) {
            try {
                // Create a temporary ScenarioRuntime to evaluate the expression
                ScenarioRuntime sr = new ScenarioRuntime(FeatureRuntime.this, templateScenario);
                sr.setSkipBackground(true);

                // Evaluate the dynamic expression in this runtime's engine
                String expression = templateScenario.getDynamicExpression();
                Object result = sr.eval(expression);

                if (result instanceof List) {
                    return (List<?>) result;
                } else {
                    // Expression didn't return a list - error
                    throw new RuntimeException("Dynamic expression must return a list: " + expression + ", got: " + result);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to evaluate dynamic expression: " + templateScenario.getDynamicExpression(), e);
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
