package io.karatelabs.markup;

import io.karatelabs.common.Resource;
import io.karatelabs.js.Engine;
import io.karatelabs.js.ExternalBridge;
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

    // ========== MarkupContext Tests ==========

    @Test
    void testContextRead() {
        Engine js = new Engine();
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));
        // context.read() in th:text expression
        String html = "<div th:text=\"context.read('test-data.json')\"></div>";
        String rendered = markup.processString(html, null);
        assertTrue(rendered.contains("karate"));
        assertTrue(rendered.contains("version"));
    }

    @Test
    void testContextMethods() {
        // Test context.read, context.toJson, context.template using file-based template
        String rendered = render("context-test.html");
        // context.template returns current template name
        assertTrue(rendered.contains("<div id=\"template\">context-test.html</div>"), "template not found in: " + rendered);
        // context.read returns file content (quotes are HTML-encoded in output)
        assertTrue(rendered.contains("karate") && rendered.contains("version"), "read content not found in: " + rendered);
        // context.toJson returns JSON string (quotes HTML-encoded as &quot;)
        assertTrue(rendered.contains("msg") && rendered.contains("hello"), "json not found in: " + rendered);
    }

    @Test
    void testTemplateErrorLogging() {
        // Test that template errors produce clear error messages with line info
        Engine js = new Engine();
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        // Template with intentional error - undefined variable
        String template = """
            <div>
                <script ka:scope="global">
                    _.value = undefinedVar.foo
                </script>
                <span th:text="value">test</span>
            </div>
            """;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            markup.processString(template, null);
        });

        // Verify the root cause contains useful error info
        Throwable rootCause = ex;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        assertTrue(message.contains("undefinedVar"), "Error should mention the undefined variable: " + message);
    }

    // ========== Java Interop Tests ==========

    @Test
    void testJavaInterop() {
        // Test Java.type() in templates
        Engine js = new Engine();
        js.setExternalBridge(new ExternalBridge() {});
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        // Test using Java.type() to create a UUID
        String html = """
            <div>
                <script ka:scope="global">
                    var UUID = Java.type('java.util.UUID');
                    _.uuid = UUID.randomUUID().toString();
                    _.uuidLen = _.uuid.length;
                </script>
                <span th:text="uuidLen">0</span>
            </div>
            """;
        String rendered = markup.processString(html, null);
        // UUID string is 36 characters (8-4-4-4-12 format)
        assertTrue(rendered.contains("<span>36</span>"), "UUID length should be 36: " + rendered);
    }

    @Test
    void testJavaInteropDateFormatting() {
        // Test Java interop for date formatting as documented
        Engine js = new Engine();
        js.setExternalBridge(new ExternalBridge() {});
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = """
            <div>
                <script ka:scope="global">
                    var SimpleDateFormat = Java.type('java.text.SimpleDateFormat');
                    var Date = Java.type('java.util.Date');
                    var formatter = new SimpleDateFormat('yyyy-MM-dd');
                    // Use a fixed date for testing: Jan 15, 2024
                    var date = new Date(1705276800000);
                    _.formatted = formatter.format(date);
                </script>
                <span th:text="formatted">date</span>
            </div>
            """;
        String rendered = markup.processString(html, null);
        assertTrue(rendered.contains("2024-01-15"), "Formatted date should be 2024-01-15: " + rendered);
    }

    @Test
    void testJavaInteropDirectClassPath() {
        // Test direct class path access (without Java.type)
        Engine js = new Engine();
        js.setExternalBridge(new ExternalBridge() {});
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = """
            <div>
                <script ka:scope="global">
                    // Math.max returns double, cast to int for clean output
                    _.result = parseInt(java.lang.Math.max(10, 20));
                </script>
                <span th:text="result">0</span>
            </div>
            """;
        String rendered = markup.processString(html, null);
        assertTrue(rendered.contains("<span>20</span>"), "Math.max should return 20: " + rendered);
    }

    @Test
    void testIterationStatusWithJavaInterop() {
        // Test that Thymeleaf's IterationStatus works with Java interop
        // The iter variable should be accessible directly without conversion
        Engine js = new Engine();
        js.setExternalBridge(new ExternalBridge() {});
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = """
            <ul>
                <li th:each="item, iter: ['a', 'b', 'c']">
                    <span th:text="iter.index">0</span>-<span th:text="item">x</span>
                </li>
            </ul>
            """;
        String rendered = markup.processString(html, null);
        assertTrue(rendered.contains("<span>0</span>-<span>a</span>"), "First item index should be 0: " + rendered);
        assertTrue(rendered.contains("<span>1</span>-<span>b</span>"), "Second item index should be 1: " + rendered);
        assertTrue(rendered.contains("<span>2</span>-<span>c</span>"), "Third item index should be 2: " + rendered);
    }

    // ========== XSS Prevention Tests ==========
    // These tests verify that th:text properly escapes HTML to prevent XSS attacks.
    // This is Thymeleaf's built-in behavior - these tests document and verify it.

    @Test
    void testThTextEscapesScriptTags() {
        Engine js = new Engine();
        js.put("userInput", "<script>alert('xss')</script>");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = "<div th:text=\"userInput\">placeholder</div>";
        String rendered = markup.processString(html, null);

        // Script tags should be escaped, not executable
        assertFalse(rendered.contains("<script>"), "Script tag should be escaped");
        assertTrue(rendered.contains("&lt;script&gt;"), "Should contain escaped script tag");
        assertTrue(rendered.contains("&lt;/script&gt;"), "Should contain escaped closing tag");
    }

    @Test
    void testThTextEscapesHtmlEntities() {
        Engine js = new Engine();
        js.put("userInput", "<b>bold</b> & \"quoted\" 'apostrophe'");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = "<div th:text=\"userInput\">placeholder</div>";
        String rendered = markup.processString(html, null);

        // All special HTML characters should be escaped
        assertFalse(rendered.contains("<b>"), "HTML tags should be escaped");
        assertTrue(rendered.contains("&lt;b&gt;"), "Should contain escaped <b>");
        assertTrue(rendered.contains("&amp;"), "Ampersand should be escaped");
        assertTrue(rendered.contains("&quot;") || rendered.contains("&#34;"), "Quotes should be escaped");
    }

    @Test
    void testThTextEscapesEventHandlers() {
        Engine js = new Engine();
        js.put("userInput", "<img src=x onerror=alert('xss')>");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = "<div th:text=\"userInput\">placeholder</div>";
        String rendered = markup.processString(html, null);

        // Event handler injection should be escaped - the < becomes &lt; so it's not an HTML tag
        assertFalse(rendered.contains("<img"), "IMG tag should be escaped");
        assertTrue(rendered.contains("&lt;img"), "Should contain escaped img tag");
        // The onerror= text is present but not executable because it's inside escaped text
        assertTrue(rendered.contains("onerror="), "Text contains onerror but it's escaped");
    }

    @Test
    void testThTextEscapesJavascriptUrl() {
        Engine js = new Engine();
        js.put("userInput", "<a href=\"javascript:alert('xss')\">click</a>");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = "<div th:text=\"userInput\">placeholder</div>";
        String rendered = markup.processString(html, null);

        // Javascript URL should be escaped as text
        assertFalse(rendered.contains("<a href"), "Anchor tag should be escaped");
        assertTrue(rendered.contains("&lt;a"), "Should contain escaped anchor");
    }

    @Test
    void testThUtextDoesNotEscape() {
        // Document that th:utext does NOT escape - should only be used with trusted content
        Engine js = new Engine();
        js.put("trustedHtml", "<b>bold</b>");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = "<div th:utext=\"trustedHtml\">placeholder</div>";
        String rendered = markup.processString(html, null);

        // th:utext renders HTML as-is (use only with trusted content!)
        assertTrue(rendered.contains("<b>bold</b>"), "th:utext should render HTML unescaped");
    }

    @Test
    void testThAttrEscapesAttributeValues() {
        // Test escaping in th:title (standard attribute) context
        Engine js = new Engine();
        js.put("userTitle", "Title with \"quotes\" and <tags>");
        Markup markup = Markup.init(js, new RootResourceResolver("classpath:markup"));

        String html = "<div th:title=\"userTitle\">content</div>";
        String rendered = markup.processString(html, null);

        // Attribute values should be properly escaped
        assertTrue(rendered.contains("title="), "Should have title attribute");
        // Quotes and angle brackets should be escaped in attribute value
        assertFalse(rendered.contains("<tags>"), "Tags in attr should be escaped");
    }

}
