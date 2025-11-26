package io.karatelabs.io.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void testDefaultValues() {
        ServerConfig config = new ServerConfig();

        assertNull(config.getResourceRoot());
        assertEquals("", config.getContextPath());
        assertFalse(config.isDevMode());
        assertEquals(600, config.getSessionExpirySeconds());
        assertEquals("karate.sid", config.getSessionCookieName());
        assertNull(config.getSessionStore());
        assertEquals("/api/", config.getApiPrefix());
        assertTrue(config.isApiPrefixEnabled());
        assertEquals("/pub/", config.getStaticPrefix());
        assertTrue(config.isStaticPrefixEnabled());
        assertTrue(config.isCsrfEnabled());
        assertNull(config.getAllowedOrigins());
        assertNull(config.getErrorTemplate404());
        assertNull(config.getErrorTemplate500());
        assertNull(config.getRequestInterceptor());
    }

    @Test
    void testConstructorWithResourceRoot() {
        ServerConfig config = new ServerConfig("classpath:templates");

        assertEquals("classpath:templates", config.getResourceRoot());
    }

    @Test
    void testFluentBuilder() {
        InMemorySessionStore store = new InMemorySessionStore();

        ServerConfig config = new ServerConfig()
                .resourceRoot("classpath:web")
                .contextPath("/app")
                .devMode(true)
                .sessionExpirySeconds(1800)
                .sessionCookieName("my.session")
                .sessionStore(store)
                .apiPrefix("/rest/")
                .apiPrefixEnabled(true)
                .staticPrefix("/static/")
                .staticPrefixEnabled(true)
                .csrfEnabled(false)
                .allowedOrigins("https://example.com", "https://other.com")
                .errorTemplate404("errors/404.html")
                .errorTemplate500("errors/500.html");

        assertEquals("classpath:web", config.getResourceRoot());
        assertEquals("/app", config.getContextPath());
        assertTrue(config.isDevMode());
        assertEquals(1800, config.getSessionExpirySeconds());
        assertEquals("my.session", config.getSessionCookieName());
        assertSame(store, config.getSessionStore());
        assertEquals("/rest/", config.getApiPrefix());
        assertTrue(config.isApiPrefixEnabled());
        assertEquals("/static/", config.getStaticPrefix());
        assertTrue(config.isStaticPrefixEnabled());
        assertFalse(config.isCsrfEnabled());
        assertArrayEquals(new String[]{"https://example.com", "https://other.com"}, config.getAllowedOrigins());
        assertEquals("errors/404.html", config.getErrorTemplate404());
        assertEquals("errors/500.html", config.getErrorTemplate500());
    }

    @Test
    void testContextPathNullHandling() {
        ServerConfig config = new ServerConfig().contextPath(null);

        assertEquals("", config.getContextPath());
    }

    @Test
    void testIsApiPath() {
        ServerConfig config = new ServerConfig().apiPrefix("/api/");

        assertTrue(config.isApiPath("/api/users"));
        assertTrue(config.isApiPath("/api/"));
        assertFalse(config.isApiPath("/users"));
        assertFalse(config.isApiPath("/apifoo"));
    }

    @Test
    void testIsApiPathDisabled() {
        ServerConfig config = new ServerConfig()
                .apiPrefix("/api/")
                .apiPrefixEnabled(false);

        assertFalse(config.isApiPath("/api/users"));
    }

    @Test
    void testIsApiPathNullPrefix() {
        ServerConfig config = new ServerConfig().apiPrefix(null);

        assertFalse(config.isApiPath("/api/users"));
    }

    @Test
    void testIsStaticPath() {
        ServerConfig config = new ServerConfig().staticPrefix("/pub/");

        assertTrue(config.isStaticPath("/pub/app.js"));
        assertTrue(config.isStaticPath("/pub/"));
        assertFalse(config.isStaticPath("/assets/app.js"));
        assertFalse(config.isStaticPath("/pubfoo"));
    }

    @Test
    void testIsStaticPathDisabled() {
        ServerConfig config = new ServerConfig()
                .staticPrefix("/pub/")
                .staticPrefixEnabled(false);

        assertFalse(config.isStaticPath("/pub/app.js"));
    }

    @Test
    void testIsStaticPathNullPrefix() {
        ServerConfig config = new ServerConfig().staticPrefix(null);

        assertFalse(config.isStaticPath("/pub/app.js"));
    }

    @Test
    void testIsSessionEnabled() {
        ServerConfig config = new ServerConfig();
        assertFalse(config.isSessionEnabled());

        config.sessionStore(new InMemorySessionStore());
        assertTrue(config.isSessionEnabled());
    }

    @Test
    void testCreateSession() {
        InMemorySessionStore store = new InMemorySessionStore();
        ServerConfig config = new ServerConfig()
                .sessionStore(store)
                .sessionExpirySeconds(300);

        Session session = config.createSession();

        assertNotNull(session);
        assertNotNull(session.getId());
        assertEquals(1, store.size());
    }

    @Test
    void testCreateSessionNoStore() {
        ServerConfig config = new ServerConfig();

        Session session = config.createSession();

        assertNull(session);
    }

    @Test
    void testRequestInterceptor() {
        final boolean[] called = {false};
        ServerConfig config = new ServerConfig()
                .requestInterceptor(request -> called[0] = true);

        assertNotNull(config.getRequestInterceptor());
        config.getRequestInterceptor().accept(new HttpRequest());
        assertTrue(called[0]);
    }

    @Test
    void testToString() {
        ServerConfig config = new ServerConfig()
                .resourceRoot("classpath:web")
                .contextPath("/app")
                .devMode(true);

        String str = config.toString();

        assertTrue(str.contains("resourceRoot='classpath:web'"));
        assertTrue(str.contains("contextPath='/app'"));
        assertTrue(str.contains("devMode=true"));
    }

}
