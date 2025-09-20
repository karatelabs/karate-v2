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
package io.karatelabs.js;

import io.karatelabs.common.Pair;
import io.karatelabs.common.Resource;
import io.karatelabs.common.StringUtils;
import io.karatelabs.gherkin.*;

import java.util.ArrayList;
import java.util.List;

import static io.karatelabs.js.TokenType.*;

public class GherkinParser extends Parser {

    final Feature feature;

    public GherkinParser(Resource resource) {
        super(resource, true);
        feature = new Feature(resource);
    }

    FeatureSection currentSection;
    List<Step> steps;

    public Feature parse() {
        while (peekIf(G_TAG)) {
            Token tag = next();
            feature.addTag(new Tag(tag.line, tag.text));
        }
        if (next().type != G_FEATURE) {
            error(G_FEATURE);
        }
        Pair<String> featureName = nameAndDesc();
        feature.setName(featureName.left);
        feature.setDescription(featureName.right);
        Token next = next();
        while (next.type != EOF) {
            switch (next.type) {
                case G_SCENARIO -> {
                    steps = new ArrayList<>();
                    Pair<String> scenarioName = nameAndDesc();
                    FeatureSection section = new FeatureSection();
                    Scenario scenario = new Scenario(feature, section, -1);
                    section.setScenario(scenario);
                    scenario.setName(scenarioName.left);
                    scenario.setDescription(scenarioName.right);
                    scenario.setSteps(steps);
                    feature.addSection(section);
                    currentSection = section;
                }
                case G_PREFIX -> step(next);
            }
            next = next();
        }
        return feature;
    }

    private void step(Token prefix) {
        Step step = new Step(feature, steps.size());
        step.setPrefix(prefix.text);
        steps.add(step);
        step.setLine(prefix.line);
        Token first = next();
        int start;
        if (first.type == G_KEYWORD) {
            step.setKeyword(first.text);
            start = (int) first.getNextPrimary().pos;
        } else {
            start = (int) first.pos;
        }
        Token last = first;
        while (true) {
            Token next = peekToken();
            if (next.type.oneOf(G_KEYWORD, EQ, G_RHS)) {
                last = next();
            } else {
                break;
            }
        }
        int end = (int) last.pos + last.text.length();
        String text = prefix.resource.getText().substring(start, end);
        step.setText(text);
    }

    private Pair<String> nameAndDesc() {
        boolean firstLine = true;
        StringBuilder description = new StringBuilder();
        String name = null;
        while (peekIf(G_DESC)) {
            Token desc = next();
            if (firstLine) {
                name = StringUtils.trimToNull(desc.text);
                firstLine = false;
            } else {
                if (!description.isEmpty()) {
                    description.append('\n');
                }
                description.append(desc.text);
            }
        }
        String desc = description.isEmpty() ? null : description.toString();
        return Pair.of(name, desc);
    }

}
