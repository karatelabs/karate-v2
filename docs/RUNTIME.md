# Karate v2 Runtime Design

This document describes the runtime architecture for Karate v2.

> See also: [GHERKIN-PARSER-REWRITE.md](./GHERKIN-PARSER-REWRITE.md) | [JS-JAVA-INTEROP.md](./JS-JAVA-INTEROP.md) | [PRINCIPLES.md](./PRINCIPLES.md)

---

## Architecture Overview

```
Suite → FeatureRuntime → ScenarioRuntime → StepExecutor
                                               ↓
                              ┌────────────────┼────────────────┐
                              ▼                ▼                ▼
                         Match Engine    Http Client    Other Actions
```

**Key design principle:** AST-based keyword dispatch (no regex). The GherkinParser extracts `step.keyword` and `step.text`, and StepExecutor uses a clean `switch` statement.

---

## Implemented Features

### Core Runtime Classes

| Class | Location | Description |
|-------|----------|-------------|
| `Suite` | `io.karatelabs.core.Suite` | Top-level orchestrator, config loading, parallel execution |
| `FeatureRuntime` | `io.karatelabs.core.FeatureRuntime` | Feature execution, scenario iteration, caching |
| `ScenarioRuntime` | `io.karatelabs.core.ScenarioRuntime` | Scenario execution, wraps KarateJs, variable scope |
| `StepExecutor` | `io.karatelabs.core.StepExecutor` | Keyword-based step dispatch |
| `KarateJs` | `io.karatelabs.core.KarateJs` | JS engine, HTTP client, karate.* bridge |

### Step Keywords (All Implemented)

**Variable Assignment:** `def`, `set`, `remove`, `text`, `json`, `xml`, `copy`, `table`

**Assertions:** `match` (all operators including `each`), `assert`, `print`

**HTTP:** `url`, `path`, `param`, `params`, `header`, `headers`, `cookie`, `cookies`, `form field`, `form fields`, `request`, `method`, `status`, `multipart file`, `multipart field`, `multipart fields`, `multipart files`, `multipart entity`

**Control Flow:** `call`, `callonce`, `eval`

**Config:** `configure` (ssl, proxy, readTimeout, connectTimeout, followRedirects, headers, charset)

### Result Classes

| Class | Description |
|-------|-------------|
| `StepResult` | Pass/fail/skip with timing and error |
| `ScenarioResult` | Aggregated step results |
| `FeatureResult` | Aggregated scenario results with `printSummary()` |
| `SuiteResult` | Full suite metrics with `printSummary()` |

### Runner API

```java
SuiteResult result = Runner.path("src/test/resources")
    .tags("@smoke", "~@slow")
    .karateEnv("dev")
    .parallel(5);
```

See `io.karatelabs.core.Runner` for full API.

### CLI

```bash
java -jar karate.jar -t @smoke -e dev -T 5 src/test/resources
```

See `io.karatelabs.core.Main` (PicoCLI-based).

### Parallel Execution

Uses Java 21+ virtual threads. Basic parallel execution is implemented in `Suite.runParallel()`.

### Configuration Loading

- `karate-config.js` from configDir
- `karate-config-{env}.js` for environment-specific config
- Config values available as variables in scenarios

### Reports

- Karate JSON report (`karate-summary.json`)
- Console output with ANSI colors (`io.karatelabs.core.Console`)

### Logging

Package `io.karatelabs.log`:
- `LogContext` - Thread-local collector for report output
- `JvmLogger` - Infrastructure logging (System.err or SLF4J)

---

## Not Yet Implemented

### Lock System (`@lock=<name>`)

For mutual exclusion when scenarios cannot run in parallel due to shared resources.

```gherkin
@lock=database
Feature: User tests
  Scenario: Create user      # holds "database" lock
  Scenario: Delete user      # waits for "database" lock

@lock=*
Scenario: Restart server     # runs exclusively (waits for all, blocks all)
```

**Implementation:**

```java
public class LockRegistry {
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();
    private final AtomicInteger activeScenarios = new AtomicInteger(0);

    public void acquireLock(String lockName) {
        if ("*".equals(lockName)) {
            acquireExclusive();  // Write lock, wait for all active to complete
        } else {
            globalLock.readLock().lock();
            locks.computeIfAbsent(lockName, k -> new ReentrantLock()).lock();
        }
        activeScenarios.incrementAndGet();
    }

    public void releaseLock(String lockName) {
        activeScenarios.decrementAndGet();
        if ("*".equals(lockName)) {
            globalLock.writeLock().unlock();
        } else {
            locks.get(lockName).unlock();
            globalLock.readLock().unlock();
        }
    }
}
```

| Tag | Scope | Behavior |
|-----|-------|----------|
| `@lock=foo` | Cross-feature | Mutual exclusion with other `@lock=foo` |
| `@lock=*` | Global | Waits for all, runs exclusively |
| `@parallel=false` | Intra-feature | Sequential within feature |

---

### Retry System (`@retry`)

Failed scenarios can be automatically retried at end of suite.

```gherkin
@retry
Scenario: Flaky test
  * url 'https://flaky-api.example.com'
  * method get
  * status 200
```

**Flow:**
1. Suite executes all scenarios in parallel
2. Collect failed scenarios with `@retry` tag
3. Re-run failures (fresh execution from scratch)
4. Final results use best outcome (pass > fail)

**Failed scenarios file (`rerun.txt`):**
```
src/test/features/users.feature:25
src/test/features/orders.feature:42
```

**CLI usage:**
```bash
karate --rerun target/karate-reports/rerun.txt
```

---

### Result Streaming

For real-time reporting to external systems.

```java
public interface ResultListener {
    void onSuiteStart(Suite suite);
    void onSuiteEnd(SuiteResult result);
    void onFeatureStart(Feature feature);
    void onFeatureEnd(FeatureResult result);
    void onScenarioStart(Scenario scenario);
    void onScenarioEnd(ScenarioResult result);
    void onStepStart(Step step);
    void onStepEnd(StepResult result);
}

// Usage
Runner.path("features/")
    .resultListener(new ReportPortalListener(client))
    .resultListener(new AllureListener())
    .parallel(10);
```

---

### Multiple Suite Execution

```java
List<SuiteResult> results = Runner.suites()
    .add(Runner.path("src/test/api/").parallel(10))
    .add(Runner.path("src/test/ui/").parallel(2))
    .parallel(2)  // 2 suites in parallel
    .run();
```

**Environment isolation:** Each suite gets its own snapshot of `System.getenv()` at creation time to prevent race conditions.

---

### Feature File Discovery

Current status:
- File system directory scanning
- Single feature file loading
- **TODO:** Classpath scanning (`classpath:features/`)
- **TODO:** JAR scanning

```java
// File system (works today)
Runner.path("src/test/resources/features/")

// Classpath (TODO)
Runner.path("classpath:features/")
```

---

### Additional Reports

- **JUnit XML** - for CI integration (TODO)
- **HTML** - standalone report (TODO)

---

### CLI JSON Configuration

```json
{
  "paths": ["src/test/features/"],
  "tags": ["@smoke"],
  "env": "dev",
  "threads": 5,
  "output": { "dir": "target/reports" }
}
```

```bash
karate --config karate.json
```

---

### karate-base.js

Shared config from classpath (e.g., company JAR):

```
karate-base.js (from JAR)
  ↓ overridden by
karate-config.js (project)
  ↓ overridden by
karate-config-dev.js (env-specific)
```

---

## Test Coverage

Tests are in `karate-core/src/test/java/io/karatelabs/core/`:

| Test Class | Coverage |
|------------|----------|
| `step/DefStepTest` | Variable assignment |
| `step/MatchStepTest` | Match assertions |
| `step/HttpStepTest` | HTTP operations |
| `step/MultipartStepTest` | Multipart uploads |
| `step/PrintAssertTest` | Print and assert |
| `ScenarioOutlineTest` | Outline expansion |
| `DynamicOutlineTest` | Dynamic scenarios |
| `BackgroundTest` | Background steps |
| `CallFeatureTest` | Feature calling |
| `ConfigTest` | Config loading |
| `RuntimeHookTest` | Hooks |
| `RunnerTest` | Runner API |

Test utilities in `TestUtils.java` and `InMemoryHttpClient.java`.
