package io.karatelabs.markup.htmx;

import io.karatelabs.js.Engine;
import io.karatelabs.markup.Markup;
import io.karatelabs.markup.MarkupConfig;
import io.karatelabs.markup.RootResourceResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HtmxDialectTest {

    // =================================================================================================================
    // HtmxConfig Tests
    // =================================================================================================================

    @Test
    void testHtmxConfigDefaults() {
        HtmxConfig config = new HtmxConfig();
        assertEquals("", config.getContextPath());
        assertTrue(config.isAddIndicatorClass());
    }

    @Test
    void testHtmxConfigContextPath() {
        HtmxConfig config = new HtmxConfig()
                .setContextPath("/app");
        assertEquals("/app", config.getContextPath());
    }

    @Test
    void testHtmxConfigContextPathNull() {
        HtmxConfig config = new HtmxConfig()
                .setContextPath(null);
        assertEquals("", config.getContextPath());
    }

    @Test
    void testHtmxConfigAddIndicatorClass() {
        HtmxConfig config = new HtmxConfig()
                .setAddIndicatorClass(false);
        assertFalse(config.isAddIndicatorClass());
    }

    @Test
    void testHtmxConfigChaining() {
        HtmxConfig config = new HtmxConfig()
                .setContextPath("/api")
                .setAddIndicatorClass(false);
        assertEquals("/api", config.getContextPath());
        assertFalse(config.isAddIndicatorClass());
    }

    // =================================================================================================================
    // HtmxDialect Tests
    // =================================================================================================================

    @Test
    void testHtmxDialectDefaults() {
        HtmxDialect dialect = new HtmxDialect();
        assertEquals("Htmx", dialect.getName());
        assertEquals("ka", dialect.getPrefix());
        assertNotNull(dialect.getConfig());
        assertEquals("", dialect.getConfig().getContextPath());
    }

    @Test
    void testHtmxDialectWithConfig() {
        HtmxConfig config = new HtmxConfig().setContextPath("/app");
        HtmxDialect dialect = new HtmxDialect(config);
        assertEquals("/app", dialect.getConfig().getContextPath());
    }

    @Test
    void testHtmxDialectProcessorsCount() {
        HtmxDialect dialect = new HtmxDialect();
        // 5 method processors (get, post, put, patch, delete) + 1 vals processor
        assertEquals(6, dialect.getProcessors("ka").size());
    }

    @Test
    void testHtmxDialectRegistrationWithMarkup() {
        Engine engine = new Engine();
        MarkupConfig config = new MarkupConfig();
        config.setResolver(new RootResourceResolver("classpath:templates"));
        config.setDevMode(true);

        HtmxDialect htmxDialect = new HtmxDialect();
        Markup markup = Markup.init(engine, config, htmxDialect);

        // Verify markup works with the dialect registered
        String result = markup.processString("<div th:text=\"${name}\">placeholder</div>", Map.of("name", "test"));
        assertEquals("<div>test</div>", result);
    }

    @Test
    void testHtmxDialectRegistrationWithContextPath() {
        Engine engine = new Engine();
        MarkupConfig config = new MarkupConfig();
        config.setResolver(new RootResourceResolver("classpath:templates"));
        config.setDevMode(true);

        HtmxConfig htmxConfig = new HtmxConfig().setContextPath("/myapp");
        HtmxDialect htmxDialect = new HtmxDialect(htmxConfig);
        Markup markup = Markup.init(engine, config, htmxDialect);

        // Verify markup works with the dialect registered
        String result = markup.processString("<span th:text=\"${value}\">x</span>", Map.of("value", "hello"));
        assertEquals("<span>hello</span>", result);
    }

    // =================================================================================================================
    // HxMethodProcessor Tests
    // =================================================================================================================

    private Markup createMarkup() {
        return createMarkup(new HtmxConfig());
    }

    private Markup createMarkup(HtmxConfig htmxConfig) {
        Engine engine = new Engine();
        MarkupConfig config = new MarkupConfig();
        config.setResolver(new RootResourceResolver("classpath:templates"));
        config.setDevMode(true);
        return Markup.init(engine, config, new HtmxDialect(htmxConfig));
    }

    @Test
    void testKaGetConvertsToHxGet() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:get=\"/users\">Load</button>", Map.of());
        assertEquals("<button hx-get=\"/users\">Load</button>", result);
    }

    @Test
    void testKaPostConvertsToHxPost() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:post=\"/users\">Save</button>", Map.of());
        assertEquals("<button hx-post=\"/users\">Save</button>", result);
    }

    @Test
    void testKaPutConvertsToHxPut() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:put=\"/users/1\">Update</button>", Map.of());
        assertEquals("<button hx-put=\"/users/1\">Update</button>", result);
    }

    @Test
    void testKaPatchConvertsToHxPatch() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:patch=\"/users/1\">Patch</button>", Map.of());
        assertEquals("<button hx-patch=\"/users/1\">Patch</button>", result);
    }

    @Test
    void testKaDeleteConvertsToHxDelete() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:delete=\"/users/1\">Delete</button>", Map.of());
        assertEquals("<button hx-delete=\"/users/1\">Delete</button>", result);
    }

    @Test
    void testKaGetWithContextPath() {
        HtmxConfig htmxConfig = new HtmxConfig().setContextPath("/app");
        Markup markup = createMarkup(htmxConfig);
        String result = markup.processString("<button ka:get=\"/users\">Load</button>", Map.of());
        assertEquals("<button hx-get=\"/app/users\">Load</button>", result);
    }

    @Test
    void testKaPostWithContextPath() {
        HtmxConfig htmxConfig = new HtmxConfig().setContextPath("/myapp");
        Markup markup = createMarkup(htmxConfig);
        String result = markup.processString("<form ka:post=\"/submit\">Submit</form>", Map.of());
        assertEquals("<form hx-post=\"/myapp/submit\">Submit</form>", result);
    }

    @Test
    void testKaGetWithExternalUrl() {
        // External URLs (not starting with /) should NOT have context path prepended
        HtmxConfig htmxConfig = new HtmxConfig().setContextPath("/app");
        Markup markup = createMarkup(htmxConfig);
        String result = markup.processString("<button ka:get=\"https://example.com/api\">Load</button>", Map.of());
        assertEquals("<button hx-get=\"https://example.com/api\">Load</button>", result);
    }

    @Test
    void testKaGetWithExpression() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:get=\"/users/${id}\">Load</button>", Map.of("id", "123"));
        assertEquals("<button hx-get=\"/users/123\">Load</button>", result);
    }

    @Test
    void testKaPostWithExpression() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:post=\"${endpoint}\">Save</button>", Map.of("endpoint", "/api/save"));
        assertEquals("<button hx-post=\"/api/save\">Save</button>", result);
    }

    @Test
    void testKaGetWithComplexExpression() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:get=\"/items/${category}/${id}\">View</button>",
                Map.of("category", "books", "id", "42"));
        assertEquals("<button hx-get=\"/items/books/42\">View</button>", result);
    }

    @Test
    void testMultipleHtmxAttributes() {
        Markup markup = createMarkup();
        String result = markup.processString(
                "<button ka:get=\"/data\" ka:post=\"/save\">Multi</button>", Map.of());
        // Both attributes should be converted
        assertTrue(result.contains("hx-get=\"/data\""));
        assertTrue(result.contains("hx-post=\"/save\""));
    }

    @Test
    void testKaGetPreservesOtherAttributes() {
        Markup markup = createMarkup();
        String result = markup.processString(
                "<button id=\"btn\" class=\"primary\" ka:get=\"/users\">Load</button>", Map.of());
        assertTrue(result.contains("id=\"btn\""));
        assertTrue(result.contains("class=\"primary\""));
        assertTrue(result.contains("hx-get=\"/users\""));
    }

    @Test
    void testKaGetWithThisKeyword() {
        Engine engine = new Engine();
        MarkupConfig config = new MarkupConfig();
        config.setResolver(new RootResourceResolver("classpath:markup"));
        config.setDevMode(true);
        Markup markup = Markup.init(engine, config, new HtmxDialect());

        // Test template: <button ka:get="this">Reload</button>
        String result = markup.processPath("htmx-this.html", Map.of());
        // "this" should resolve to the template path: /htmx-this
        assertEquals("<button hx-get=\"/htmx-this\">Reload</button>", result);
    }

    @Test
    void testKaPostWithThisKeywordAndContextPath() {
        Engine engine = new Engine();
        MarkupConfig config = new MarkupConfig();
        config.setResolver(new RootResourceResolver("classpath:markup"));
        config.setDevMode(true);
        HtmxConfig htmxConfig = new HtmxConfig().setContextPath("/app");
        Markup markup = Markup.init(engine, config, new HtmxDialect(htmxConfig));

        // Using inline template with "this" - need to use path for "this" to work
        String result = markup.processPath("htmx-this.html", Map.of());
        // "this" resolves to /htmx-this, then context path /app is prepended
        assertEquals("<button hx-get=\"/app/htmx-this\">Reload</button>", result);
    }

    // =================================================================================================================
    // HxValsProcessor Tests
    // =================================================================================================================

    @Test
    void testKaValsSimpleKeyValue() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:vals=\"edit:true\">Edit</button>", Map.of());
        // Single quotes around JSON with double quotes inside
        assertEquals("<button hx-vals='{\"edit\":true}'>Edit</button>", result);
    }

    @Test
    void testKaValsStringValue() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:vals=\"mode:'edit'\">Edit</button>", Map.of());
        assertEquals("<button hx-vals='{\"mode\":\"edit\"}'>Edit</button>", result);
    }

    @Test
    void testKaValsMultipleKeyValues() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:vals=\"page:1,size:10\">Load</button>", Map.of());
        assertTrue(result.contains("hx-vals='"));
        assertTrue(result.contains("\"page\":1"));
        assertTrue(result.contains("\"size\":10"));
    }

    @Test
    void testKaValsWithVariableReference() {
        Markup markup = createMarkup();
        // Variable reference like prod.id (demo pattern)
        String result = markup.processString("<button ka:vals=\"id:item.id\">View</button>",
                Map.of("item", Map.of("id", 123)));
        assertEquals("<button hx-vals='{\"id\":123}'>View</button>", result);
    }

    @Test
    void testKaValsAndKaGetCombined() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:get=\"/items\" ka:vals=\"page:1\">Load</button>", Map.of());
        assertTrue(result.contains("hx-get=\"/items\""));
        assertTrue(result.contains("hx-vals='"));
    }

}
