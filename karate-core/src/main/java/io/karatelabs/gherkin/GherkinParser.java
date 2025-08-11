package io.karatelabs.gherkin;

import io.karatelabs.js.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.CharArrayReader;

public class GherkinParser {

    static final Logger logger = LoggerFactory.getLogger(GherkinParser.class);

    public GherkinParser(Source source) {
        getChunks(source);
    }

    private static void getChunks(Source source) {
        CharArrayReader reader = new CharArrayReader(source.text.toCharArray());
        GherkinLexer lexer = new GherkinLexer(reader);
        int line = 0;
        int col = 0;
        long pos = 0;
        try {
            while (true) {
                GherkinToken token = lexer.yylex();
                if (token == GherkinToken.EOF) {
                    break;
                }
                String text = lexer.yytext();
                logger.debug("{}:{} {} - {}", line, col, token, text);
                int length = lexer.yylength();
                pos += length;
                if (token == GherkinToken.WS_LF) {
                    for (int i = 0; i < length; i++) {
                        if (text.charAt(i) == '\n') {
                            col = 0;
                            line++;
                        } else {
                            col++;
                        }
                    }
                } else {
                    col += length;
                }
            }
        } catch (Throwable e) {
            String message = "lexer failed at [" + (line + 1) + ":" + (col + 1) + "] " + source.getStringForLog();
            throw new RuntimeException(message, e);
        }
    }

}
