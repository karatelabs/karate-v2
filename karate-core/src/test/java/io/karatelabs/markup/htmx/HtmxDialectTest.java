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
        // 5 method processors (get, post, put, patch, delete) + 1 vals processor + 21 generic processors + 1 json processor
        assertEquals(28, dialect.getProcessors("ka").size());
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

    // =================================================================================================================
    // HxGenericProcessor Tests
    // =================================================================================================================

    @Test
    void testKaTargetConvertsToHxTarget() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:target=\"#results\">Click</button>", Map.of());
        assertEquals("<button hx-target=\"#results\">Click</button>", result);
    }

    @Test
    void testKaSwapConvertsToHxSwap() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:swap=\"outerHTML\">Click</button>", Map.of());
        assertEquals("<button hx-swap=\"outerHTML\">Click</button>", result);
    }

    @Test
    void testKaTriggerConvertsToHxTrigger() {
        Markup markup = createMarkup();
        String result = markup.processString("<input ka:trigger=\"keyup changed delay:500ms\"/>", Map.of());
        assertEquals("<input hx-trigger=\"keyup changed delay:500ms\"/>", result);
    }

    @Test
    void testKaPushUrlConvertsToHxPushUrl() {
        Markup markup = createMarkup();
        String result = markup.processString("<a ka:push-url=\"true\" href=\"/page\">Link</a>", Map.of());
        // Attribute order isn't guaranteed, so check for both attributes
        assertTrue(result.contains("hx-push-url=\"true\""));
        assertTrue(result.contains("href=\"/page\""));
    }

    @Test
    void testKaSelectConvertsToHxSelect() {
        Markup markup = createMarkup();
        String result = markup.processString("<div ka:select=\".content\">Partial</div>", Map.of());
        assertEquals("<div hx-select=\".content\">Partial</div>", result);
    }

    @Test
    void testKaConfirmConvertsToHxConfirm() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:confirm=\"Are you sure?\">Delete</button>", Map.of());
        assertEquals("<button hx-confirm=\"Are you sure?\">Delete</button>", result);
    }

    @Test
    void testKaIndicatorConvertsToHxIndicator() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:indicator=\"#spinner\">Load</button>", Map.of());
        assertEquals("<button hx-indicator=\"#spinner\">Load</button>", result);
    }

    @Test
    void testKaBoostConvertsToHxBoost() {
        Markup markup = createMarkup();
        String result = markup.processString("<div ka:boost=\"true\"><a href=\"/\">Home</a></div>", Map.of());
        assertEquals("<div hx-boost=\"true\"><a href=\"/\">Home</a></div>", result);
    }

    @Test
    void testKaIncludeConvertsToHxInclude() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:include=\"[name='extra']\">Submit</button>", Map.of());
        assertEquals("<button hx-include=\"[name='extra']\">Submit</button>", result);
    }

    @Test
    void testKaSyncConvertsToHxSync() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:sync=\"closest form:abort\">Submit</button>", Map.of());
        assertEquals("<button hx-sync=\"closest form:abort\">Submit</button>", result);
    }

    @Test
    void testKaDisabledEltConvertsToHxDisabledElt() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:disabled-elt=\"this\">Submit</button>", Map.of());
        assertEquals("<button hx-disabled-elt=\"this\">Submit</button>", result);
    }

    @Test
    void testKaReplaceUrlConvertsToHxReplaceUrl() {
        Markup markup = createMarkup();
        String result = markup.processString("<a ka:replace-url=\"true\" href=\"/new\">Link</a>", Map.of());
        // Attribute order isn't guaranteed, so check for both attributes
        assertTrue(result.contains("hx-replace-url=\"true\""));
        assertTrue(result.contains("href=\"/new\""));
    }

    @Test
    void testKaValidateConvertsToHxValidate() {
        Markup markup = createMarkup();
        String result = markup.processString("<form ka:validate=\"true\">Form</form>", Map.of());
        assertEquals("<form hx-validate=\"true\">Form</form>", result);
    }

    @Test
    void testKaTargetWithExpression() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:target=\"#item-${id}\">Click</button>", Map.of("id", "123"));
        assertEquals("<button hx-target=\"#item-123\">Click</button>", result);
    }

    @Test
    void testKaSwapWithExpression() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:swap=\"${swapMethod}\">Click</button>", Map.of("swapMethod", "innerHTML"));
        assertEquals("<button hx-swap=\"innerHTML\">Click</button>", result);
    }

    @Test
    void testCombinedHtmxAttributes() {
        Markup markup = createMarkup();
        String result = markup.processString(
                "<button ka:get=\"/data\" ka:target=\"#results\" ka:swap=\"innerHTML\">Load</button>", Map.of());
        assertTrue(result.contains("hx-get=\"/data\""));
        assertTrue(result.contains("hx-target=\"#results\""));
        assertTrue(result.contains("hx-swap=\"innerHTML\""));
    }

    @Test
    void testKaPromptConvertsToHxPrompt() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:prompt=\"Enter value:\">Prompt</button>", Map.of());
        assertEquals("<button hx-prompt=\"Enter value:\">Prompt</button>", result);
    }

    @Test
    void testKaHeadersConvertsToHxHeaders() {
        Markup markup = createMarkup();
        String result = markup.processString("<button ka:headers=\"js:{Authorization:'Bearer token'}\">Auth</button>", Map.of());
        assertEquals("<button hx-headers=\"js:{Authorization:'Bearer token'}\">Auth</button>", result);
    }

    @Test
    void testKaExtConvertsToHxExt() {
        Markup markup = createMarkup();
        String result = markup.processString("<div ka:ext=\"json-enc\">Content</div>", Map.of());
        assertEquals("<div hx-ext=\"json-enc\">Content</div>", result);
    }

    @Test
    void testKaPreserveConvertsToHxPreserve() {
        Markup markup = createMarkup();
        String result = markup.processString("<video ka:preserve=\"true\">Video</video>", Map.of());
        assertEquals("<video hx-preserve=\"true\">Video</video>", result);
    }

    @Test
    void testComplexHtmxForm() {
        Markup markup = createMarkup();
        String html = """
                <form ka:post="/api/submit" ka:target="#result" ka:swap="outerHTML" ka:indicator="#loading">
                    <input name="name" ka:trigger="keyup changed delay:300ms"/>
                    <button ka:disabled-elt="this" ka:confirm="Submit form?">Submit</button>
                </form>""";
        String result = markup.processString(html, Map.of());
        assertTrue(result.contains("hx-post=\"/api/submit\""));
        assertTrue(result.contains("hx-target=\"#result\""));
        assertTrue(result.contains("hx-swap=\"outerHTML\""));
        assertTrue(result.contains("hx-indicator=\"#loading\""));
        assertTrue(result.contains("hx-trigger=\"keyup changed delay:300ms\""));
        assertTrue(result.contains("hx-disabled-elt=\"this\""));
        assertTrue(result.contains("hx-confirm=\"Submit form?\""));
    }

    // =================================================================================================================
    // KaDataProcessor Tests
    // =================================================================================================================

    @Test
    void testKaJsonOnFormWithSimpleObject() {
        Markup markup = createMarkup();
        String html = """
                <form ka:data="form:data">
                    <input x-model="form.name"/>
                </form>""";
        Map<String, Object> vars = Map.of("data", Map.of("name", "John", "email", "john@test.com"));
        String result = markup.processString(html, vars);

        // Should have x-data with initial values
        assertTrue(result.contains("x-data=\"{"));
        assertTrue(result.contains("form:"));

        // Should have hidden input for form submission (attribute order may vary)
        assertTrue(result.contains("type=\"hidden\""));
        assertTrue(result.contains("name=\"form\""));
        assertTrue(result.contains("x-bind:value=\"JSON.stringify(form)\""));
    }

    @Test
    void testKaJsonOnFormWithEmptyObject() {
        Markup markup = createMarkup();
        String html = "<form ka:data=\"form:empty\"><input x-model=\"form.name\"/></form>";
        Map<String, Object> vars = Map.of("empty", Map.of());
        String result = markup.processString(html, vars);

        assertTrue(result.contains("x-data=\"{ form: {} }\""));
        // Check hidden input attributes (order may vary)
        assertTrue(result.contains("type=\"hidden\""));
        assertTrue(result.contains("name=\"form\""));
        assertTrue(result.contains("x-bind:value=\"JSON.stringify(form)\""));
    }

    @Test
    void testKaJsonOnFormWithArray() {
        Markup markup = createMarkup();
        String html = "<form ka:data=\"items:list\"><div x-for=\"item in items\"></div></form>";
        Map<String, Object> vars = Map.of("list", java.util.List.of("a", "b", "c"));
        String result = markup.processString(html, vars);

        assertTrue(result.contains("x-data=\"{ items:"));
        assertTrue(result.contains("[\"a\",\"b\",\"c\"]"));
        assertTrue(result.contains("x-bind:value=\"JSON.stringify(items)\""));
    }

    @Test
    void testKaJsonOnFormWithNestedObject() {
        Markup markup = createMarkup();
        String html = "<form ka:data=\"form:nested\"><input x-model=\"form.user.name\"/></form>";
        Map<String, Object> vars = Map.of("nested",
                Map.of("user", Map.of("name", "Alice", "age", 30),
                       "active", true));
        String result = markup.processString(html, vars);

        assertTrue(result.contains("x-data=\"{ form:"));
        assertTrue(result.contains("\"user\":{"));
        assertTrue(result.contains("\"name\":\"Alice\""));
        assertTrue(result.contains("\"active\":true"));
    }

    @Test
    void testKaJsonOnFormPreservesOtherAttributes() {
        Markup markup = createMarkup();
        String html = "<form ka:data=\"form:data\" class=\"my-form\" id=\"test-form\"><input/></form>";
        Map<String, Object> vars = Map.of("data", Map.of());
        String result = markup.processString(html, vars);

        assertTrue(result.contains("class=\"my-form\""));
        assertTrue(result.contains("id=\"test-form\""));
        assertTrue(result.contains("x-data=\"{"));
    }

    @Test
    void testKaJsonOnFormCombinedWithHtmxAttributes() {
        Markup markup = createMarkup();
        String html = """
                <form ka:data="form:data" ka:post="/api/submit" ka:target="#result">
                    <input x-model="form.email"/>
                    <button type="submit">Save</button>
                </form>""";
        Map<String, Object> vars = Map.of("data", Map.of("email", ""));
        String result = markup.processString(html, vars);

        // Should have x-data and hidden input (attribute order may vary)
        assertTrue(result.contains("x-data=\"{"));
        assertTrue(result.contains("type=\"hidden\""));
        assertTrue(result.contains("name=\"form\""));
        assertTrue(result.contains("x-bind:value=\"JSON.stringify(form)\""));

        // Should have HTMX attributes
        assertTrue(result.contains("hx-post=\"/api/submit\""));
        assertTrue(result.contains("hx-target=\"#result\""));
    }

    @Test
    void testKaJsonWithComplexFormData() {
        Markup markup = createMarkup();
        String html = "<form ka:data=\"form:formData\"><input x-model=\"form.name\"/></form>";
        Map<String, Object> formData = new java.util.HashMap<>();
        formData.put("name", "Test User");
        formData.put("email", "test@example.com");
        formData.put("products", java.util.List.of("product1", "product2"));
        formData.put("notify", true);
        Map<String, Object> vars = Map.of("formData", formData);
        String result = markup.processString(html, vars);

        assertTrue(result.contains("\"name\":\"Test User\""));
        assertTrue(result.contains("\"email\":\"test@example.com\""));
        assertTrue(result.contains("\"products\":[\"product1\",\"product2\"]"));
        assertTrue(result.contains("\"notify\":true"));
    }

    @Test
    void testKaJsonOnFormWithNullInitialData() {
        Markup markup = createMarkup();
        String html = "<form ka:data=\"form:nothing\"><input x-model=\"form.name\"/></form>";
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("nothing", null);
        String result = markup.processString(html, vars);

        // Should default to empty object
        assertTrue(result.contains("x-data=\"{ form: {} }\""));
    }

    @Test
    void testKaJsonRealisticManageTeamExample() {
        // Based on karate-studio manage-team.html pattern
        Markup markup = createMarkup();
        String html = """
                <form ka:data="form:initialForm">
                    <input x-model="form.email"/>
                    <input type="checkbox" x-model="form.products"/>
                    <input type="checkbox" x-model.boolean="form.notify"/>
                    <button hx-post="manage-team" hx-target="#main-content" ka:vals="action:'addUser'">
                        Add User
                    </button>
                </form>""";
        Map<String, Object> initialForm = new java.util.HashMap<>();
        initialForm.put("email", "");
        initialForm.put("role", "user");
        initialForm.put("products", java.util.List.of());
        initialForm.put("notify", true);
        Map<String, Object> vars = Map.of("initialForm", initialForm);
        String result = markup.processString(html, vars);

        // Should have proper x-data initialization
        assertTrue(result.contains("x-data=\"{ form:"));

        // Should have hidden input for form submission (attribute order may vary)
        assertTrue(result.contains("type=\"hidden\""));
        assertTrue(result.contains("name=\"form\""));
        assertTrue(result.contains("x-bind:value=\"JSON.stringify(form)\""));

        // Should preserve HTMX attributes
        assertTrue(result.contains("hx-post=\"manage-team\""));
        assertTrue(result.contains("hx-vals='{\"action\":\"addUser\"}'"));
    }

    // =================================================================================================================
    // KaDataProcessor Tests - Non-Form Elements (read-only x-data binding)
    // =================================================================================================================

    @Test
    void testKaJsonOnDivAddsXData() {
        Markup markup = createMarkup();
        String html = "<div ka:data=\"data:serverData\"><span x-text=\"data.name\"></span></div>";
        Map<String, Object> vars = Map.of("serverData", Map.of("name", "Alice", "count", 42));
        String result = markup.processString(html, vars);

        // Should have x-data with server values
        assertTrue(result.contains("x-data=\"{ data:"));
        assertTrue(result.contains("\"name\":\"Alice\""));
        assertTrue(result.contains("\"count\":42"));

        // Should NOT have hidden input (only forms get that)
        assertFalse(result.contains("type=\"hidden\""));
        assertFalse(result.contains("x-bind:value"));
    }

    @Test
    void testKaJsonOnSectionWithArray() {
        Markup markup = createMarkup();
        String html = "<section ka:data=\"items:list\"><template x-for=\"item in items\"></template></section>";
        Map<String, Object> vars = Map.of("list", java.util.List.of("one", "two", "three"));
        String result = markup.processString(html, vars);

        assertTrue(result.contains("x-data=\"{ items:"));
        assertTrue(result.contains("[\"one\",\"two\",\"three\"]"));
        assertFalse(result.contains("type=\"hidden\""));
    }

    @Test
    void testKaJsonOnDivPreservesAttributes() {
        Markup markup = createMarkup();
        String html = "<div ka:data=\"config:settings\" class=\"my-class\" id=\"my-id\"><span x-text=\"config.theme\"></span></div>";
        Map<String, Object> vars = Map.of("settings", Map.of("theme", "dark", "lang", "en"));
        String result = markup.processString(html, vars);

        assertTrue(result.contains("class=\"my-class\""));
        assertTrue(result.contains("id=\"my-id\""));
        assertTrue(result.contains("x-data=\"{ config:"));
        assertTrue(result.contains("\"theme\":\"dark\""));
    }

    @Test
    void testKaJsonOnSpanWithNestedData() {
        Markup markup = createMarkup();
        String html = "<span ka:data=\"user:profile\" x-text=\"user.name\"></span>";
        Map<String, Object> vars = Map.of("profile", Map.of(
                "name", "Bob",
                "address", Map.of("city", "NYC", "zip", "10001")));
        String result = markup.processString(html, vars);

        assertTrue(result.contains("x-data=\"{ user:"));
        assertTrue(result.contains("\"name\":\"Bob\""));
        assertTrue(result.contains("\"address\":{"));
        assertTrue(result.contains("\"city\":\"NYC\""));
    }

    @Test
    void testKaJsonFormHasHiddenInput() {
        Markup markup = createMarkup();
        Map<String, Object> vars = Map.of("data", Map.of("value", "test"));
        String formHtml = "<form ka:data=\"form:data\"><input x-model=\"form.value\"/></form>";
        String formResult = markup.processString(formHtml, vars);

        assertTrue(formResult.contains("type=\"hidden\""));
        assertTrue(formResult.contains("x-bind:value=\"JSON.stringify(form)\""));
    }

    @Test
    void testKaJsonDivHasNoHiddenInput() {
        Markup markup = createMarkup();
        Map<String, Object> vars = Map.of("data", Map.of("value", "test"));
        String divHtml = "<div ka:data=\"form:data\"><span x-text=\"form.value\"></span></div>";
        String divResult = markup.processString(divHtml, vars);

        assertTrue(divResult.contains("x-data=\"{ form:"));
        assertFalse(divResult.contains("type=\"hidden\""));
        assertFalse(divResult.contains("x-bind:value"));
    }

    // =================================================================================================================
    // KaDataProcessor Tests - Integration with ka:scope="local"
    // =================================================================================================================

    @Test
    void testKaDataWithKaScopeLocal() {
        // Test that ka:scope="local" sets up variables that ka:data can access
        // Variables set as _.varName in ka:scope are available as varName directly
        Markup markup = createMarkup();
        String html = """
                <div>
                    <script ka:scope="local">_.formData = {name: 'John', email: 'john@test.com'}</script>
                    <form ka:data="form:formData">
                        <input x-model="form.name"/>
                    </form>
                </div>""";
        String result = markup.processString(html, Map.of());
        System.out.println("DEBUG ka:scope + ka:data: " + result);

        // Should have x-data with values from ka:scope
        assertTrue(result.contains("x-data=\"{ form:"), "x-data not found in: " + result);
        assertTrue(result.contains("\"name\":\"John\""), "name not found in: " + result);
        assertTrue(result.contains("\"email\":\"john@test.com\""), "email not found in: " + result);
    }

    @Test
    void testKaDataWithKaScopeLocalArray() {
        // Variables set as _.items in ka:scope are available as items directly
        Markup markup = createMarkup();
        String html = """
                <div>
                    <script ka:scope="local">_.items = ['apple', 'banana', 'cherry']</script>
                    <section ka:data="list:items">
                        <template x-for="item in list"><span x-text="item"></span></template>
                    </section>
                </div>""";
        String result = markup.processString(html, Map.of());

        assertTrue(result.contains("x-data=\"{ list:"), "x-data not found in: " + result);
        assertTrue(result.contains("[\"apple\",\"banana\",\"cherry\"]"), "array not found in: " + result);
    }

}
