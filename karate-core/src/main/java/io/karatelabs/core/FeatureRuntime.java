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
import io.karatelabs.js.JsCallable;
import io.karatelabs.output.LogContext;
import io.karatelabs.output.ResultListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class FeatureRuntime implements Callable<FeatureResult> {

    private static final Logger logger = LogContext.RUNTIME_LOGGER;

    private final Suite suite;
    private final Feature feature;
    private final FeatureRuntime caller;
    private final ScenarioRuntime callerScenario;  // The calling scenario's runtime (for variable inheritance)
    private final boolean sharedScope;  // true = pass variables by reference, false = pass copies
    private final Map<String, Object> callArg;
    private final String callTagSelector;  // Tag selector for call-by-tag (e.g., "@name=second")

    // Caches (feature-level)
    final Map<String, Object> CALLONCE_CACHE = new ConcurrentHashMap<>();
    final Map<String, Object> SETUPONCE_CACHE = new ConcurrentHashMap<>();
    private final ReentrantLock callOnceLock = new ReentrantLock();

    // State
    private ScenarioRuntime lastExecuted;
    private FeatureResult result;

    public FeatureRuntime(Feature feature) {
        this(null, feature, null, null, false, null);
    }

    public FeatureRuntime(Suite suite, Feature feature) {
        this(suite, feature, null, null, false, null);
    }

    public FeatureRuntime(Suite suite, Feature feature, FeatureRuntime caller, Map<String, Object> callArg) {
        this(suite, feature, caller, null, false, callArg);
    }

    public FeatureRuntime(Suite suite, Feature feature, FeatureRuntime caller, ScenarioRuntime callerScenario, boolean sharedScope, Map<String, Object> callArg) {
        this(suite, feature, caller, callerScenario, sharedScope, callArg, null);
    }

    public FeatureRuntime(Suite suite, Feature feature, FeatureRuntime caller, ScenarioRuntime callerScenario, boolean sharedScope, Map<String, Object> callArg, String callTagSelector) {
        this.suite = suite;
        this.feature = feature;
        this.caller = caller;
        this.callerScenario = callerScenario;
        this.sharedScope = sharedScope;
        this.callArg = callArg;
        this.callTagSelector = callTagSelector;
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
            // Fire FEATURE_ENTER event (RuntimeHookAdapter calls beforeFeature)
            if (suite != null) {
                suite.fireEvent(FeatureRunEvent.enter(this));
            }

            for (Scenario scenario : selectedScenarios()) {
                // Notify listeners of scenario start (only for top-level features)
                if (suite != null && caller == null) {
                    for (ResultListener listener : suite.getResultListeners()) {
                        listener.onScenarioStart(scenario);
                    }
                }

                ScenarioRuntime sr = new ScenarioRuntime(this, scenario);
                ScenarioResult scenarioResult = sr.call();
                result.addScenarioResult(scenarioResult);
                lastExecuted = sr;

                // Notify listeners of scenario completion (only for top-level features)
                if (suite != null && caller == null) {
                    for (ResultListener listener : suite.getResultListeners()) {
                        listener.onScenarioEnd(scenarioResult);
                    }
                }
            }

            // Invoke configured afterFeature hook if present (only for top-level features)
            if (lastExecuted != null && caller == null) {
                invokeAfterFeatureHook(lastExecuted);
            }

            // Fire FEATURE_EXIT event (RuntimeHookAdapter calls afterFeature)
            if (suite != null) {
                suite.fireEvent(FeatureRunEvent.exit(this, result));
            }
        } finally {
            result.setEndTime(System.currentTimeMillis());

            // Notify listeners of feature end (only for top-level features, not nested calls)
            if (suite != null && caller == null) {
                for (ResultListener listener : suite.getResultListeners()) {
                    listener.onFeatureEnd(result);
                }
            }
        }

        return result;
    }

    /**
     * Invokes the configured afterFeature hook if present.
     * Uses the last executed scenario's runtime context.
     */
    private void invokeAfterFeatureHook(ScenarioRuntime sr) {
        KarateConfig config = sr.getConfig();
        Object afterFeature = config.getAfterFeature();
        if (afterFeature instanceof JsCallable callable) {
            try {
                callable.call(null);
            } catch (Exception e) {
                logger.warn("afterFeature hook failed: {}", e.getMessage());
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
                } else if (result instanceof JsCallable) {
                    // Generator function - call repeatedly until null/non-map
                    return evaluateGeneratorFunction(result);
                } else {
                    // Expression didn't return a list or function - error
                    throw new RuntimeException("Dynamic expression must return a list or function: " + expression + ", got: " + (result != null ? result.getClass().getName() : "null"));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to evaluate dynamic expression: " + templateScenario.getDynamicExpression(), e);
            }
        }

        /**
         * Evaluates a generator function by calling it repeatedly with incrementing index
         * until it returns null or a non-Map value.
         */
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> evaluateGeneratorFunction(Object function) {
            List<Map<String, Object>> results = new ArrayList<>();
            JsCallable callable = (JsCallable) function;
            int index = 0;

            while (true) {
                Object rowValue;
                try {
                    // JsCallable.call() works with null context (uses declared context internally)
                    rowValue = callable.call(null, index);
                } catch (Exception e) {
                    logger.warn("Generator function threw exception at index {}: {}", index, e.getMessage());
                    break;
                }

                if (rowValue == null) {
                    // null signals end of iteration
                    break;
                }

                if (rowValue instanceof Map) {
                    results.add((Map<String, Object>) rowValue);
                } else {
                    // Non-map value signals end of iteration
                    logger.debug("Generator function returned non-map at index {}, stopping: {}", index, rowValue);
                    break;
                }

                index++;
            }

            return results;
        }

        private boolean shouldSelect(Scenario scenario) {
            // Apply call-level tag filter if specified (takes precedence)
            // This allows calling specific @ignore scenarios by tag
            if (callTagSelector != null) {
                return matchesCallTag(scenario, callTagSelector);
            }

            // For called features (caller != null), don't filter by @ignore
            // @ignore only excludes scenarios from top-level runner selection
            if (caller != null) {
                return true;
            }

            // Use TagSelector for suite-level filtering
            // This handles @ignore, @setup, @env, and complex expressions like anyOf(), allOf()
            List<Tag> tags = scenario.getTagsEffective();
            TagSelector selector = new TagSelector(tags);
            String karateEnv = suite != null ? suite.getEnv() : null;
            return selector.evaluate(suite != null ? suite.getTagSelector() : null, karateEnv);
        }

        /**
         * Simple tag matching for call-by-tag syntax (e.g., call read('file.feature@tagname')).
         * Supports: @tagname, @name=value, ~@tagname (negation)
         * Does NOT filter by @ignore - allows calling @ignore scenarios explicitly.
         */
        private boolean matchesCallTag(Scenario scenario, String tagSelector) {
            List<Tag> tags = scenario.getTagsEffective();
            if (tags.isEmpty()) {
                return !tagSelector.startsWith("@");
            }

            // Check if it's a negation
            if (tagSelector.startsWith("~")) {
                String required = tagSelector.substring(1);
                for (Tag tag : tags) {
                    if (matchesTag(tag, required)) {
                        return false;
                    }
                }
                return true;
            }

            // Parse the selector (remove leading @)
            String selector = tagSelector.startsWith("@") ? tagSelector.substring(1) : tagSelector;

            // Check if any tag matches
            for (Tag tag : tags) {
                if (matchesTag(tag, selector)) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Check if a tag matches a selector.
         * Selector can be: "tagname" or "name=value"
         */
        private boolean matchesTag(Tag tag, String selector) {
            int eqPos = selector.indexOf('=');
            if (eqPos == -1) {
                // Simple tag match: @tagname
                return tag.getName().equals(selector);
            } else {
                // Value tag match: @name=value
                String selectorName = selector.substring(0, eqPos);
                String selectorValue = selector.substring(eqPos + 1);
                return tag.getName().equals(selectorName) && tag.getValues().contains(selectorValue);
            }
        }
    }

    // ========== Resource Resolution ==========

    public Resource resolve(String path) {
        // V1 compatibility: handle 'this:' prefix for relative paths
        if (path.startsWith("this:")) {
            path = path.substring(5);  // Remove 'this:' prefix
        }
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

    public ScenarioRuntime getCallerScenario() {
        return callerScenario;
    }

    public boolean isSharedScope() {
        return sharedScope;
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

    public ReentrantLock getCallOnceLock() {
        return callOnceLock;
    }

}
