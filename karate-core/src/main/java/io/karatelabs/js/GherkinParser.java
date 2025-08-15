package io.karatelabs.js;

import io.karatelabs.common.Source;
import io.karatelabs.common.StringUtils;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Tag;

public class GherkinParser extends Parser {

    final Source source;

    public GherkinParser(Source source) {
        super(source, true);
        this.source = source;
    }

    public Feature parse() {
        Feature feature = new Feature(source);
        while (peekIf(Token.G_TAG)) {
            Chunk tag = next();
            feature.addTag(new Tag(tag.line, tag.text));
        }
        if (next().token != Token.G_FEATURE) {
            error(Token.G_FEATURE);
        }
        boolean firstLine = true;
        StringBuilder description = new StringBuilder();
        while (peekIf(Token.G_DESC)) {
            Chunk desc = next();
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
