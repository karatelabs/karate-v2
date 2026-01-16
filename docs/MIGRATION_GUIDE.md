# Karate v1 to v2 Migration Guide

## Overview

Karate v2 includes **backward compatibility shims** that allow most v1 code to work with minimal changes. For most users, the only change required is updating the Maven dependency.

## Quick Start

### Step 1: Update Maven Dependency

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
```

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
- `userDataDir` is no longer required - v2 auto-creates a temp sandbox
- `showDriverLog` is removed (logs are handled differently)
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

- [ ] Remove `userDataDir` from driver config (optional, now auto-defaulted)
- [ ] Remove `showDriverLog` from driver config (no longer used)
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

## Appendix: karate-demo Migration Reference

The `karate-demo` project was migrated as a reference implementation. This involved additional infrastructure changes beyond what typical end-users need:

- Spring Boot 2.x → 3.x upgrade (required for Java 21)
- `javax.*` → `jakarta.*` servlet imports
- Spring Security 5 → 6 configuration style
- Cookie domain normalization for RFC 6265 compliance

For the complete diff, see: <!-- TODO: Add git diff link -->
