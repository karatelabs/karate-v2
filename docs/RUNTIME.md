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
| `JunitXmlWriter` | `io.karatelabs.core.JunitXmlWriter` | JUnit XML report generation for CI/CD |
| `ResultListener` | `io.karatelabs.core.ResultListener` | Interface for streaming test results |

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
    .outputJunitXml(true)  // generate JUnit XML for CI
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
- JUnit XML report (`karate-junit.xml`) for CI/CD integration
- Console output with ANSI colors (`io.karatelabs.core.Console`)

### Logging

Package `io.karatelabs.log`:
- `LogContext` - Thread-local collector for report output
- `JvmLogger` - Infrastructure logging (System.err or SLF4J)

---

## Not Yet Implemented

### Priority 1: Result Streaming ✅ IMPLEMENTED

Foundation for HTML reports and external integrations.

```java
public interface ResultListener {
    default void onSuiteStart(Suite suite) {}
    default void onSuiteEnd(SuiteResult result) {}
    default void onFeatureStart(Feature feature) {}
    default void onFeatureEnd(FeatureResult result) {}
    default void onScenarioStart(Scenario scenario) {}
    default void onScenarioEnd(ScenarioResult result) {}
}

// Usage
Runner.path("features/")
    .resultListener(new HtmlReportListener())
    .resultListener(new AllureListener())
    .parallel(10);
```

**Design notes:**
- Scenario is the smallest unit of granularity (no step-level events)
- Unlike `RuntimeHook`, `ResultListener` is purely observational and cannot abort execution
- Multiple listeners can be registered via `Runner.Builder.resultListener()`

**Built-in listeners (TODO):**
- `HtmlReportListener` - generates HTML reports
- `TelemetryListener` - sends daily ping

---

### Priority 2: HTML Reports

*See detailed spec below in HTML Reports section.*

---

### Priority 3: Cucumber JSON Format

*See detailed spec below.*

---

### Priority 4: Feature File Discovery

Current status:
- File system directory scanning ✓
- Single feature file loading ✓
- **TODO:** Classpath scanning (`classpath:features/`)
- **TODO:** JAR scanning

```java
// File system (works today)
Runner.path("src/test/resources/features/")

// Classpath (TODO)
Runner.path("classpath:features/")
```

---

### Priority 5: CLI JSON Configuration

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

### Priority 6: karate-base.js

Shared config from classpath (e.g., company JAR):

```
karate-base.js (from JAR)
  ↓ overridden by
karate-config.js (project)
  ↓ overridden by
karate-config-dev.js (env-specific)
```

---

### HTML Reports

Comprehensive HTML reporting system with pluggable templates.

#### Report Type

Rich interactive HTML report with Bootstrap 5 + Alpine.js (no jQuery).

#### Core Features

**Report Views (same as v1):**
- **Summary** - Overview of all features with pass/fail counts, sortable table
- **Feature** - Individual feature with scenarios, steps, logs, embeds
- **Tags** - Cross-feature tag analysis matrix
- **Timeline** - Gantt-style execution visualization

**Report Aggregation:**
```java
// Aggregate reports from multiple JSON files (new in v2)
HtmlReport.aggregate()
    .json("target/run1/karate-summary.json")
    .json("target/run2/karate-summary.json")
    .outputDir("target/combined-report")
    .generate();
```

**CLI:**
```bash
karate report --aggregate target/run1,target/run2 --output target/combined
```

#### Template Architecture

Leverages v2 templating system (`io.karatelabs.markup`):

```
ReportTemplateResolver
    ↓
1. Check user-provided templates (filesystem)
2. Check classpath (commercial extensions in JAR)
3. Fall back to built-in defaults
```

**Template Resolution Order:**
```
user templates (--templates dir)
  ↓ fallback
classpath:karate-report-templates/  (commercial JAR)
  ↓ fallback
classpath:io/karatelabs/core/report/  (built-in)
```

**Customization:**
```java
Runner.path("features/")
    .outputHtmlReport(true)
    .reportTemplates("src/custom-templates/")  // user templates
    .parallel(5);
```

#### Full Report (Bootstrap 5 + Alpine.js)

**Stack:**
- Bootstrap 5 (CSS + minimal JS for components)
- Alpine.js (replaces jQuery for interactivity)
- No jQuery dependency

**Features:**
- Responsive layout with left navigation
- Collapsible step details with log output
- Embedded screenshots
- Sortable/filterable tables
- Timeline visualization using vis.js (690KB, bundled - consider lighter alternatives)

**Static Resources:**
- Bundled in JAR, copied to `res/` folder on generation (same as v1)
- Allows offline viewing of reports

#### Pluggability Design

**Interface:**
```java
public interface ReportGenerator {
    void generate(SuiteResult result, Path outputDir);
}

public interface ReportTemplateResolver {
    Resource resolveTemplate(String templateName);
    Resource resolveStatic(String resourcePath);
}
```

**Commercial Extension Example:**
```java
// In commercial JAR on classpath
public class EnterpriseReportGenerator implements ReportGenerator {
    // Custom branding, additional views, etc.
}

// Registered via ServiceLoader or configuration
META-INF/services/io.karatelabs.core.ReportGenerator
```

**Variable Injection:**
```java
// Custom variables for templates
Runner.path("features/")
    .reportVariable("company", "Acme Corp")
    .reportVariable("logo", "classpath:branding/logo.png")
    .parallel(5);
```

#### Runner API

```java
SuiteResult result = Runner.path("src/test/resources")
    .outputHtmlReport(true)           // default: true
    .reportTemplates("custom/")       // custom template dir
    .reportVariable("key", value)     // template variables
    .parallel(5);
```

**CLI opt-out:**
```bash
karate --no-html-report -T 5 src/test/features
```

#### Output Structure

```
target/karate-reports/
├── karate-summary.json           # JSON data
├── karate-junit.xml              # JUnit XML
├── index.html                    # Report entry point
├── karate-summary.html           # Summary view
├── karate-tags.html              # Tags view
├── karate-timeline.html          # Timeline view
├── features/
│   ├── users.html                # Per-feature reports
│   └── orders.html
└── res/
    ├── bootstrap.min.css
    ├── alpine.min.js
    └── karate-report.css
```

---

### Cucumber JSON Format

Standard Cucumber JSON for third-party tool integration (Allure, ReportPortal, etc.).

```java
Runner.path("features/")
    .outputCucumberJson(true)     // generates cucumber.json
    .parallel(5);
```

**Output:** `target/karate-reports/cucumber.json`

---

## Future Phase

Lower priority features for later implementation.

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

### Telemetry

Anonymous usage telemetry to understand adoption. Integrated into core runtime (not HTML reports) for visibility into CI/CD and headless usage.

**Design:**
- Sent from `Suite.run()` completion, not from report viewing
- **Once per day max** - prevents excessive pings from frequent test runs
- Non-blocking - async HTTP POST, failures silently ignored
- Minimal payload - no PII, no test content

**Storage (`~/.karate/`):**
```
~/.karate/
├── uuid.txt           # Persistent user UUID (existing v1 approach)
└── telemetry.json     # Last ping timestamp + daily state
```

**telemetry.json:**
```json
{
  "lastPing": "2025-12-16T10:30:00Z",
  "dailySent": true
}
```

**Payload (minimal):**
```json
{
  "uuid": "abc-123-...",
  "version": "2.0.0",
  "os": "darwin",
  "java": "21",
  "features": 15,
  "scenarios": 42,
  "passed": true,
  "ci": true,
  "meta": "..."
}
```

**CI Detection:**
- Check common env vars: `CI`, `JENKINS_URL`, `GITHUB_ACTIONS`, `GITLAB_CI`, `TRAVIS`, etc.

**Opt-out:**
```bash
export KARATE_TELEMETRY=false
```

**Implementation:**
```java
public class Telemetry {
    private static final Path KARATE_DIR = Path.of(System.getProperty("user.home"), ".karate");
    private static final Path UUID_FILE = KARATE_DIR.resolve("uuid.txt");
    private static final Path STATE_FILE = KARATE_DIR.resolve("telemetry.json");

    public static void ping(SuiteResult result) {
        if (!isEnabled()) return;
        if (alreadySentToday()) return;

        // Async non-blocking POST
        CompletableFuture.runAsync(() -> {
            try {
                sendPing(buildPayload(result));
                updateLastPing();
            } catch (Exception ignored) { }
        });
    }

    private static boolean isEnabled() {
        String env = System.getenv("KARATE_TELEMETRY");
        return env == null || !"false".equalsIgnoreCase(env.trim());
    }

    private static boolean alreadySentToday() {
        // Check STATE_FILE for today's date
    }
}
```

**Called from Suite:**
```java
public SuiteResult run() {
    // ... execution ...
    Telemetry.ping(result);  // async, non-blocking
    return result;
}
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
| `ResultListenerTest` | Result streaming |
| `RunnerTest` | Runner API |
| `JunitXmlWriterTest` | JUnit XML report generation |

Test utilities in `TestUtils.java` and `InMemoryHttpClient.java`.
