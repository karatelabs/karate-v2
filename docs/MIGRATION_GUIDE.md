# Karate v1 to v2 Migration Guide

## What's New in Karate v2

Karate v2 is a **complete ground-up rewrite** with significant improvements across the board. Here are the highlights:

### Core Infrastructure

| Improvement | Description | Commit |
|-------------|-------------|--------|
| **Custom JS Engine** | Hand-rolled lexer/parser replacing JFlex, ES6+ support, 10x performance | [90d6e07](https://github.com/karatelabs/karate/commit/90d6e07) |
| **Apache HttpClient 5.6** | Modern HTTP client with Brotli compression, connection pooling | [1a35bcd](https://github.com/karatelabs/karate/commit/1a35bcd) |
| **Virtual Threads** | Java 21+ unlocks massive parallelism with virtual threads | - |
| **@lock Tag** | Scenario-level mutual exclusion for parallel safety | [a08337b](https://github.com/karatelabs/karate/commit/a08337b) |
| **@lock=\*** | Exclusive execution - scenario runs alone | [cd94b11](https://github.com/karatelabs/karate/commit/cd94b11) |

### Browser Automation

| Improvement | Description | Commit |
|-------------|-------------|--------|
| **CDP Driver Rewrite** | Complete reimplementation using Chrome DevTools Protocol directly | [68111e5](https://github.com/karatelabs/karate/commit/68111e5) |
| **PooledDriverProvider** | Automatic browser pooling for parallel UI automation | [b140436](https://github.com/karatelabs/karate/commit/b140436) |
| **Auto-wait** | Automatic waiting before element operations reduces flaky tests | [67e4c2d](https://github.com/karatelabs/karate/commit/67e4c2d) |

### Reporting

| Improvement | Description | Commit |
|-------------|-------------|--------|
| **JSONL Streaming** | Memory-efficient `karate-results.jsonl` format | [f4240a2](https://github.com/karatelabs/karate/commit/f4240a2) |
| **Smaller HTML Reports** | Alpine.js client-side rendering, Bootstrap 5.3 | [3b965b6](https://github.com/karatelabs/karate/commit/3b965b6) |
| **Dark Mode** | Native dark mode toggle in HTML reports | [3b965b6](https://github.com/karatelabs/karate/commit/3b965b6) |
| **Tag Filtering** | Interactive tag filtering on summary page | [3b965b6](https://github.com/karatelabs/karate/commit/3b965b6) |
| **Timeline View** | Consistent lane pool for thread names | [7f4c8c9](https://github.com/karatelabs/karate/commit/7f4c8c9) |

### Test Framework Integration

| Improvement | Description | Commit |
|-------------|-------------|--------|
| **JUnit 6** | Streaming dynamic test generation via `@TestFactory` | [a794b02](https://github.com/karatelabs/karate/commit/a794b02) |
| **Gatling 3.14.9** | Re-implemented with pure Java + thin Scala bridge | [32f8b00](https://github.com/karatelabs/karate/commit/32f8b00) |

### Event System & Logging

| Improvement | Description | Commit |
|-------------|-------------|--------|
| **Unified Event System** | Single `RunListener` API replaces `RuntimeHook` and `ResultListener` - see [EVENTS.md](./EVENTS.md) | [f4240a2](https://github.com/karatelabs/karate/commit/f4240a2) |
| **HTTP Events** | `HTTP_ENTER`/`HTTP_EXIT` events for request interception and commercial extensions | [1333f92](https://github.com/karatelabs/karate/commit/1333f92) |
| **Unified Namespaces** | `karate.runtime`, `karate.http`, `karate.mock`, `karate.scenario`, `karate.console` | [ef5d9bd](https://github.com/karatelabs/karate/commit/ef5d9bd) |
| **Dual-Mode Logging** | Thread-local capture for reports + SLF4J cascade for real-time monitoring | [ef5d9bd](https://github.com/karatelabs/karate/commit/ef5d9bd) |
| **Runtime Log Level** | `--runtime-log-level` CLI flag for dynamic control | [910eca4](https://github.com/karatelabs/karate/commit/910eca4) |

### New APIs

| API | Description | Commit |
|-----|-------------|--------|
| **karate.faker.\*** | Test data generation: `firstName()`, `email()`, `randomInt()`, etc. | [245c540](https://github.com/karatelabs/karate/commit/245c540) |
| **karate.expect()** | Chai-style BDD assertions - easier migration from Postman and JS testing frameworks | [ad2f475](https://github.com/karatelabs/karate/commit/ad2f475) |
| **karate.uuid()** | Generate random UUIDs | [cb516d4](https://github.com/karatelabs/karate/commit/cb516d4) |
| **configure auth** | Declarative HTTP authentication (Basic, Bearer, OAuth2) | [1a06c64](https://github.com/karatelabs/karate/commit/1a06c64) |

#### Configure Auth Details

Centralized authentication configuration supporting multiple auth types:

```javascript
// Basic Auth
configure auth = { type: 'basic', username: 'user', password: 'pass' }

// Bearer Token
configure auth = { type: 'bearer', token: 'your-token' }

// OAuth2 Client Credentials
configure auth = { type: 'oauth2', tokenUrl: 'https://auth.example.com/token', clientId: '...', clientSecret: '...' }

// OAuth2 Authorization Code (PKCE)
configure auth = { type: 'oauth2', flow: 'authorization_code', authUrl: '...', tokenUrl: '...', clientId: '...' }
```

- Automatic token refresh for OAuth2
- Auth configuration inherited by called features

#### karate.expect() - Chai-style Assertions

If you're migrating from Postman or other frameworks using Chai-style JavaScript syntax, `karate.expect()` provides a familiar API:

```javascript
karate.expect(response.name).to.equal('John')
karate.expect(response.age).to.be.above(18)
karate.expect(response).to.have.property('email')
karate.expect(response.tags).to.include('active')
karate.expect(response.status).to.not.equal('deleted')
```

### V1 Compatibility

| Improvement | Description | Commit |
|-------------|-------------|--------|
| **Compatibility Shims** | `com.intuit.karate` package delegates to v2 | [fefb91f](https://github.com/karatelabs/karate/commit/fefb91f) |
| **Drop-in Migration** | Most v1 code works with just dependency update | - |

---

## Overview

Karate v2 includes **backward compatibility shims** that allow most v1 code to work with minimal changes. For most users, the only change required is updating the Maven dependency.

## Quick Start

### Step 1: Update Maven Dependencies

```xml
<!-- v1 -->
<dependency>
    <groupId>io.karatelabs</groupId>
    <artifactId>karate-junit5</artifactId>
    <version>1.5.2</version>
    <scope>test</scope>
</dependency>

<!-- v2 -->
<dependency>
    <groupId>io.karatelabs</groupId>
    <artifactId>karate-junit6</artifactId>
    <version>2.0.0.RC1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.1</version>
    <scope>test</scope>
</dependency>
```

> **Note:** Unlike `karate-junit5` which bundled JUnit, `karate-junit6` declares JUnit as a `provided` dependency, giving you control over the JUnit version. You must add `junit-jupiter` explicitly.

> **Reference:** See the [karate-demo migration commit](https://github.com/karatelabs/karate/commit/c8fca97ce) for a complete example of dependency changes.

### Step 2: Update Java Version

Karate v2 requires **Java 21+** for virtual threads support.

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>
```

That's it for most projects. Run your tests and they should work.

---

## V1 Compatibility Shims

The following v1 APIs work without code changes via deprecated shims:

| v1 Class | Status |
|----------|--------|
| `com.intuit.karate.Runner` | Works - delegates to v2 |
| `com.intuit.karate.Results` | Works - wraps v2 SuiteResult |
| `com.intuit.karate.core.MockServer` | Works - delegates to v2 |
| `com.intuit.karate.junit5.Karate` | Works - delegates to v2 |

### Example: Runner API (no changes needed)

```java
// This v1 code works in v2 without modification
import com.intuit.karate.Results;
import com.intuit.karate.Runner;

Results results = Runner.path("classpath:features")
    .tags("~@ignore")
    .parallel(5);
assertTrue(results.getFailCount() == 0, results.getErrorMessages());
```

### Example: MockServer API (no changes needed)

```java
// This v1 code works in v2 without modification
import com.intuit.karate.core.MockServer;

MockServer server = MockServer
    .feature("classpath:mock.feature")
    .arg("key", "value")
    .http(0).build();
```

## Deprecated Configure Options

These configure options now produce a warning and have no effect:

- `logPrettyRequest` / `logPrettyResponse`
- `printEnabled`
- `lowerCaseResponseHeaders`
- `logModifier`

Your tests will still pass - these are just no-ops now.

---

## Feature File Compatibility

Most feature files work unchanged. The only known difference:

- **Cookie domain assertions**: If testing cookie domains, note that RFC 6265 compliance means leading dots are stripped (`.example.com` → `example.com`)

---

## Browser Automation (UI Tests)

V2 uses a new CDP-based driver implementation with simplified configuration.

### Driver Configuration

```javascript
// karate-config.js - minimal config
function fn() {
  karate.configure('driver', { type: 'chrome', headless: false });
  return { serverUrl: 'http://localhost:8080' };
}
```

**Key differences from v1:**
- `showDriverLog` has no effect (TODO)
- Only `chrome` type is supported via CDP (chromedriver, geckodriver, safaridriver not yet available)

### Gherkin Syntax (unchanged)

All v1 driver keywords work the same way:

```gherkin
* driver serverUrl + '/login'
* input('#username', 'admin')
* click('button[type=submit]')
* waitFor('#dashboard')
* match driver.title == 'Welcome'
```

### Driver Inheritance in Called Features

V2 preserves v1 behavior for driver inheritance:
- Config is inherited by called features
- Driver instance is shared with called features (when caller has driver)
- Driver is not closed until the top-level scenario exits

### Shared vs Isolated Scope (Unchanged from V1)

Call scope determines what propagates back to the caller:

| Call Style | Scope | Variables | Config | Cookies |
|------------|-------|-----------|--------|---------|
| `call read('f.feature')` | Shared | ✅ Propagate | ✅ Propagate | ✅ Propagate |
| `def result = call read('f.feature')` | Isolated | ❌ In `result` | ❌ | ❌ |

This is unchanged from V1.

### Browser Pooling (Default Behavior)

V2 automatically pools browser instances using `PooledDriverProvider`. This is the default - no configuration needed:

```java
Runner.path("features/")
    .parallel(4);  // Pool of 4 drivers auto-created
```

Benefits:
- Browser instances are reused across scenarios
- Pool size auto-scales to match parallelism
- Clean state reset between scenarios

### V1-Style Driver Propagation with `scope: 'caller'`

In V1, if a called feature (shared scope) initialized a driver, it would propagate back to the caller. In V2 with pooling, this doesn't happen by default - the driver is released back to the pool when the called scenario ends.

To restore V1 behavior for specific features, use `scope: 'caller'` in the driver config. This only affects driver propagation - other variables/config still follow the shared vs isolated scope rules above.

```gherkin
# init-driver.feature - called feature that inits driver
@ignore
Feature: Initialize driver with caller scope

Background:
* def driverWithScope = karate.merge(driverConfig, { scope: 'caller' })
* configure driver = driverWithScope

Scenario: Init driver
* driver serverUrl + '/page.html'  # driver propagates to caller
```

```gherkin
# main.feature - caller receives the driver
Scenario:
* call read('init-driver.feature')
* match driver.title == 'Page'  # works - driver propagated from callee
```

**When to use `scope: 'caller'`:**
- Migrating V1 tests where called features initialize the driver
- Reusable "driver setup" features that callers depend on

See [DRIVER.md](./DRIVER.md) for detailed DriverProvider documentation.

### Migration Checklist for Driver Tests

- [ ] `showDriverLog` has no effect (TODO)
- [ ] Comment out non-Chrome driver types in scenario outlines
- [ ] Consider migrating to `DriverProvider` for parallel execution

---

## Migration Checklist

- [ ] Update `karate-junit5` → `karate-junit6` dependency
- [ ] Update Java version to 21+
- [ ] Replace `JsonUtils` with `Json` class (if used)
- [ ] Remove code using `HttpLogModifier`, `WebSocketClient`, or Driver Java APIs (if used)
- [ ] Update cookie domain assertions if needed

---

## Gradual Migration to v2 APIs

Each shim provides a `toV2*()` method if you want to migrate incrementally:

```java
// Get underlying v2 Builder
io.karatelabs.core.Runner.Builder v2Builder = v1Builder.toV2Builder();

// Get underlying v2 MockServer
io.karatelabs.core.MockServer v2Server = v1Server.toV2MockServer();

// Get underlying v2 SuiteResult
io.karatelabs.core.SuiteResult v2Results = v1Results.toSuiteResult();
```

---

## Getting Help

- GitHub Issues: https://github.com/karatelabs/karate/issues
- Documentation: https://karatelabs.io/docs

---

## Appendix: Migration Reference Commits

We've migrated two projects as reference implementations. These commits show all the changes needed:

### karate-demo Migration

**Commit:** [c8fca97ce](https://github.com/karatelabs/karate/commit/c8fca97ce)

This involved additional infrastructure changes beyond what typical end-users need:

- Spring Boot 2.x → 3.x upgrade (required for Java 21)
- `javax.*` → `jakarta.*` servlet imports
- Spring Security 5 → 6 configuration style
- Cookie domain normalization for RFC 6265 compliance

### karate-e2e-tests Migration

**Commit:** [7ffe47509](https://github.com/karatelabs/karate/commit/7ffe47509)

This shows a simpler migration focused on test dependencies and runner changes.
