# Karate V2 Mock Server Design

This document describes the mock server architecture for Karate V2.

> See also: [RUNTIME.md](./RUNTIME.md) | [CLI.md](./CLI.md) | [COMPATIBILITY.md](./COMPATIBILITY.md)

---

## Overview

Mock servers in Karate allow you to create API test-doubles using feature files. Scenarios act as request matchers, with JavaScript expressions determining which scenario handles each incoming request.

**Key V2 design principles:**
- V2's JS engine is better - no "re-hydration" needed, objects pass directly
- Keep JS functions as `JsCallable` for engine context injection
- Request locking may still be needed for thread safety
- Most tests use in-memory harness; real HTTP tests are minimal
- New `@mock` tag identifies mock feature files
- CLI: all mock functionality under `mock` subcommand

---

## Architecture

```
CLI / karate.start()
        ↓
    MockServer (HttpServer wrapper)
        ↓
    MockHandler implements Function<HttpRequest, HttpResponse>
        ↓
    ┌───────────────────────────────────────────┐
    │  For each request:                        │
    │  1. Set up request variables              │
    │  2. Evaluate scenarios (first match wins) │
    │  3. Execute matched scenario              │
    │  4. Build response from variables         │
    └───────────────────────────────────────────┘
```

### Core Classes

| Class | Location | Description |
|-------|----------|-------------|
| `MockServer` | `io.karatelabs.core.MockServer` | Public API, wraps HttpServer with mock feature handling |
| `MockHandler` | `io.karatelabs.core.MockHandler` | Request routing and scenario execution |
| `MockRuntime` | `io.karatelabs.core.MockRuntime` | Per-request execution context with KarateJs |
| `MockConfig` | `io.karatelabs.core.MockConfig` | Global mock configuration (CORS, headers, hooks) |

---

## Feature File Structure

```gherkin
@mock
Feature: User API Mock

Background:
  # Executes ONCE on server startup (not per request)
  * def users = {}
  * def uuid = function(){ return java.util.UUID.randomUUID() + '' }
  * configure cors = true
  * configure responseHeaders = { 'Content-Type': 'application/json' }

Scenario: pathMatches('/users/{id}') && methodIs('get')
  * def user = users[pathParams.id]
  * def response = user
  * def responseStatus = user ? 200 : 404

Scenario: pathMatches('/users') && methodIs('post')
  * def id = uuid()
  * users[id] = request
  * users[id].id = id
  * def response = users[id]
  * def responseStatus = 201

Scenario:
  # Catch-all (no matcher expression = always matches)
  * def responseStatus = 404
  * def response = { error: 'not found' }
```

### @mock Tag

The `@mock` tag at feature level indicates this is a mock definition (not a test):
- Background executes once on server startup
- Scenarios are request matchers, not test cases
- Scenario names/descriptions are JavaScript expressions for matching
- `Scenario Outline` is NOT supported in mock mode

---

## Request Variables

Available in all mock scenarios:

| Variable | Type | Description |
|----------|------|-------------|
| `request` | Object | Parsed request body (JSON object, XML, or string) |
| `requestBytes` | byte[] | Raw request body bytes |
| `requestPath` | String | URL path without query string (e.g., `/users/123`) |
| `requestUri` | String | Path with query string (e.g., `/users?name=bob`) |
| `requestUrlBase` | String | Base URL (e.g., `http://localhost:8080`) |
| `requestMethod` | String | HTTP method in uppercase (GET, POST, etc.) |
| `requestHeaders` | Map | Request headers (case-insensitive access) |
| `requestParams` | Map | Query string or form parameters |
| `requestParts` | Map | Multipart request parts (file uploads) |
| `pathParams` | Map | Path parameters extracted by `pathMatches()` |

---

## Response Variables

Set these in scenarios to configure the response:

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `response` | any | null | Response body (auto-serialized to JSON/XML/text) |
| `responseStatus` | int | 200 | HTTP status code |
| `responseHeaders` | Map | {} | Additional response headers |
| `responseDelay` | int | 0 | Delay in milliseconds before sending response |

---

## Matcher Functions

All matcher functions access the current request via thread-local context:

### pathMatches(pattern)

Match URL path with placeholder extraction:

```javascript
pathMatches('/users/{id}')           // Extracts { id: '123' } from /users/123
pathMatches('/v1/cats/{catId}/toys') // Multiple placeholders supported
```

- Placeholders match exactly one path segment
- Sets `pathParams` variable on match
- Returns `true` if pattern matches, `false` otherwise

### methodIs(method)

Case-insensitive HTTP method check:

```javascript
methodIs('get')
methodIs('POST')
```

### typeContains(substring)

Check Content-Type header contains substring:

```javascript
typeContains('json')  // Matches application/json, application/ld+json
typeContains('xml')   // Matches text/xml, application/xml
```

### acceptContains(substring)

Check Accept header contains substring:

```javascript
acceptContains('json')
```

### headerContains(name, value)

Check if any value of a header contains substring:

```javascript
headerContains('Authorization', 'Bearer')
```

### paramValue(name)

Get query/form parameter value:

```javascript
paramValue('page')  // Returns string or null
```

### paramExists(name)

Check if parameter exists:

```javascript
paramExists('debug')
```

### bodyPath(expression)

Extract value from request body using JsonPath or XPath:

```javascript
bodyPath('$.user.name')  // JsonPath
bodyPath('/root/item')   // XPath (paths starting with /)
```

---

## Configuration

### CORS

```gherkin
Background:
  * configure cors = true
```

When enabled:
- OPTIONS requests return 200 with CORS headers (no scenario matching)
- All responses include `Access-Control-Allow-Origin: *`

### Response Headers

```gherkin
Background:
  * configure responseHeaders = { 'Content-Type': 'application/json' }
```

Applied to all responses. Scenario-level `responseHeaders` overrides.

### afterScenario

```gherkin
Background:
  * configure afterScenario = function(){ karate.log('request handled') }
```

Executes after each scenario, before response is sent.

---

## Stateful Mocks

Variables defined in `Background` persist across requests:

```gherkin
Background:
  * def counter = { value: 0 }

Scenario: pathMatches('/increment')
  * counter.value = counter.value + 1
  * def response = { count: counter.value }
```

**Thread safety:** The handler synchronizes request processing to prevent concurrent access to shared state.

---

## Proxy Mode

Forward requests to a real backend:

```gherkin
Scenario: requestPath.startsWith('/api')
  * def response = karate.proceed('http://real-backend:8080')

Scenario: requestPath.startsWith('/proxy')
  # Use host from incoming request (true HTTP proxy)
  * def response = karate.proceed()
```

`karate.proceed()` forwards the request and returns the response, which can be modified before returning.

---

## SSL/TLS Configuration

Karate V2 supports HTTPS for both mock servers and HTTP clients with comprehensive certificate management.

### Server-Side SSL (Mock Server)

#### Basic HTTPS

```bash
# CLI with auto-generated self-signed certificate
karate mock -m api.feature -p 8443 --ssl

# CLI with custom certificate
karate mock -m api.feature -p 8443 --ssl --cert cert.pem --key key.pem
```

```java
// Java API
MockServer server = MockServer.feature("api.feature")
    .port(8443)
    .ssl(true)
    .start();

// With custom certificate
MockServer server = MockServer.feature("api.feature")
    .port(8443)
    .ssl(true)
    .certPath("cert.pem")
    .keyPath("key.pem")
    .start();
```

```javascript
// karate.start() with SSL
var server = karate.start({ mock: 'api.feature', port: 8443, ssl: true });

// With custom certificate
var server = karate.start({
    mock: 'api.feature',
    port: 8443,
    ssl: true,
    cert: 'cert.pem',
    key: 'key.pem'
});
```

#### Self-Signed Certificate Generation

When `ssl: true` without cert/key paths:
- Auto-generates `cert.pem` and `key.pem` in temp directory
- Valid for 365 days with localhost/127.0.0.1 SANs
- Suitable for local development and testing

#### Server SSL Configuration Options

| Option | CLI | Java API | Description |
|--------|-----|----------|-------------|
| Enable SSL | `--ssl` / `-s` | `.ssl(true)` | Enable HTTPS |
| Certificate | `--cert <file>` / `-c` | `.certPath(path)` | PEM certificate file |
| Private Key | `--key <file>` / `-k` | `.keyPath(path)` | PEM private key file |

### Client-Side SSL (HTTP Client)

#### Basic SSL

```gherkin
# Trust all certificates (for self-signed)
* configure ssl = true

# Explicit trust all
* configure ssl = { trustAll: true }
```

```javascript
// In karate-config.js
karate.configure('ssl', true);
karate.configure('ssl', { trustAll: true });
```

#### X509 Certificate Authentication (Mutual TLS / mTLS)

For APIs requiring client certificate authentication:

```gherkin
# PKCS12 keystore
* configure ssl = { keyStore: 'classpath:client.pfx', keyStorePassword: 'secret', keyStoreType: 'pkcs12' }

# JKS keystore
* configure ssl = { keyStore: 'classpath:client.jks', keyStorePassword: 'secret', keyStoreType: 'jks' }

# Full mTLS with custom trust store
* configure ssl = {
    keyStore: 'classpath:client.pfx',
    keyStorePassword: 'clientpass',
    keyStoreType: 'pkcs12',
    trustStore: 'classpath:truststore.jks',
    trustStorePassword: 'trustpass',
    trustStoreType: 'jks'
  }
```

#### Client SSL Configuration Options

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `trustAll` | boolean | no | Trust all server certificates (default: false) |
| `keyStore` | string | no | Path to client certificate keystore |
| `keyStorePassword` | string | no | Keystore password |
| `keyStoreType` | string | no | Keystore format: `pkcs12`, `jks`, `pem` |
| `trustStore` | string | no | Path to trust store with CA certificates |
| `trustStorePassword` | string | no | Trust store password |
| `trustStoreType` | string | no | Trust store format: `pkcs12`, `jks`, `pem` |
| `algorithm` | string | no | SSL algorithm (default: `TLS`) |

**Supported keystore types:** As defined in [Java KeyStore docs](https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#KeyStore)

**Supported algorithms:** As defined in [Java SSLContext docs](https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#SSLContext)

### SSL Implementation

#### Core Classes

| Class | Location | Description |
|-------|----------|-------------|
| `SslConfig` | `io.karatelabs.core.SslConfig` | SSL configuration holder |
| `SslContextFactory` | `io.karatelabs.core.SslContextFactory` | Creates SSLContext from config |
| `CertificateGenerator` | `io.karatelabs.core.CertificateGenerator` | Self-signed cert generation |

#### SslConfig.java

```java
public class SslConfig {
    // Trust configuration
    private boolean trustAll;
    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType;

    // Client certificate (mTLS)
    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType;

    // Algorithm
    private String algorithm = "TLS";

    // Server-side (for MockServer)
    private String certPath;
    private String keyPath;

    public static SslConfig fromMap(Map<String, Object> map) { ... }
    public static SslConfig trustAll() { ... }
    public SSLContext createClientContext() { ... }
    public SSLContext createServerContext() { ... }
}
```

#### SslContextFactory.java

```java
public class SslContextFactory {
    public static SSLContext createClientContext(SslConfig config) {
        if (config.isTrustAll()) {
            return createTrustAllContext(config.getAlgorithm());
        }

        TrustManager[] trustManagers = loadTrustManagers(config);
        KeyManager[] keyManagers = loadKeyManagers(config);

        SSLContext ctx = SSLContext.getInstance(config.getAlgorithm());
        ctx.init(keyManagers, trustManagers, new SecureRandom());
        return ctx;
    }

    public static SSLContext createServerContext(SslConfig config) {
        if (config.getCertPath() == null) {
            // Generate self-signed certificate
            return CertificateGenerator.generateSelfSigned();
        }
        return loadFromPem(config.getCertPath(), config.getKeyPath());
    }
}
```

#### Integration Points

**HTTP Client (KarateHttpClient):**
```java
public class KarateHttpClient {
    private SslConfig sslConfig;

    public void configure(String key, Object value) {
        if ("ssl".equals(key)) {
            if (value instanceof Boolean b && b) {
                this.sslConfig = SslConfig.trustAll();
            } else if (value instanceof Map map) {
                this.sslConfig = SslConfig.fromMap(map);
            }
            applySSLContext();
        }
    }
}
```

**Mock Server (HttpServer):**
```java
public class HttpServer {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SslConfig sslConfig;

        public Builder ssl(boolean enabled) {
            if (enabled) {
                this.sslConfig = new SslConfig();
            }
            return this;
        }

        public Builder certPath(String path) {
            ensureSslConfig();
            this.sslConfig.setCertPath(path);
            return this;
        }

        public Builder keyPath(String path) {
            ensureSslConfig();
            this.sslConfig.setKeyPath(path);
            return this;
        }

        public HttpServer start() {
            if (sslConfig != null) {
                SSLContext ctx = SslContextFactory.createServerContext(sslConfig);
                // Apply to Netty pipeline
            }
            // ...
        }
    }
}
```

### Testing SSL

#### In-Memory SSL Tests

```java
@Test
void testSslConfigParsing() {
    Map<String, Object> map = Map.of(
        "keyStore", "client.pfx",
        "keyStorePassword", "secret",
        "keyStoreType", "pkcs12",
        "trustAll", true
    );
    SslConfig config = SslConfig.fromMap(map);

    assertEquals("client.pfx", config.getKeyStore());
    assertTrue(config.isTrustAll());
}
```

#### Integration SSL Tests

```java
@Test
void testMockServerWithSsl() {
    MockServer server = MockServer.feature("""
        @mock
        Feature:
        Scenario: methodIs('get')
          * def response = { secure: true }
        """)
        .ssl(true)
        .start();

    try {
        // Client must trust self-signed cert
        var http = karate.http("https://localhost:" + server.getPort());
        http.configure("ssl", Map.of("trustAll", true));
        var response = http.get();
        assertEquals(true, response.body().get("secure"));
    } finally {
        server.stop();
    }
}
```

#### mTLS Integration Test

```java
@Test
void testMutualTls() {
    // Server requiring client cert
    MockServer server = MockServer.feature("mtls-mock.feature")
        .ssl(true)
        .certPath("server.pem")
        .keyPath("server-key.pem")
        .clientAuth(true)  // Require client cert
        .trustStorePath("ca.pem")
        .start();

    try {
        var http = karate.http("https://localhost:" + server.getPort());
        http.configure("ssl", Map.of(
            "keyStore", "client.pfx",
            "keyStorePassword", "secret",
            "keyStoreType", "pkcs12",
            "trustAll", true
        ));
        var response = http.get("/secure");
        assertEquals(200, response.status());
    } finally {
        server.stop();
    }
}
```

---

## Implementation Plan

### Phase 1: Core MockHandler

**Files to create:**

1. **MockServer.java** - Public API
   ```java
   public class MockServer {
       public static Builder feature(String path) { ... }
       public static Builder feature(Feature feature) { ... }

       public int getPort() { ... }
       public void stop() { ... }

       public static class Builder {
           public Builder port(int port) { ... }
           public Builder ssl(boolean ssl) { ... }
           public Builder arg(Map<String, Object> arg) { ... }
           public Builder pathPrefix(String prefix) { ... }
           public MockServer start() { ... }
       }
   }
   ```

2. **MockHandler.java** - Request routing
   ```java
   public class MockHandler implements Function<HttpRequest, HttpResponse> {
       private final List<Feature> features;
       private final Map<String, Object> globals;
       private final MockConfig config;
       private final ReentrantLock requestLock = new ReentrantLock();

       @Override
       public HttpResponse apply(HttpRequest request) {
           requestLock.lock();
           try {
               return handleRequest(request);
           } finally {
               requestLock.unlock();
           }
       }
   }
   ```

3. **MockRuntime.java** - Per-request execution context
   ```java
   public class MockRuntime {
       private final KarateJs engine;
       private final HttpRequest request;

       public void initRequestVariables() { ... }
       public void registerMatcherFunctions() { ... }
       public boolean evaluateScenarioMatcher(Scenario scenario) { ... }
       public HttpResponse buildResponse() { ... }
   }
   ```

4. **MockConfig.java** - Configuration holder
   ```java
   public class MockConfig {
       private boolean corsEnabled;
       private Map<String, Object> responseHeaders;
       private JsCallable afterScenario;
   }
   ```

### Phase 2: Matcher Functions

Implement as `JsCallable` instances registered in `MockRuntime`:

```java
// In MockRuntime.registerMatcherFunctions()
engine.put("pathMatches", (JsCallable) args -> {
    String pattern = (String) args[0];
    Map<String, String> params = HttpUtils.parseUriPattern(pattern, currentRequest.path());
    if (params != null) {
        engine.put("pathParams", params);
        return true;
    }
    return false;
});

engine.put("methodIs", (JsCallable) args ->
    currentRequest.method().equalsIgnoreCase((String) args[0]));

engine.put("typeContains", (JsCallable) args -> {
    String contentType = currentRequest.header("Content-Type");
    return contentType != null && contentType.contains((String) args[0]);
});

// ... other matchers
```

### Phase 3: Integration with FeatureRuntime

Modify scenario execution for mock mode:

1. Detect `@mock` tag on feature
2. Skip normal test execution
3. Execute Background once on initialization
4. For each request, evaluate scenario matchers and execute first match

```java
// In MockHandler
private HttpResponse handleRequest(HttpRequest request) {
    MockRuntime runtime = new MockRuntime(engine, request, config);
    runtime.initRequestVariables();
    runtime.registerMatcherFunctions();

    for (Feature feature : features) {
        for (Scenario scenario : feature.getScenarios()) {
            if (runtime.evaluateScenarioMatcher(scenario)) {
                runtime.executeScenario(scenario);
                return runtime.buildResponse();
            }
        }
    }

    // No match - return 404
    return HttpResponse.of(404).body("{ \"error\": \"no matching scenario\" }");
}
```

### Phase 4: karate.start() Integration

Add to KarateJs:

```java
public MockServer start(Object config) {
    if (config instanceof String path) {
        return MockServer.feature(path).start();
    }
    Map<String, Object> map = (Map<String, Object>) config;
    MockServer.Builder builder = MockServer.feature((String) map.get("mock"));
    if (map.containsKey("port")) builder.port((int) map.get("port"));
    if (map.containsKey("ssl")) builder.ssl((boolean) map.get("ssl"));
    if (map.containsKey("arg")) builder.arg((Map<String, Object>) map.get("arg"));
    return builder.start();
}
```

### Phase 5: CLI Integration

Add `mock` subcommand to Main.java:

```bash
# Start mock server
karate mock -m users.feature -p 8080

# Multiple mock files
karate mock -m users.feature -m orders.feature -p 8080

# With SSL
karate mock -m api.feature -p 8443 --ssl

# Watch mode (hot reload)
karate mock -m api.feature -W
```

CLI options:
- `-m, --mock <file>` - Mock feature file (repeatable)
- `-p, --port <port>` - Port to listen on (default: 0 = dynamic)
- `-s, --ssl` - Enable HTTPS
- `-c, --cert <file>` - SSL certificate (PEM)
- `-k, --key <file>` - SSL private key (PEM)
- `-W, --watch` - Hot reload on file changes
- `--path-prefix <prefix>` - URL path prefix to strip

### Phase 6: karate.proceed() for Proxy Mode

Add to KarateJs:

```java
public HttpResponse proceed(String targetUrl) {
    HttpRequest req = MockRuntime.getCurrentRequest();
    if (targetUrl == null) {
        targetUrl = req.header("Host");
    }
    return httpClient.forward(req, targetUrl);
}
```

---

## Testing Strategy

### In-Memory Tests (Most Tests)

Use `InMemoryHttpClient` pattern for fast, isolated tests:

```java
@Test
void testMockHandler() {
    Feature mock = Feature.parse("""
        @mock
        Feature:
        Background:
          * def data = { name: 'test' }
        Scenario: pathMatches('/api/data')
          * def response = data
        """);

    MockHandler handler = new MockHandler(mock);
    HttpRequest request = HttpRequest.of("GET", "/api/data");
    HttpResponse response = handler.apply(request);

    assertEquals(200, response.status());
    assertEquals("{\"name\":\"test\"}", response.bodyString());
}
```

**Test classes:**
- `MockHandlerTest` - Core handler logic, matcher functions
- `MockConfigTest` - CORS, headers, afterScenario
- `MockStatefulTest` - Stateful mock scenarios
- `MockProxyTest` - karate.proceed() functionality

### Integration Tests (Real HTTP)

**Single shared server** for all HTTP integration tests:

```java
public class MockIntegrationTest {
    private static MockServer server;

    @BeforeAll
    static void startServer() {
        server = MockServer.feature("classpath:mock/integration.feature")
            .port(0)  // Dynamic port
            .start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Test
    void testParallelRequests() {
        // Test concurrent requests with callonce/callSingle
    }

    @Test
    void testStatefulCrud() {
        // Test full CRUD with persistent state
    }
}
```

### V1 Compatibility Tests

Run V1 mock-related tests via `run-v1-compat.sh`:

```bash
./etc/run-v1-compat.sh mock-crud.feature
./etc/run-v1-compat.sh mock-parallel.feature
```

### Manual CLI Testing

After implementing the CLI mock subcommand, manually verify with curl:

**1. Create test mock feature:**

```bash
cat > /tmp/test-mock.feature << 'EOF'
@mock
Feature: Test Mock

Background:
  * def users = {}
  * def counter = { value: 0 }

Scenario: pathMatches('/users/{id}') && methodIs('get')
  * def user = users[pathParams.id]
  * def response = user ? user : { error: 'not found' }
  * def responseStatus = user ? 200 : 404

Scenario: pathMatches('/users') && methodIs('post')
  * counter.value = counter.value + 1
  * def id = counter.value + ''
  * users[id] = request
  * users[id].id = id
  * def response = users[id]
  * def responseStatus = 201

Scenario: pathMatches('/users') && methodIs('get')
  * def response = karate.valuesOf(users)
  * def responseStatus = 200

Scenario:
  * def responseStatus = 404
  * def response = { error: 'not found' }
EOF
```

**2. Start mock server:**

```bash
# Basic HTTP
java -jar target/karate.jar mock -m /tmp/test-mock.feature -p 8080

# With HTTPS
java -jar target/karate.jar mock -m /tmp/test-mock.feature -p 8443 --ssl
```

**3. Test with curl:**

```bash
# Create user
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name": "John", "email": "john@example.com"}'
# Expected: 201, {"id": "1", "name": "John", "email": "john@example.com"}

# Get user
curl http://localhost:8080/users/1
# Expected: 200, {"id": "1", "name": "John", "email": "john@example.com"}

# Get non-existent user
curl http://localhost:8080/users/999
# Expected: 404, {"error": "not found"}

# List all users
curl http://localhost:8080/users
# Expected: 200, [{"id": "1", "name": "John", "email": "john@example.com"}]

# Create another user
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Jane"}'
# Expected: 201, {"id": "2", "name": "Jane"}

# Catch-all 404
curl http://localhost:8080/unknown
# Expected: 404, {"error": "not found"}
```

**4. Test HTTPS with curl:**

```bash
# Trust self-signed cert
curl -k https://localhost:8443/users
# or
curl --cacert /path/to/cert.pem https://localhost:8443/users
```

**5. Test hot reload (if implemented):**

```bash
# Start with watch mode
java -jar target/karate.jar mock -m /tmp/test-mock.feature -p 8080 -W

# In another terminal, modify the feature file
# Server should auto-reload
```

**CLI Test Checklist:**

| Test | Command | Expected |
|------|---------|----------|
| Basic GET | `curl localhost:8080/users/1` | 200 or 404 |
| POST JSON | `curl -X POST -d '{"name":"test"}' localhost:8080/users` | 201 with id |
| HTTPS | `curl -k https://localhost:8443/users` | 200 |
| Content-Type | `curl -H "Content-Type: application/xml" ...` | Proper handling |
| Query params | `curl "localhost:8080/search?q=test"` | Params accessible |
| Path params | `curl localhost:8080/users/123` | pathParams.id = "123" |
| CORS preflight | `curl -X OPTIONS localhost:8080/users` | CORS headers |
| Hot reload | Modify feature, re-request | Updated response |

---

## V1 Compatibility Notes

### Preserved Behavior
- Scenario matching: first match wins
- Background executes once on startup
- Same request/response variables
- Same matcher function signatures
- CORS and configure options

### V2 Improvements
- No "re-hydration" - objects pass directly via JsCallable
- Cleaner thread-local management
- MockServer as simple wrapper over HttpServer
- CLI under `mock` subcommand (breaking change)

### Not Implemented (Out of Scope)
- WebSocket support
- gRPC/Kafka mocking
- Browser/driver automation

---

## File Structure

```
karate-v2/
├── karate-core/src/main/java/io/karatelabs/core/
│   ├── MockServer.java          # Public API
│   ├── MockHandler.java         # Request routing
│   ├── MockRuntime.java         # Per-request execution
│   ├── MockConfig.java          # Configuration
│   └── ssl/
│       ├── SslConfig.java           # SSL configuration holder
│       ├── SslContextFactory.java   # SSLContext creation
│       └── CertificateGenerator.java # Self-signed cert generation
├── karate-core/src/test/java/io/karatelabs/core/
│   ├── mock/
│   │   ├── MockHandlerTest.java
│   │   ├── MockConfigTest.java
│   │   ├── MockStatefulTest.java
│   │   └── MockProxyTest.java
│   ├── ssl/
│   │   ├── SslConfigTest.java       # Config parsing tests
│   │   ├── SslContextFactoryTest.java
│   │   └── SslIntegrationTest.java  # HTTPS + mTLS tests
│   └── MockIntegrationTest.java  # Real HTTP tests
├── karate-core/src/test/resources/io/karatelabs/core/ssl/
│   ├── server-keystore-cert.pem # Test server cert (from V1)
│   ├── server-keystore-key.pem  # Test server key (from V1)
│   ├── server-keystore.p12      # Test server PKCS12 (from V1)
│   ├── mock-cert.pem            # Mock cert (from V1)
│   └── mock-key.pem             # Mock key (from V1)
└── docs/
    └── MOCKS.md                  # This file
```

---

## Implementation Sequence

1. **MockHandler + MockRuntime** - Core request handling
2. **Matcher functions** - pathMatches, methodIs, etc.
3. **Request/response variables** - Full set
4. **MockConfig** - CORS, headers, afterScenario
5. **MockServer** - Public API wrapper
6. **SslConfig + SslContextFactory** - SSL configuration and context creation
7. **CertificateGenerator** - Self-signed certificate generation
8. **HTTP Client SSL** - Configure ssl support in KarateHttpClient
9. **Mock Server SSL** - HTTPS support in HttpServer/MockServer
10. **karate.start()** - Test integration (with SSL options)
11. **CLI mock subcommand** - Command-line usage (with SSL flags)
12. **Manual CLI testing** - Verify with curl (see [Manual CLI Testing](#manual-cli-testing))
13. **Stateful tests** - Verify state persistence
14. **Integration tests** - Real HTTP with parallel
15. **SSL integration tests** - HTTPS and mTLS tests
16. **karate.proceed()** - Proxy mode
17. **V1 compat tests** - Run mock-related V1 tests

---

## Implementation Status

**Last updated:** 2025-12-21

### Completed Phases

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core MockHandler + MockConfig | ✅ Complete |
| 2 | Matcher functions (pathMatches, methodIs, typeContains, etc.) | ✅ Complete |
| 3 | Request/response variables | ✅ Complete |
| 4 | MockConfig (CORS, responseHeaders, afterScenario) | ✅ Complete |
| 5 | MockServer public API wrapper | ✅ Complete |
| 6 | SslConfig + SslContextFactory | ✅ Complete |
| 7 | CertificateGenerator (self-signed certs via Netty) | ✅ Complete |
| 8 | HTTP Client SSL | ✅ Complete (ApacheHttpClient already supports SSL) |
| 9 | Mock Server SSL | ✅ Complete (HttpServer + MockServer integration) |
| 10 | karate.start() integration | ✅ Complete |
| 11 | CLI mock subcommand | ✅ Complete |
| 12 | Manual CLI testing | ⏳ Pending |
| 13-14 | Stateful and integration tests | ✅ Complete (30 tests) |
| 15 | SSL integration tests | ✅ Complete (5 tests) |
| 16 | karate.proceed() proxy mode | ✅ Complete |
| 17 | V1 compat tests | ✅ Complete (5 end-to-end tests) |

### Files Created

**Production code:**
- `MockHandler.java` - Core request routing with matcher functions, uses KarateJs for karate.* access
- `MockServer.java` - Public API with Builder pattern, SSL support
- `MockConfig.java` - CORS, response headers, afterScenario configuration
- `SslConfig.java` - SSL configuration holder
- `SslContextFactory.java` - SSLContext creation from config
- `CertificateGenerator.java` - Self-signed certificate generation via Netty
- `MockCommand.java` - CLI subcommand for `karate mock`

**Modified files:**
- `KarateJs.java` - Added `karate.start()` and `karate.proceed()` methods
- `HttpServer.java` - Added SSL support with SslContext parameter
- `Main.java` - Added MockCommand to subcommands

**Tests (30 total):**
- `MockHandlerTest.java` - 11 unit tests for handler, matchers, CORS
- `MockServerTest.java` - 6 integration tests with real HTTP
- `MockServerSslTest.java` - 5 SSL integration tests (HTTPS, self-signed certs)
- `MockProxyTest.java` - 3 proxy mode tests (karate.proceed())
- `MockV1CompatTest.java` - 5 V1 compatibility end-to-end tests using Karate features

Note: Tests from karate-demo are a separate exercise and not covered here.

### Implementation Notes

1. **Thread-local pattern:** MockHandler uses `ThreadLocal<HttpRequest>` for matcher function access and `karate.proceed()`
2. **Netty SSL:** CertificateGenerator uses `io.netty.handler.ssl.util.SelfSignedCertificate`
3. **KarateJs integration:** MockHandler creates a KarateJs instance for scenario execution, providing access to all karate.* functions
4. **Console methods:** MockCommand uses `Console.info()`, `Console.pass()`, etc. (not Console.Color enum)
5. **HttpResponse pass-through:** When `karate.proceed()` is used, the response (HttpResponse) is passed through directly with status, headers, and body

### Known Limitations

1. **No watch mode:** `-W` flag not implemented yet
2. **Manual CLI testing pending:** Need to verify with curl as described in Manual CLI Testing section

---

## V2 Classes to Reference

Before implementing, study these existing V2 classes:

| Class | Location | What to look at |
|-------|----------|-----------------|
| `HttpServer` | `io.karatelabs.io.http.HttpServer` | Takes `Function<HttpRequest, HttpResponse>` - MockHandler implements this |
| `HttpRequest` | `io.karatelabs.io.http.HttpRequest` | Already has `pathMatches()`, `getBodyConverted()`, `processBody()` for multipart |
| `HttpResponse` | `io.karatelabs.io.http.HttpResponse` | `setBody()`, `setStatus()`, `setHeaders()` - implements `SimpleObject` for JS |
| `KarateJs` | `io.karatelabs.core.KarateJs` | Provider pattern (e.g., `setCallProvider`) - add `setStartProvider` for `karate.start()` |
| `ScenarioRuntime` | `io.karatelabs.core.ScenarioRuntime` | How scenarios execute with KarateJs, config evaluation |
| `StepExecutor` | `io.karatelabs.core.StepExecutor` | Keyword dispatch - reuse for mock scenario execution |
| `ServerTestHarness` | `io.karatelabs.io.http.ServerTestHarness` | Test pattern to follow for MockHandler tests |
| `InMemoryHttpClient` | `io.karatelabs.core.InMemoryHttpClient` (test) | In-memory testing pattern |

**Key patterns to follow:**

1. **Handler as Function:** `HttpServer.start(port, request -> response)` - MockHandler is the function
2. **SimpleObject for JS:** Both HttpRequest and HttpResponse implement `SimpleObject` for JS property access
3. **Provider pattern:** KarateJs uses providers (e.g., `callProvider`) to wire up runtime context - follow for `karate.start()`
4. **ReentrantLock:** See `ScenarioRuntime.executeCallSingle()` for thread-safe caching pattern

**Existing infrastructure to reuse:**

- `HttpRequest.pathMatches(pattern)` - Already implemented, extracts path params
- `HttpRequest.getBodyConverted()` - Parses JSON/XML automatically
- `HttpRequest.processBody()` - Handles multipart/form-urlencoded
- `Feature.read(resource)` - Parse feature files
- `engine.eval(expression)` - Evaluate JS expressions for scenario matching

---

## References

- V1 MockHandler: `/Users/peter/dev/zcode/karate/karate-core/src/main/java/com/intuit/karate/core/MockHandler.java`
- V1 Documentation: `/Users/peter/dev/zcode/karate/karate-netty/README.md`
- V1 SSL Resources: `/Users/peter/dev/zcode/karate/karate-demo/src/test/java/` (server-keystore.*, mock-*.pem)
- V2 HTTP Server: `io.karatelabs.io.http.HttpServer`
- V2 ServerTestHarness: `io.karatelabs.io.http.ServerTestHarness`
