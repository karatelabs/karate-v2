package io.karatelabs.js;

import io.karatelabs.common.Resource;
import io.karatelabs.common.StringUtils;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Tag;

import static io.karatelabs.js.TokenType.*;

public class GherkinParser extends Parser {

    public GherkinParser(Resource resource) {
        super(resource, true);
    }

    public Feature parse() {
        Feature feature = new Feature(resource);
        while (peekIf(G_TAG)) {
            Token tag = next();
            feature.addTag(new Tag(tag.line, tag.text));
        }
        if (next().type != G_FEATURE) {
            error(G_FEATURE);
        }
        boolean firstLine = true;
        StringBuilder description = new StringBuilder();
        while (peekIf(G_DESC)) {
            Token desc = next();
            if (firstLine) {
                feature.setName(StringUtils.trimToNull(desc.text));
                firstLine = false;
            } else {
                if (!description.isEmpty()) {
                    description.append('\n');
                }
                description.append(desc.text);
            }
        }
        if (!description.isEmpty()) {
            feature.setDescription(description.toString());
        }
        return feature;
    }

}
