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
        tags();
        consume(Token.G_FEATURE);
        desc();
        background();
        while (true) {
            enter(Type.G_SCENARIO);
            tags();
            final Token next = peek();
            if (next == Token.G_SCENARIO) {
                consume(Token.G_SCENARIO);
                desc();
                steps();
            } else if (next == Token.G_SCENARIO_OUTLINE) {
                consume(Token.G_SCENARIO_OUTLINE);
                desc();
                steps();
                examples();
            } else {
                exit(false, false);
                break;
            }
            exit();
        }
        if (peek() != Token.EOF) {
            error("cannot parse feature");
        }
        exit();
        return feature;
    }

    private void tags() {
        enter(Type.G_TAGS);
        boolean present = false;
        while (consumeIf(Token.G_TAG)) {
            present = true;
        }
        exit(present, false);
    }

    private void desc() {
        enter(Type.G_DESC);
        boolean present = false;
        while (consumeIf(Token.G_DESC)) {
            present = true;
        }
        exit(present, false);
    }

    private void background() {
        enter(Type.G_BACKGROUND);
        if (consumeIf(Token.G_BACKGROUND)) {
            desc();
            steps();
            exit();
        } else {
            exit(false, false);
        }
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

    private void examples() {
        enter(Type.G_EXAMPLES_PARENT);
        while (true) {
            enter(Type.G_EXAMPLES);
            tags();
            if (!consumeIf(Token.G_EXAMPLES)) {
                exit(false, false);
                break;
            }
            desc();
            table();
        }
        exit();
    }

    private void table() {
        enter(Type.G_TABLE);
        while (peekIf(Token.G_PIPE_FIRST)) {
            enter(Type.G_TABLE_ROW);
            consume(Token.G_PIPE_FIRST);
            while (anyOf(Token.G_PIPE, Token.G_TABLE_CELL)) {

            }
            exit();
        }
        exit();
    }

}
