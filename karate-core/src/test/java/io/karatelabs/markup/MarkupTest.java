package io.karatelabs.markup;

import io.karatelabs.common.Resource;
import io.karatelabs.js.Engine;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class MarkupTest {

    static final Logger logger = LoggerFactory.getLogger(MarkupTest.class);

    private static String render(String filename) {
        Engine js = new Engine();
        RootResourceResolver resolver = new RootResourceResolver("classpath:markup");
        Markup markup = Markup.init(js, resolver);
        return markup.processPath(filename, null);
    }

    @Test
    void testHtmlString() {
        Engine js = new Engine();
        js.put("message", "hello world");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));
        String html = "<div><div th:text=\"message\"></div><div th:replace=\"/temp.html\"></div></div>";
        String rendered = markup.processString(html, null);
        assertEquals("<div><div>hello world</div><div>temp</div></div>", rendered);
    }

    @Test
    void testHtmlFile() {
        String rendered = render("main.html");
        assertFalse(rendered.contains("<script foo=\"bar\" ka:scope=\"local\"></script>"));
        assertTrue(rendered.contains("<div id=\"local_js\">local.js called</div>"));
        assertTrue(rendered.contains("<div id=\"global_js\">global.js called</div>"));
        assertTrue(rendered.contains("<div id=\"before_one\"><span>js_one</span></div>"));
        assertTrue(rendered.contains("<div id=\"called_one\">called_one</div>"));
        assertTrue(rendered.contains("<div id=\"after_one\"><span>js_one</span></div>"));
    }

    @Test
    void testKaSet() {
        String rendered = render("ka-set.html");
        assertEquals("<div>"
                + "first line\n"
                + "second line"
                + "</div>", rendered.replaceAll("\\r", "").trim());
    }

    @Test
    void testWith() {
        String rendered = render("with");
        assertTrue(rendered.contains("<div>bar</div>"));
        assertTrue(rendered.contains("<div>hello world</div>"));
        assertTrue(rendered.contains("<div>with</div>"));
    }

    @Test
    void testAttr() {
        String rendered = render("attr.html");
        assertTrue(rendered.contains("<div foo=\"a\">normal</div>"));
        assertTrue(rendered.contains("<div foo=\"xa\">append</div>"));
        assertTrue(rendered.contains("<div foo=\"ax\">prepend</div>"));
    }

    @Test
    void testNoCache() {
        Resource resource = Resource.path("classpath:markup/temp.js");
        String rendered = render("nocache.html");
        assertTrue(rendered.contains("<script src=\"temp.js?ts=" + resource.getLastModified() + "\"></script>"));
    }

    static String MY_COLON = "my:";

    @Test
    void testCustomResolverAndThis() {
        Engine js = new Engine();
        RootResourceResolver resolver = new RootResourceResolver("classpath:markup") {
            @Override
            public Resource resolve(String path, Resource caller) {
                if (path.startsWith(MY_COLON)) {
                    path = "custom/" + path.substring(MY_COLON.length());
                    return super.resolve(path, caller);
                }
                return super.resolve(path, caller);
            }
        };
        Markup markup = Markup.init(js, resolver);
        String rendered = markup.processPath("custom", null);
        assertEquals("<div><div>caller</div>\n<div><div>called</div></div></div>", rendered.replaceAll("\\r", ""));
    }


}
