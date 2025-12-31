# Karate v2 Runtime Design

> See also: [CLI.md](./CLI.md) | [PARSER.md](./PARSER.md) | [JS_ENGINE.md](./JS_ENGINE.md) | [REPORTS.md](./REPORTS.md) | [EVENTS.md](./EVENTS.md) | [PRINCIPLES.md](./PRINCIPLES.md) | [GATLING.md](./GATLING.md)

---

## Architecture Overview

```
Suite → FeatureRuntime → ScenarioRuntime → StepExecutor
                                               ↓
                              ┌────────────────┼────────────────┐
                              ▼                ▼                ▼
                         Match Engine    Http Client    Other Actions
```

**V1 Reference:** `/Users/peter/dev/zcode/karate/karate-core/src/main/java/com/intuit/karate/`

---

## Core Classes

| Class | Description |
|-------|-------------|
| `Suite` | Top-level orchestrator, config loading, parallel execution |
| `FeatureRuntime` | Feature execution, scenario iteration, caching |
| `ScenarioRuntime` | Scenario execution, variable scope |
| `StepExecutor` | Keyword-based step dispatch |
| `KarateJs` | JS engine bridge, `karate.*` methods, implements `PerfContext` |
| `KarateJsBase` | Shared state and infrastructure for KarateJs |
| `KarateJsUtils` | Stateless utility methods for `karate.*` API |
| `KarateJsContext` | Runtime context interface (implemented by ScenarioRuntime) |
| `PerfContext` | Interface for custom performance event capture (Gatling integration) |
| `PerfEvent` | Data record for performance events (name, startTime, endTime) |
| `CommandProvider` | SPI for CLI subcommand registration (see [CLI.md](./CLI.md)) |
| `Runner` | Fluent API for test execution |
| `RuntimeHook` | Lifecycle callbacks for test execution interception (see [EVENTS.md](./EVENTS.md) for new API) |
| `ResultListener` | Observation interface for reporting |

**Reports (io.karatelabs.output):** `HtmlReportListener`, `JunitXmlWriter`, `CucumberJsonWriter`, `JsonLinesReportListener`

**Results:** `StepResult`, `ScenarioResult`, `FeatureResult`, `SuiteResult`

---

## Implemented Features

### Step Keywords
- **Variables:** `def`, `set`, `remove`, `text`, `json`, `xml`, `csv`, `yaml`, `string`, `xmlstring`, `copy`, `table`, `replace`
- **Assertions:** `match` (all operators), `assert`, `print`
- **HTTP:** `url`, `path`, `param`, `params`, `header`, `headers`, `cookie`, `cookies`, `form field`, `form fields`, `request`, `retry until`, `method`, `status`, `multipart file/field/fields/files/entity`
- **Control:** `call`, `callonce`, `eval`, `doc`
- **Config:** `configure` (see [Configure Keys](#configure-keys))

### Built-in Tags
| Tag | Description |
|-----|-------------|
| `@ignore` | Skip execution (feature or scenario level) |
| `@env=<name>` | Run only when `karate.env` matches |
| `@envnot=<name>` | Skip when `karate.env` matches |
| `@setup` | Data provider for dynamic outlines |
| `@fail` | Expect failure (invert result) |

### Tag Inheritance

Feature-level tags are inherited by all scenarios in that feature. When checking tags, the effective tags are the **merge of feature + scenario tags**.

```gherkin
@api @ignore
Feature: Helper for tests
  # All scenarios inherit @api and @ignore

Scenario: Setup data
  # Effective tags: @api, @ignore
  * def data = { }
```

### @ignore Behavior

The `@ignore` tag skips scenarios/features from execution:

- **Feature-level `@ignore`**: The entire feature is skipped by the runner (does not appear in reports)
- **Scenario-level `@ignore`**: That specific scenario is skipped
- **Called features**: `@ignore` does NOT prevent a feature from being called via `call read('...')`

This allows creating helper features that are only executed when explicitly called:

```gherkin
@ignore
Feature: Helper utilities
  # Not executed by runner, but can be called

Scenario: Generate test data
  * def result = { id: 1 }
```

```gherkin
Feature: Main tests

Scenario: Use helper
  * def data = call read('helper.feature')  # Works despite @ignore
  * match data.result.id == 1
```

### Runner API
```java
SuiteResult result = Runner.path("src/test/resources")
    .tags("@smoke", "~@slow")
    .karateEnv("dev")
    .outputJunitXml(true)
    .outputCucumberJson(true)
    .parallel(5);
```

### System Properties
| Property | Description |
|----------|-------------|
| `karate.env` | Environment name |
| `karate.config.dir` | Config directory |
| `karate.output.dir` | Output directory |
| `karate.working.dir` | Working directory |

---

## V1 Differences

Intentional deviations from V1 behavior:

| Feature | V1 Behavior | V2 Behavior | Rationale |
|---------|-------------|-------------|-----------|
| Variable names | Must start with letter (`[a-zA-Z]`) | Can start with letter or underscore (`[a-zA-Z_]`) | More permissive, aligns with JavaScript conventions |

---

## karate.* API

### Implemented

| Method | Location | Description |
|--------|----------|-------------|
| `abort()` | KarateJs | Abort scenario execution |
| `append(list, items...)` | KarateJsUtils | Append items to list (returns new list) |
| `appendTo(list, items...)` | KarateJsUtils | Append items to list (mutates) |
| `call(path, arg?)` | KarateJs | Call feature file |
| `callonce(path, arg?)` | KarateJs | Call feature once per feature |
| `callSingle(path, arg?)` | KarateJs | Call once per suite (cached) |
| `capturePerfEvent(name, start, end)` | KarateJs | Capture custom perf event (Gatling integration, NO-OP otherwise) |
| `config` | KarateJs | Get config object |
| `configure(key, value)` | KarateJs | Set configuration |
| `distinct(list)` | KarateJsUtils | Remove duplicates |
| `doc(template)` | KarateJs | Render HTML template |
| `embed(data, [mimeType], [name])` | KarateJs | Embed content in report (auto-detects type) |
| `env` | KarateJs | Get karate.env value |
| `eval(expression)` | KarateJs | Evaluate JS expression |
| `feature` | KarateJs | Get feature info (name, description, prefixedPath, fileName, parentDir) |
| `exec(command)` | KarateJs | Execute system command |
| `extract(text, regex, group)` | KarateJsUtils | Extract regex match |
| `extractAll(text, regex, group)` | KarateJsUtils | Extract all regex matches |
| `fail(message)` | KarateJsUtils | Fail scenario with message |
| `filter(list, fn)` | KarateJsUtils | Filter list by predicate |
| `filterKeys(map, keys)` | KarateJsUtils | Filter map by keys |
| `forEach(collection, fn)` | KarateJsUtils | Iterate collection |
| `fork(options)` | KarateJs | Fork background process |
| `fromJson(string)` | KarateJsUtils | Parse JSON string |
| `fromString(text)` | KarateJs | Parse as JSON/XML or return string |
| `get(name, path?)` | KarateJs | Get variable value |
| `http(url)` | KarateJs | Create HTTP client |
| `info` | KarateJs | Get scenario info |
| `jsonPath(obj, path)` | KarateJsUtils | Apply JSONPath expression |
| `keysOf(map)` | KarateJsUtils | Get map keys |
| `log(args...)` | KarateJs | Log message |
| `lowerCase(value)` | KarateJsUtils | Lowercase string/JSON/XML |
| `map(list, fn)` | KarateJsUtils | Transform list |
| `mapWithKey(list, key)` | KarateJsUtils | Wrap list items in maps |
| `match(actual, expected)` | KarateJs | Match assertion |
| `merge(maps...)` | KarateJsUtils | Merge maps |
| `os` | KarateJs | Get OS info |
| `pause(ms)` | KarateJsUtils | Sleep for milliseconds |
| `pretty(value)` | KarateJsUtils | Format as pretty JSON/XML |
| `prettyXml(value)` | KarateJsUtils | Format as pretty XML |
| `proceed()` | KarateJs | Proceed in mock (passthrough) |
| `properties` | KarateJs | Get system properties |
| `range(start, end, step?)` | KarateJsUtils | Generate number range |
| `read(path)` | KarateJs | Read file content |
| `readAsBytes(path)` | KarateJs | Read file as bytes |
| `readAsString(path)` | KarateJs | Read file as string |
| `remove(name, path)` | KarateJs | Remove from variable |
| `repeat(count, fn)` | KarateJsUtils | Generate list by repeating function |
| `scenario` | KarateJs | Get scenario info (name, description, line, sectionIndex, exampleIndex, exampleData) |
| `scenarioOutline` | KarateJs | Get scenario outline info (null if not in outline) |
| `set(name, path?, value)` | KarateJs | Set variable value |
| `setup()` | KarateJs | Get setup scenario result |
| `setupOnce()` | KarateJs | Get cached setup result |
| `setXml(name, path, value)` | KarateJs | Set XML value |
| `signal(value)` | KarateJs | Signal to listener |
| `sizeOf(value)` | KarateJsUtils | Get size of list/map/string |
| `sort(list, fn)` | KarateJsUtils | Sort list by key function |
| `start(options)` | KarateJs | Start mock server |
| `tags` | KarateJs | Get effective tags list (feature + scenario) |
| `tagValues` | KarateJs | Get tag values map (tag name → list of values) |
| `toBean(obj, className)` | KarateJsUtils | Convert to Java bean |
| `toBytes(list)` | KarateJsUtils | Convert number list to byte[] |
| `toCsv(list)` | KarateJsUtils | Convert list of maps to CSV |
| `toJava(value)` | KarateJsUtils | No-op (V1 compat) |
| `toJson(value, removeNulls?)` | KarateJsUtils | Convert to JSON |
| `toString(value)` | KarateJsUtils | Convert to string (JSON/XML aware) |
| `typeOf(value)` | KarateJsUtils | Get Karate type name |
| `urlDecode(string)` | KarateJsUtils | URL decode |
| `urlEncode(string)` | KarateJsUtils | URL encode |
| `valuesOf(map)` | KarateJsUtils | Get map values |
| `xmlPath(xml, path)` | KarateJsUtils | Apply XPath expression |
| `logger` | KarateJs | Log facade (debug/info/warn/error via LogContext) |
| `prevRequest` | KarateJs | Get previous HTTP request (method, url, headers, body) |
| `request` | KarateJs | Get current request body (mock context only) |
| `response` | KarateJs | Get current response (mock context only) |
| `readAsStream(path)` | KarateJs | Read file as InputStream |
| `render(template)` | KarateJs | Render HTML template (returns string) |
| `stop(port)` | KarateJsUtils | Debug breakpoint - pauses until connection received |
| `toAbsolutePath(path)` | KarateJs | Convert relative path to absolute |
| `waitForHttp(url, options)` | KarateJsUtils | Poll HTTP endpoint until available |
| `waitForPort(host, port)` | KarateJsUtils | Wait for TCP port to become available |
| `write(value, path)` | KarateJs | Write content to file in output directory |

### Out of Scope (UI/Extensions)

| Method | Reason |
|--------|--------|
| `robot` | Desktop automation deferred (karate-robot) |
| `channel()`, `consume()` | Kafka extension |
| `webSocket()`, `webSocketBinary()` | WebSocket not yet |
| `compareImage()` | UI testing |

### Planned (Driver/Browser)

| Method | Phase | Notes |
|--------|-------|-------|
| `driver` | Phase 9 | Browser automation via `karate.driver()` - see [DRIVER.md](./DRIVER.md) |

### Performance Testing Integration (PerfContext)

The `PerfContext` interface enables custom performance event capture for Gatling integration:

```java
// io.karatelabs.core.PerfContext
public interface PerfContext {
    void capturePerfEvent(String name, long startTime, long endTime);
}
```

`KarateJs` implements `PerfContext`, so the `karate` object can be passed to Java methods:

```java
// Custom Java code capturing performance metrics
public static Map<String, Object> myRpc(Map<String, Object> args, PerfContext context) {
    long start = System.currentTimeMillis();
    // ... custom logic (database, gRPC, etc.) ...
    long end = System.currentTimeMillis();

    context.capturePerfEvent("myRpc", start, end);
    return Map.of("success", true);
}
```

```gherkin
# Feature file usage
Scenario: Custom RPC
  * def result = Java.type('mock.MockUtils').myRpc({ data: 'test' }, karate)
```

**Behavior:**
- **Normal execution:** `capturePerfEvent()` is a NO-OP
- **Gatling context:** Events are reported to Gatling's StatsEngine

**Implementation:** `KarateJs` has an optional `perfEventHandler` that Gatling sets before feature execution. See [GATLING.md](./GATLING.md) for full integration details.

### Pending V1 Parity (Advanced)

| Feature | V1 Pattern | Notes |
|---------|-----------|-------|
| Java Function as callable | `Hello.sayHelloFactory()` returns `Function<String,String>` | JS engine needs to wrap `java.util.function.Function`, `Callable`, `Runnable`, `Predicate` as `JsCallable` |
| callSingle returning Java fn | `karate.callSingle('file.js')` where JS returns Java Function | Depends on above |
| Tagged Examples in call/callSingle | `call 'file.feature@tag'` with `@tag` on Examples section | Tag on Examples should filter which outline rows to execute. V1: `call-single-tag-called.feature` |

---

## Configure Keys

### Implemented
`ssl`, `proxy`, `readTimeout`, `connectTimeout`, `followRedirects`, `headers`, `cookies`, `charset`, `retry`, `report`, `ntlmAuth`, `callSingleCache`, `continueOnStepFailure`, `httpRetryEnabled`

### TODO
| Key | Priority |
|-----|----------|
| `url` | High |
| `driver` | High (Phase 9) |
| `lowerCaseResponseHeaders` | Medium |
| `logPrettyRequest/Response` | Medium |
| `printEnabled` | Medium |
| `localAddress` | Low |

### Out of Scope
`robot`, `driverTarget`, `kafka`, `grpc`, `websocket`, `webhook`, `responseHeaders`, `responseDelay`, `cors`

---

## Not Yet Implemented

### Priority 7: JavaScript Script Execution (`*.karate.js`)

Pure JavaScript files with the `*.karate.js` naming convention can be executed directly:

```javascript
// server-test.karate.js
var proc = karate.fork({
  args: ['node', 'server.js'],
  listener: function(line) {
    if (line.contains('listening')) {
      karate.signal({ ready: true })
    }
  }
})

var result = karate.listen(5000)
if (!result.ready) throw 'Server did not start'

var http = karate.http('http://localhost:8080')
var response = http.path('health').get()
match(response.body).equals({ status: 'ok' })

proc.close()
```

**Implementation:** `JsScriptRuntime` - executes `.karate.js` with KarateJs context, results mapped to `ScenarioResult`.

---

### Priority 9: Configure Report

```cucumber
* configure report = { showJsLineNumbers: true }
```

Controls report verbosity, JS line-level capture, HTTP detail, payload size limits.

See [REPORTS.md](./REPORTS.md) for specification.

---

### Priority 9: karate-base.js

Shared config from classpath (e.g., company JAR):

```
karate-base.js (from JAR)
  ↓ overridden by
karate-config.js (project)
  ↓ overridden by
karate-config-dev.js (env-specific)
```

---

## Future Phase

### Lock System (`@lock=<name>`)

For mutual exclusion when scenarios cannot run in parallel:

```gherkin
@lock=database
Feature: User tests
  Scenario: Create user      # holds "database" lock

@lock=*
Scenario: Restart server     # runs exclusively
```

**Implementation:**
```java
public class LockRegistry {
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();

    public void acquireLock(String lockName) {
        if ("*".equals(lockName)) {
            acquireExclusive();
        } else {
            globalLock.readLock().lock();
            locks.computeIfAbsent(lockName, k -> new ReentrantLock()).lock();
        }
    }
}
```

---

### Retry System (`@retry`)

```gherkin
@retry
Scenario: Flaky test
  * url 'https://flaky-api.example.com'
  * method get
  * status 200
```

**Flow:**
1. Suite executes all scenarios
2. Collect failed `@retry` scenarios
3. Re-run failures (fresh execution)
4. Final results use best outcome

**CLI:** `karate --rerun target/karate-reports/rerun.txt`

---

### Multiple Suite Execution

```java
List<SuiteResult> results = Runner.suites()
    .add(Runner.path("src/test/api/").parallel(10))
    .add(Runner.path("src/test/ui/").parallel(2))
    .parallel(2)
    .run();
```

---

### Telemetry

Anonymous daily usage ping from `Suite.run()`:

```json
{
  "uuid": "abc-123-...",
  "version": "2.0.0",
  "os": "darwin",
  "java": "21",
  "features": 15,
  "scenarios": 42,
  "passed": true,
  "ci": true
}
```

**Storage:** `~/.karate/uuid.txt`, `~/.karate/telemetry.json`

**Opt-out:** `export KARATE_TELEMETRY=false`

---

### TODO: Plugin Architecture

V1 uses `Class.forName()` for loading plugins (RuntimeHook, ChannelFactory, RobotFactory, etc.). V2 should formalize the plugin architecture:

**ServiceLoader (auto-discovery):**
- Best for: CLI commands, report listeners, extensions that should "just work"
- User drops JAR in `~/.karate/ext/`, plugin is automatically available
- Uses `META-INF/services/` for registration
- Example: `CommandProvider` for `karate perf`

**Class.forName (explicit loading):**
- Best for: RuntimeHooks, custom factories where user wants explicit control
- User specifies class name via CLI `--hook com.example.MyHook` or config
- More flexible for conditional loading

**Proposed SPIs:**

| Interface | Purpose | Discovery |
|-----------|---------|-----------|
| `CommandProvider` | CLI subcommands | ServiceLoader |
| `RunListenerFactory` | Event listeners | ServiceLoader |
| `ReportWriterFactory` | Custom report formats | ServiceLoader |
| `RuntimeHook` | Lifecycle callbacks | Class.forName (--hook) |
| `HttpClientFactory` | Custom HTTP clients | Class.forName (config) |

**Implementation notes:**
- ServiceLoader providers should be lazy-loaded and fail gracefully
- Document which interfaces use which pattern
- Consider a unified `PluginRegistry` that supports both patterns

---

### Root Bindings (Private Variables)

The JS engine supports "root bindings" via `Engine.putRootBinding()` for built-in variables that should be accessible during evaluation but excluded from `getBindings()`.

**How it works:**
- `putRootBinding(name, value)` stores in `ContextRoot._bindings` (the root context's private bindings)
- `put(name, value)` stores in `Engine.bindings` (user-accessible bindings)
- `getBindings()` returns only `Engine.bindings`, excluding root bindings
- Variable lookup (`get()`) checks both, so root bindings are accessible during JS evaluation

**Usage in KarateJs:**
```java
// In KarateJs constructor - these won't appear in getAllVariables()
engine.putRootBinding("karate", this);
engine.putRootBinding("read", initRead());
engine.putRootBinding("match", matchFluent());

// In ScenarioRuntime - call args and example data
engine.putRootBinding("__arg", callArg);
engine.putRootBinding("__row", exampleData);
engine.putRootBinding("__num", exampleIndex);
```

**Result:** `ScenarioRuntime.getAllVariables()` returns only user-defined variables without needing a hardcoded exclusion list.

### TODO: Filter `fn` from karate-config.js

The `fn` variable from karate-config.js convention may still appear in `getAllVariables()`. Options to address:
1. Use `Engine.evalWith()` for config evaluation and post-process the map
2. Remove `fn` from bindings after config evaluation
3. Accept that `fn` appears (low priority, doesn't affect functionality)

---

## Test Classes

| Class | Coverage |
|-------|----------|
| `StepDefTest` | def, set, copy, table, replace, csv, yaml |
| `StepMatchTest` | match assertions |
| `StepHttpTest` | HTTP keywords |
| `StepMultipartTest` | multipart |
| `StepJsTest` | JS functions, karate.* API |
| `StepXmlTest` | XML operations |
| `StepCallTest` | call/callonce |
| `StepAbortTest` | karate.abort() |
| `StepEvalTest` | eval keyword |
| `StepInfoTest` | karate.info, scenario, feature, tags, tagValues, scenarioOutline |
| `OutlineTest` | Scenario outline, dynamic |
| `CallSingleTest` | karate.callSingle() |
| `DataUtilsTest` | CSV/YAML parsing |
| `TagSelectorTest` | Tag selectors: anyOf, allOf, not, valuesFor, @env |
