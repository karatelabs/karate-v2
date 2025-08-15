package io.karatelabs.js;

import io.karatelabs.common.Source;

public class GherkinParser extends Parser {

    public GherkinParser(Source source) {
        super(source, true);
    }

    @Override
    public Node parse() {
        enter(Type.G_FEATURE);
        final Node feature = marker.node;
        if (peekIf(Token.G_TAG)) {
            // TODO
        }
        consume(Token.G_FEATURE);
        consumeIf(Token.G_DESC);
        if (peekIf(Token.G_BACKGROUND)) {
            // TODO
        }
        while (true) {
            final Token next = peek();
            if (next == Token.G_SCENARIO) {
                enter(Type.G_SCENARIO);
                consume(Token.G_SCENARIO);
                consumeIf(Token.G_DESC);
                steps();
            } else if (next == Token.G_SCENARIO_OUTLINE) {
                // TODO
            } else {
                break;
            }
        }
        if (peek() != Token.EOF) {
            error("cannot parse feature");
        }
        exit();
        return feature;
    }

    private void steps() {
        enter(Type.G_STEPS);
        while (true) {
            final Token next = peek();
            if (next == Token.G_PREFIX) {
                enter(Type.G_STEP);
                consume(Token.G_PREFIX);
                consume(Token.G_STEP_TEXT);
                exit();
            } else {
                break;
            }
        }
        exit();
    }

}
