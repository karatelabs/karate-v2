# Karate v2 Runtime Design

This document describes the runtime architecture for Karate v2.

> See also: [CLI.md](./CLI.md) | [PARSER.md](./PARSER.md) | [JS_ENGINE.md](./JS_ENGINE.md) | [HTML_REPORTS.md](./HTML_REPORTS.md) | [PRINCIPLES.md](./PRINCIPLES.md) | [ROADMAP.md](./ROADMAP.md)

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
| `HtmlReportListener` | `io.karatelabs.core.HtmlReportListener` | Async HTML report generation (default). See [HTML_REPORTS.md](./HTML_REPORTS.md) |
| `HtmlReportWriter` | `io.karatelabs.core.HtmlReportWriter` | HTML report generation with inlined JSON |
| `NdjsonReportListener` | `io.karatelabs.core.NdjsonReportListener` | NDJSON streaming (opt-in) |
| `HtmlReport` | `io.karatelabs.core.HtmlReport` | Report aggregation API |
| `ResultListener` | `io.karatelabs.core.ResultListener` | Interface for streaming test results |

### Step Keywords (All Implemented)

**Variable Assignment:** `def`, `set`, `remove`, `text`, `json`, `xml`, `csv`, `yaml`, `string`, `xmlstring`, `copy`, `table`, `replace`

**Assertions:** `match` (all operators including `each`), `assert`, `print`

**HTTP:** `url`, `path`, `param`, `params`, `header`, `headers`, `cookie`, `cookies`, `form field`, `form fields`, `request`, `method`, `status`, `multipart file`, `multipart field`, `multipart fields`, `multipart files`, `multipart entity`

**Control Flow:** `call`, `callonce`, `eval`

**Config:** `configure` (ssl, proxy, readTimeout, connectTimeout, followRedirects, headers, charset)

#### Data Conversion Keywords

| Keyword | Description | Example |
|---------|-------------|---------|
| `csv` | Parse CSV to List<Map> | `* csv data = """name,age\nJohn,30"""` |
| `yaml` | Parse YAML to object | `* yaml config = """server: localhost"""` |
| `string` | Convert to JSON string | `* string s = response` |
| `xmlstring` | Convert to XML string | `* xmlstring s = xmlVar` |
| `replace` | Token replacement | `* replace text.name = 'World'` |

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

**Runner.Builder Options:**

| Method | Default | Description |
|--------|---------|-------------|
| `path(String...)` | - | Feature file paths or directories |
| `tags(String...)` | - | Tag expressions (e.g., `@smoke`, `~@slow`) |
| `karateEnv(String)` | - | Environment name for config-{env}.js |
| `outputDir(String)` | `target/karate-reports` | Output directory for reports |
| `outputHtmlReport(boolean)` | `true` | Generate HTML reports |
| `outputNdjson(boolean)` | `false` | Generate NDJSON for aggregation |
| `outputJunitXml(boolean)` | `false` | Generate JUnit XML for CI |
| `outputCucumberJson(boolean)` | `false` | Generate Cucumber JSON |
| `outputConsoleSummary(boolean)` | `true` | Print summary to console |
| `backupReportDir(boolean)` | `false` | Backup existing report dir with timestamp |
| `workingDir(String)` | current dir | Working directory for relative paths |
| `configPath(String)` | `classpath:karate-config.js` | Config file path |

See `io.karatelabs.core.Runner` for full API.

### CLI

```bash
java -jar karate.jar -t @smoke -e dev -T 5 src/test/resources
```

See `io.karatelabs.Main` (PicoCLI-based).

### Parallel Execution

Uses Java 21+ virtual threads. Basic parallel execution is implemented in `Suite.runParallel()`.

### Configuration Loading

- `karate-config.js` from configDir
- `karate-config-{env}.js` for environment-specific config
- Config values available as variables in scenarios

**V2 Enhancement - Working Directory Fallback:**

In V1, config files had to be on the Java classpath. V2 adds a fallback that searches the working directory when `classpath:karate-config.js` is not found. This makes Karate friendlier for users running from the file system without Java project structure:

```bash
# No classpath setup needed - just run from project directory
./my-project/
├── karate-config.js       # Found via working directory fallback
├── karate-config-dev.js   # Env-specific also found
└── tests/
    └── users.feature
```

**Config Evaluation Per-Scenario:**

Config is evaluated fresh for each scenario (not once per Suite). This enables:
- `karate.callSingle()` in config for one-time initialization
- `karate.env` access during config evaluation
- Per-scenario isolation with shared caches

### karate.callSingle()

`karate.callSingle(path)` executes a feature or JS file **once per Suite** with thread-safe caching. This is commonly used in `karate-config.js` for expensive bootstrap operations (authentication, database setup, etc.).

```javascript
// karate-config.js
function fn() {
    var auth = karate.callSingle('classpath:auth.feature');
    return {
        authToken: auth.token,
        baseUrl: 'http://localhost:8080'
    };
}
```

**Key features:**
- **Suite-level caching** - Result cached and shared across all scenarios
- **Thread-safe** - Uses `ReentrantLock` with double-checked locking pattern
- **Exception caching** - Failed callSingle cached and re-thrown on subsequent calls
- **Deep copy** - Cached results deep-copied to prevent cross-thread mutation
- **Works in config** - Available in `karate-config.js` because config is evaluated per-scenario after providers are wired

**Supported file types:**
- `.feature` files - Returns map of all defined variables
- `.js` files - Function is invoked, return value cached

**With arguments:**
```javascript
var result = karate.callSingle('setup.feature', { name: 'World' });
```

**Implementation:**
- `Suite` holds `CALLSINGLE_CACHE` (ConcurrentHashMap) and `callSingleLock` (ReentrantLock)
- `ScenarioRuntime.executeCallSingle()` implements double-checked locking
- `KarateJs.callSingle()` bridges to runtime via provider pattern

See `io.karatelabs.core.callsingle.CallSingleTest` for comprehensive test coverage including parallel execution.

### Reports

- Karate JSON report (`karate-summary.json`)
- JUnit XML report (`karate-junit.xml`) for CI/CD integration
- Cucumber JSON report (`cucumber.json`) for third-party tools (Allure, ReportPortal, etc.)
- Console output with ANSI colors (`io.karatelabs.core.Console`)

#### Report Architecture

See [HTML_REPORTS.md](./HTML_REPORTS.md) for detailed HTML report architecture, JSON schema, and NDJSON streaming documentation.

**Summary:**
- `HtmlReportListener` writes feature HTML files asynchronously as features complete
- Only small summary data kept in memory
- JSON data inlined in HTML, rendered client-side with Alpine.js
- NDJSON streaming opt-in via `.outputNdjson(true)`
- Report aggregation via `HtmlReport.aggregate()`

### Logging Architecture

Karate v2 separates logging into two distinct systems:

#### Test Logs (LogContext)

`io.karatelabs.log.LogContext` is a thread-local log collector that captures all test output:
- `* print` statements
- `karate.log()` / `console.log()` calls
- HTTP request/response summaries

Logs are captured per-step and stored in `StepResult`, appearing in HTML reports.

**Key methods:**

| Method | Description |
|--------|-------------|
| `LogContext.get()` | Get current thread's log context |
| `log(Object message)` | Log a message |
| `log(String format, Object... args)` | Log with `{}` placeholders |
| `collect()` | Get accumulated log and clear buffer |
| `peek()` | Get accumulated log without clearing |
| `setCascade(Consumer<String>)` | Forward logs to external logger |

**Cascade to SLF4J:**

Test logs are captured for HTML reports by default. To additionally forward them to SLF4J:

```java
import io.karatelabs.log.LogContext;
import io.karatelabs.log.Slf4jCascade;

// In test setup or @BeforeClass
LogContext.setCascade(Slf4jCascade.create());
```

This forwards all test output to the `karate.run` SLF4J category at INFO level. Configure in your `logback.xml`:

```xml
<logger name="karate.run" level="INFO">
    <appender-ref ref="FILE" />
</logger>
```

For custom category or level:
```java
LogContext.setCascade(Slf4jCascade.create("my.category", LogLevel.DEBUG));
```

#### Framework Logs (JvmLogger)

`io.karatelabs.log.JvmLogger` is a static logger for Karate framework/infrastructure code:
- Config loading
- Hook execution
- Report generation
- callSingle/callOnce execution

**Configuration:**
```java
import io.karatelabs.log.JvmLogger;
import io.karatelabs.log.LogLevel;

// Set log level (default: INFO)
JvmLogger.setLevel(LogLevel.DEBUG);

// Custom appender (default: System.err)
JvmLogger.setAppender((level, format, args) -> {
    // custom implementation
});
```

**Log Levels:** `TRACE` < `DEBUG` < `INFO` < `WARN` < `ERROR`

### Console Output

The `io.karatelabs.core.Console` class provides ANSI color support for terminal output.

**Auto-Detection:**

Colors are automatically enabled based on environment:
- Respects `NO_COLOR` environment variable (https://no-color.org/)
- Respects `FORCE_COLOR` environment variable
- Detects terminal capability via `TERM`, `COLORTERM`
- Windows: detects Windows Terminal via `WT_SESSION`

**Programmatic Control:**
```java
import io.karatelabs.core.Console;

Console.setColorsEnabled(false);  // Disable colors
```

**Console Summary:**

Control whether suite/feature summaries are printed to console:

```java
Runner.path("features/")
    .outputConsoleSummary(false)  // suppress console output
    .parallel(5);
```

Default: `true`. When disabled, results are still available in `SuiteResult`.

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

### ~~Priority 3: Feature File Discovery~~ ✅ IMPLEMENTED

Feature file discovery now supports:
- File system directory scanning ✓
- Single feature file loading ✓
- Classpath directory scanning ✓
- JAR resource scanning ✓

```java
// File system paths
Runner.path("src/test/resources/features/")

// Classpath directory - scans for all .feature files
Runner.path("classpath:features/")

// Classpath single file
Runner.path("classpath:features/users.feature")

// Mixed paths
Runner.path("src/test/local/", "classpath:features/")
```

**Implementation details:**
- Uses `ClassLoader.getResources()` to locate classpath directories
- NIO FileSystem API for walking directories (handles both file system and JAR entries)
- Fallback to manual JAR scanning when NIO FileSystem provider unavailable
- See `Resource.scanClasspath()` in `io.karatelabs.common.Resource`

---

### ~~Priority 4: CLI JSON Configuration~~ ✅ IMPLEMENTED

Run Karate tests using a JSON configuration file:

```bash
karate --config karate.json

# Override config file options with CLI args
karate --config karate.json -e prod -T 10
```

**JSON Schema:**
```json
{
  "paths": ["src/test/features/", "classpath:features/"],
  "tags": ["@smoke", "~@slow"],
  "env": "dev",
  "threads": 5,
  "scenarioName": ".*login.*",
  "configDir": "src/test/resources",
  "workingDir": "/home/user/project",
  "dryRun": false,
  "clean": false,
  "output": {
    "dir": "target/karate-reports",
    "html": true,
    "junitXml": false,
    "cucumberJson": false,
    "ndjson": false
  }
}
```

**Precedence:** CLI arguments override config file values.

See `io.karatelabs.core.KarateConfig` for implementation.

---

### ~~Priority 5: Working Directory (`-w, --workdir`)~~ ✅ IMPLEMENTED

Working directory CLI option is now available to control relative path resolution:

```bash
# Run from project root, features in subdir
karate -w /home/user/project src/test/features

# JSON config
{
  "workingDir": "/home/user/project",
  "paths": ["src/test/features"]
}
```

**Programmatic API:**
```java
Runner.path("src/test/features")
    .workingDir("/home/user/project")
    .parallel(5);
```

**Implementation details:**
- `Main.java` - Added `-w, --workdir` CLI option
- `KarateConfig.java` - Added `workingDir` JSON field
- `Runner.Builder` - Added `workingDir(String/Path)` methods
- `Suite` - Stores and propagates working directory for resource creation
- `Resource.scanClasspath()` - Accepts optional root parameter for relative path computation

---

### ~~Priority 6: Dynamic Scenario Outline - Generator Function Support~~ ✅ IMPLEMENTED

Generator functions are now supported for dynamic scenario outlines. The expression can return either a `List<?>` or a function that generates rows.

**Usage:**
```gherkin
@setup
Scenario: Define generator
  * def generator = function(i){ if (i == 5) return null; return { name: 'item' + i, index: i } }

Scenario Outline: Test generated data
  * match __num == <index>
  * match __row.name == 'item' + <index>

Examples:
| karate.setup().generator |
```

**How it works:**
- If the expression returns a `List`, it's used directly
- If the expression returns a function (JS callable), it's called repeatedly with an incrementing index (0, 1, 2, ...)
- The function should return a `Map` for each row of data
- Return `null` or a non-Map value to signal end of iteration

**Built-in variables for outline scenarios:**
- `__num` - The example row index (0-based)
- `__row` - The full example data map

**Implementation:**
- `FeatureRuntime.evaluateDynamicExpression()` - Detects functions and delegates to generator evaluation
- `FeatureRuntime.evaluateGeneratorFunction()` - Calls function repeatedly via `JsCallable.call(null, index)` until termination
- `ScenarioRuntime.initEngine()` - Sets `__num` and `__row` variables

See `io.karatelabs.core.DynamicOutlineTest` for comprehensive test coverage.

---

### Priority 7: karate-base.js

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

See [HTML_REPORTS.md](./HTML_REPORTS.md) for comprehensive HTML report documentation including:
- Report architecture and memory-efficient generation
- JSON schema for summary and feature data
- NDJSON streaming for aggregation
- Template customization
- UX patterns and execution sequence display

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
| `ConfigTest` | Config loading, working directory fallback |
| `callsingle/CallSingleTest` | `karate.callSingle()`, thread-safe caching, parallel execution |
| `log/Slf4jCascadeTest` | SLF4J cascade for test logs |
| `RuntimeHookTest` | Hooks |
| `ResultListenerTest` | Result streaming |
| `RunnerTest` | Runner API, classpath directory scanning |
| `KarateConfigTest` | JSON config file parsing and loading |
| `JunitXmlWriterTest` | JUnit XML report generation |
| `CucumberJsonWriterTest` | Cucumber JSON report generation |
| `HtmlReportListenerTest` | Async HTML report generation. See [HTML_REPORTS.md](./HTML_REPORTS.md) |
| `NdjsonReportListenerTest` | NDJSON streaming (opt-in) |
| `HtmlReportWriterTest` | HTML report generation and aggregation |

Test utilities in `TestUtils.java` and `InMemoryHttpClient.java`.
