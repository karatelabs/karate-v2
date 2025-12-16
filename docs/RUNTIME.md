# Karate v2 Runtime Design

This document describes the runtime architecture for Karate v2.

> See also: [PARSER.md](./PARSER.md) | [JS_ENGINE.md](./JS_ENGINE.md) | [PRINCIPLES.md](./PRINCIPLES.md) | [ROADMAP.md](./ROADMAP.md)

---

## Implementation Guidance

**Referencing v1 code:** While v2 is a fresh implementation, the Karate v1 source (`/karate/karate-core`) should be referenced to ensure format compatibility and preserve capabilities. Key areas:
- Report JSON schemas (preserve field names for tooling compatibility)
- Cucumber JSON format (must match spec for Allure, ReportPortal, etc.)
- CLI options (maintain familiar interface)

**v1 source location:** `/Users/peter/dev/zcode/karate/karate-core/src/main/java/com/intuit/karate/`

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
| `CucumberJsonWriter` | `io.karatelabs.core.CucumberJsonWriter` | Cucumber JSON report for third-party tools |
| `HtmlReportListener` | `io.karatelabs.core.HtmlReportListener` | Async HTML report generation (default) |
| `HtmlReportWriter` | `io.karatelabs.core.HtmlReportWriter` | HTML report generation with inlined JSON |
| `NdjsonReportListener` | `io.karatelabs.core.NdjsonReportListener` | NDJSON streaming (opt-in via `.outputNdjson(true)`) |
| `HtmlReport` | `io.karatelabs.core.HtmlReport` | Report aggregation API |
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
    .outputJunitXml(true)       // generate JUnit XML for CI
    .outputCucumberJson(true)   // generate Cucumber JSON for Allure, etc.
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
- Cucumber JSON report (`cucumber.json`) for third-party tools (Allure, ReportPortal, etc.)
- Console output with ANSI colors (`io.karatelabs.core.Console`)

#### Report Architecture

**Memory-efficient HTML report generation:**
The `HtmlReportListener` writes feature HTML files asynchronously as each feature completes, using a single-thread executor. Only small summary data is kept in memory. At suite end, summary pages are generated from the in-memory summaries.

```
Test Execution
    ↓
HtmlReportListener (default)
    ├── onFeatureEnd() → Queue feature HTML to executor (async)
    │                  → Collect small summary info in memory
    └── onSuiteEnd()   → Write karate-summary.html, tags, timeline
                       → Wait for executor to finish
                       → Copy static resources
```

**Async report generation:**
Report generation runs on a single-thread executor to avoid blocking test execution. Feature HTML is written as features complete, making partial results available during execution.

```java
// In HtmlReportListener
@Override
public void onFeatureEnd(FeatureResult result) {
    summaries.add(new FeatureSummary(result));  // small in-memory data
    executor.submit(() -> writeFeatureHtml(result));  // async
}

@Override
public void onSuiteEnd(SuiteResult result) {
    writeSummaryPages(summaries, result);  // summary, tags, timeline
    executor.shutdown();
    executor.awaitTermination(60, TimeUnit.SECONDS);
}
```

**Report aggregation across runs:**
The JSON-based approach enables merging reports from different test runs:

```java
HtmlReport.aggregate()
    .json("target/run1/karate-summary.json")
    .json("target/run2/karate-summary.json")
    .outputDir("target/combined-report")
    .generate();
```

**Plugin system for extended reports (commercial):**
A plugin interface will allow collecting additional data during test execution, such as HTTP request/response details for API coverage reports:

```java
public interface ReportPlugin {
    void onHttpRequest(HttpRequest request, HttpResponse response, ScenarioRuntime runtime);
    void generateReport(Path outputDir);
}

// Usage - cross-reference HTTP calls to tests for OpenAPI coverage
Runner.path("features/")
    .reportPlugin(new ApiCoveragePlugin("openapi.yaml"))
    .parallel(5);
```

**Karate JSON Schema (v2 - simplified, breaking change from v1):**

The v2 JSON uses **step-level source** - each step carries its own text with indentation preserved. This handles:
- **Loops** - Same step executes multiple times (each execution is a separate entry)
- **Scenario Outline** - Same source lines in different scenario instances
- **Called features** - Steps from other files appear nested
- **Embedded JS** - JS execution spans files/contexts

**Suite summary (`karate-summary.json`):**

```json
{
  "version": "2.0.0",
  "env": "dev",
  "threads": 5,
  "featuresPassed": 10,
  "featuresFailed": 2,
  "featuresSkipped": 0,
  "scenariosPassed": 45,
  "scenariosFailed": 3,
  "elapsedTime": 12345.0,
  "totalTime": 54321.0,
  "efficiency": 0.85,
  "resultDate": "2025-12-16T10:30:00",
  "features": [
    {
      "name": "User Management",
      "relativePath": "features/users.feature",
      "failed": false,
      "passedCount": 5,
      "failedCount": 0,
      "durationMillis": 1234.5
    }
  ]
}
```

**Feature detail (inlined in HTML):**

```json
{
  "name": "User Management",
  "relativePath": "features/users.feature",
  "scenarios": [
    {
      "name": "Create user",
      "tags": ["@smoke"],
      "failed": false,
      "ms": 50,
      "steps": [
        { "text": "    * url baseUrl", "status": "passed", "ms": 2 },
        { "text": "    # create payload\n    * def user = { name: 'John' }", "status": "passed", "ms": 1 },
        { "text": "    * call read('helper.feature')", "status": "passed", "ms": 100,
          "called": [
            { "text": "  * print 'from helper'", "status": "passed", "ms": 1 }
          ]
        },
        { "text": "    * method post", "status": "passed", "ms": 45, "logs": ["POST http://...", "< 201"] },
        { "text": "    * status 201", "status": "passed", "ms": 0 }
      ]
    }
  ]
}
```

**Design rationale:**
- **Step text is self-contained** - Includes indentation, preceding comments, blank lines
- **No source array** - Execution may span multiple files (called features, JS)
- **No line-number mapping** - Loops/outlines execute same line multiple times
- **Nested `called` array** - Shows steps from called features inline
- **Renderer is simple** - Just iterate steps in order, display text with status

| Field | Description |
|-------|-------------|
| `scenarios[].steps` | Array of executed steps in order |
| `steps[].text` | Step source with indentation preserved (may include preceding comment/blank lines) |
| `steps[].status` | `"passed"`, `"failed"`, `"skipped"` |
| `steps[].ms` | Duration in milliseconds |
| `steps[].logs` | Array of log entries (HTTP, print, etc.) |
| `steps[].error` | Error message if failed |
| `steps[].called` | Nested steps array for called features |

#### Line-Delimited JSON (NDJSON) for Raw Data (Opt-In)

NDJSON output is opt-in via `.outputNdjson(true)`. It provides a **single append-only NDJSON file** with **feature-level granularity** for use cases like:
- Report aggregation across multiple test runs
- Live tailing during execution for progress monitoring
- External integrations and custom tooling

```
{"t":"suite","time":"2025-12-16T10:30:00","threads":5,"env":"dev"}
{"t":"feature","path":"features/users.feature","name":"User Management","scenarios":[...],"passed":true,"ms":1234}
{"t":"feature","path":"features/orders.feature","name":"Order Processing","scenarios":[...],"passed":false,"ms":2345}
{"t":"suite_end","passed":42,"failed":3,"ms":12345}
```

Each `feature` line contains the full scenario/step data as defined in the JSON schema above.

**Why feature-level (not step-level) events:**
- **Simpler clients** - Each line is a complete, self-contained feature result
- **Easy progress tracking** - `grep '"t":"feature"' karate-results.ndjson | wc -l`
- **Atomic writes** - No need to reconstruct partial state
- **Web server friendly** - Serve features as they complete, clients poll/SSE for new lines

**Architecture:**
```
Test threads ──write──► Queue ──single writer──► karate-results.ndjson
                                                        │
                                           (post-process on suite end)
                                                        ▼
                                              ┌─────────────────┐
                                              │ index.html      │
                                              │ features/*.html │
                                              │ karate-summary.json │
                                              └─────────────────┘
```

**Benefits:**
- **Single file during execution** - No explosion of files
- **Append-only** - Thread-safe with single writer thread
- **Streamable** - Can tail during execution for live progress
- **Post-process to split** - Generate per-feature HTML at suite end
- **Easy aggregation** - `cat run1.ndjson run2.ndjson` to merge runs
- **MB of JSON in script tag is fine** - Modern browsers handle this easily

**Future possibility:** A web server can serve this data live, enabling rich client-side rendering and search while tests execute.

### Logging

Package `io.karatelabs.log`:
- `LogContext` - Thread-local collector for report output
- `JvmLogger` - Infrastructure logging (System.err or SLF4J)

### Result Streaming (ResultListener)

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

**Planned built-in listeners:**
- `HtmlReportListener` - generates HTML reports (see Priority 1)
- `TelemetryListener` - sends daily ping (see Future Phase)

---

## Not Yet Implemented

### ~~Priority 1: HTML Reports~~ ✅ IMPLEMENTED

HTML report generation is now available with async feature HTML writing:

```java
// Default: HTML reports generated automatically
Runner.path("features/")
    .parallel(5);

// Opt-in NDJSON for aggregation/streaming
Runner.path("features/")
    .outputNdjson(true)
    .parallel(5);

// Disable HTML reports
Runner.path("features/")
    .outputHtmlReport(false)
    .parallel(5);
```

**Key features:**
- **Async HTML generation** - Feature HTML written as features complete, doesn't block execution
- **Memory efficient** - Only small summary data kept in memory
- **Inlined JSON + Alpine.js** - No server-side template rendering; JSON inlined in HTML, rendered client-side
- **NDJSON opt-in** - For report aggregation and live progress streaming
- **Report aggregation** - Merge NDJSON files from multiple test runs:

```java
HtmlReport.aggregate()
    .json("target/run1/karate-results.ndjson")
    .json("target/run2/karate-results.ndjson")
    .outputDir("target/combined")
    .generate();
```

See `io.karatelabs.core.HtmlReportListener`, `io.karatelabs.core.HtmlReportWriter`, `io.karatelabs.core.NdjsonReportListener`, and `io.karatelabs.core.HtmlReport` for implementation.

---

### ~~Priority 2: Cucumber JSON Format~~ ✅ IMPLEMENTED

Cucumber JSON report generation is now available via:
```java
Runner.path("features/")
    .outputCucumberJson(true)     // generates cucumber.json
    .parallel(5);
```

See `io.karatelabs.core.CucumberJsonWriter` for implementation.

---

### Priority 3: Feature File Discovery

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

### Priority 4: CLI JSON Configuration

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

### Priority 5: karate-base.js

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

#### Single-File HTML Architecture

**Key insight:** Inline the JSON data in the HTML, render everything client-side with Alpine.js.

```html
<!DOCTYPE html>
<html>
<head>
  <link rel="stylesheet" href="res/bootstrap.min.css">
  <script src="res/alpine.min.js" defer></script>
</head>
<body x-data="reportData()">
  <!-- Left nav, source view, log panel all rendered by Alpine -->
</body>
<script type="application/json" id="karate-data">
  { /* inlined karate-summary.json */ }
</script>
<script>
  function reportData() {
    return {
      data: JSON.parse(document.getElementById('karate-data').textContent),
      // Alpine reactive state for search, filters, scroll sync
    }
  }
</script>
</html>
```

**Benefits:**
- **Minimal I/O** - One HTML file per feature (not dozens of files)
- **No server required** - Works with `file://` protocol
- **Rich interactivity** - Search, filter, scroll sync all in client JS
- **Smaller total size** - JSON + template < pre-rendered HTML for each state

#### Execution Sequence UX

Reports show the **execution sequence** - steps in the order they ran, with source text preserved:

```
┌─────────────────────────────────────────────────────────────┐
│ Feature: User Management                            [PASS]  │
├─────────────────────────────────────────────────────────────┤
│ ▼ Scenario: Create user                 @smoke      [PASS]  │
│     * url baseUrl                                ✓  [2ms]   │
│     # create payload                                        │
│     * def user = { name: 'John' }                ✓  [1ms]   │
│   ▼ * call read('helper.feature')                ✓  [100ms] │ ← expandable
│       * print 'from helper'                      ✓  [1ms]   │
│     * method post                                ✓  [45ms]  │ ← click to expand logs
│     * status 201                                 ✓  [0ms]   │
└─────────────────────────────────────────────────────────────┘
          │
          ▼ (click to expand HTTP logs)
┌─────────────────────────────────────────────────────────────┐
│ > POST http://localhost:8080/users                          │
│ > Content-Type: application/json                            │
│ > { "name": "John" }                                        │
│ < 201 Created                                               │
│ < { "id": 123, "name": "John" }                             │
└─────────────────────────────────────────────────────────────┘
```

**Key UX features:**
- **Execution order** - Steps shown as they ran, not source file order
- **Indentation preserved** - Step text includes original whitespace
- **Comments inline** - Preceding comments included in step text
- **Called features nested** - Expandable tree showing called feature steps
- **Pass/fail indicators** per step with timing
- **Click to expand** logs for HTTP, print, etc.
- **Loops show each iteration** - Same step appears multiple times if looped

**Why execution sequence (not source overlay):**

| Source Overlay Problems | Execution Sequence Solution |
|------------------------|----------------------------|
| Loops: line 15 executes 3× | Each execution is a separate entry |
| Outline: same lines, different scenarios | Each scenario has its own step list |
| Called features: steps from other files | Nested in `called` array |
| Embedded JS: execution spans contexts | All steps in execution order |

**Data model:** See [Karate JSON Schema](#karate-json-schema-v2---simplified-breaking-change-from-v1) above. Each scenario has a `steps` array in execution order. The renderer iterates and displays - no line-number mapping needed.

#### Stack

- Bootstrap 5 (CSS only, minimal JS for dropdowns)
- Alpine.js (reactive rendering, no build step)
- No jQuery dependency

**Static Resources:**
- Bundled in JAR, copied to `res/` folder on first report generation
- Shared across all feature reports
- Allows offline viewing

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

### Cucumber JSON Format ✅

> **Status:** Implemented in `io.karatelabs.core.CucumberJsonWriter`

Standard Cucumber JSON for third-party tool integration (Allure, ReportPortal, etc.).

```java
Runner.path("features/")
    .outputCucumberJson(true)     // generates cucumber.json
    .parallel(5);
```

**Output:** `target/karate-reports/cucumber.json`

**Schema (per Cucumber spec):**
```json
[
  {
    "id": "users-feature",
    "uri": "classpath:features/users.feature",
    "name": "User Management",
    "description": "Feature description",
    "keyword": "Feature",
    "line": 1,
    "tags": [{"name": "@smoke", "line": 1}],
    "elements": [
      {
        "id": "users-feature;create-user",
        "name": "Create user",
        "description": "",
        "keyword": "Scenario",
        "line": 5,
        "type": "scenario",
        "tags": [{"name": "@smoke", "line": 4}],
        "steps": [
          {
            "keyword": "Given ",
            "name": "url 'http://localhost:8080'",
            "line": 6,
            "result": {
              "status": "passed",
              "duration": 1234567
            },
            "embeddings": [
              {
                "mime_type": "text/plain",
                "data": "base64-encoded-data"
              }
            ]
          }
        ]
      }
    ]
  }
]
```

**Key mappings:**
| Karate Concept | Cucumber JSON Field |
|---------------|---------------------|
| Feature | Top-level array element |
| Scenario | `elements[]` with `type: "scenario"` |
| Scenario Outline row | `elements[]` with `type: "scenario"` |
| Background | `elements[]` with `type: "background"` (repeated per scenario) |
| Step | `steps[]` within element |
| Step keyword | `keyword` (includes trailing space: `"Given "`) |
| Step text | `name` |
| Step result | `result.status`: `"passed"`, `"failed"`, `"skipped"`, `"pending"` |
| Duration | `result.duration` in nanoseconds |
| Error | `result.error_message` |
| Logs/embeds | `embeddings[]` with `mime_type` and base64 `data` |

**Implementation class:** `io.karatelabs.core.CucumberJsonWriter`

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
| `CucumberJsonWriterTest` | Cucumber JSON report generation |
| `HtmlReportListenerTest` | Async HTML report generation |
| `NdjsonReportListenerTest` | NDJSON streaming (opt-in) |
| `HtmlReportWriterTest` | HTML report generation and aggregation |

Test utilities in `TestUtils.java` and `InMemoryHttpClient.java`.
