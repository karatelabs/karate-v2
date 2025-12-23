# Karate v2 Runtime Design

> See also: [CLI.md](./CLI.md) | [PARSER.md](./PARSER.md) | [JS_ENGINE.md](./JS_ENGINE.md) | [HTML_REPORTS.md](./HTML_REPORTS.md) | [PRINCIPLES.md](./PRINCIPLES.md)

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
| `KarateJs` | JS engine bridge, `karate.*` methods |
| `KarateJsApi` | Stateless utility methods for `karate.*` API |
| `Runner` | Fluent API for test execution |

**Reports:** `HtmlReportListener`, `JunitXmlWriter`, `CucumberJsonWriter`, `NdjsonReportListener`

**Results:** `StepResult`, `ScenarioResult`, `FeatureResult`, `SuiteResult`

---

## Implemented Features

### Step Keywords
- **Variables:** `def`, `set`, `remove`, `text`, `json`, `xml`, `csv`, `yaml`, `string`, `xmlstring`, `copy`, `table`, `replace`
- **Assertions:** `match` (all operators), `assert`, `print`
- **HTTP:** `url`, `path`, `param`, `params`, `header`, `headers`, `cookie`, `cookies`, `form field`, `form fields`, `request`, `method`, `status`, `multipart file/field/fields/files/entity`
- **Control:** `call`, `callonce`, `eval`, `doc`
- **Config:** `configure` (see [Configure Keys](#configure-keys))

### Built-in Tags
| Tag | Description |
|-----|-------------|
| `@ignore` | Skip execution |
| `@env=<name>` | Run only when `karate.env` matches |
| `@envnot=<name>` | Skip when `karate.env` matches |
| `@setup` | Data provider for dynamic outlines |
| `@fail` | Expect failure (invert result) |

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

## karate.* API

### Implemented

| Method | Location | Description |
|--------|----------|-------------|
| `abort()` | KarateJs | Abort scenario execution |
| `append(list, items...)` | KarateJsApi | Append items to list (returns new list) |
| `appendTo(list, items...)` | KarateJsApi | Append items to list (mutates) |
| `call(path, arg?)` | KarateJs | Call feature file |
| `callonce(path, arg?)` | KarateJs | Call feature once per feature |
| `callSingle(path, arg?)` | KarateJs | Call once per suite (cached) |
| `config` | KarateJs | Get config object |
| `configure(key, value)` | KarateJs | Set configuration |
| `distinct(list)` | KarateJsApi | Remove duplicates |
| `doc(template)` | KarateJs | Render HTML template |
| `embed(data, [mimeType], [name])` | KarateJs | Embed content in report (auto-detects type) |
| `env` | KarateJs | Get karate.env value |
| `eval(expression)` | KarateJs | Evaluate JS expression |
| `feature` | KarateJs | Get feature info (name, description, prefixedPath, fileName, parentDir) |
| `exec(command)` | KarateJs | Execute system command |
| `extract(text, regex, group)` | KarateJsApi | Extract regex match |
| `extractAll(text, regex, group)` | KarateJsApi | Extract all regex matches |
| `fail(message)` | KarateJs | Fail scenario with message |
| `filter(list, fn)` | KarateJsApi | Filter list by predicate |
| `filterKeys(map, keys)` | KarateJsApi | Filter map by keys |
| `forEach(collection, fn)` | KarateJsApi | Iterate collection |
| `fork(options)` | KarateJs | Fork background process |
| `fromJson(string)` | KarateJsApi | Parse JSON string |
| `fromString(text)` | KarateJs | Parse as JSON/XML or return string |
| `get(name, path?)` | KarateJs | Get variable value |
| `http(url)` | KarateJs | Create HTTP client |
| `info` | KarateJs | Get scenario info |
| `jsonPath(obj, path)` | KarateJsApi | Apply JSONPath expression |
| `keysOf(map)` | KarateJsApi | Get map keys |
| `log(args...)` | KarateJs | Log message |
| `lowerCase(value)` | KarateJsApi | Lowercase string/JSON/XML |
| `map(list, fn)` | KarateJsApi | Transform list |
| `mapWithKey(list, key)` | KarateJsApi | Wrap list items in maps |
| `match(actual, expected)` | KarateJs | Match assertion |
| `merge(maps...)` | KarateJsApi | Merge maps |
| `os` | KarateJs | Get OS info |
| `pause(ms)` | KarateJsApi | Sleep for milliseconds |
| `pretty(value)` | KarateJsApi | Format as pretty JSON/XML |
| `prettyXml(value)` | KarateJsApi | Format as pretty XML |
| `proceed()` | KarateJs | Proceed in mock (passthrough) |
| `properties` | KarateJs | Get system properties |
| `range(start, end, step?)` | KarateJsApi | Generate number range |
| `read(path)` | KarateJs | Read file content |
| `readAsBytes(path)` | KarateJs | Read file as bytes |
| `readAsString(path)` | KarateJs | Read file as string |
| `remove(name, path)` | KarateJs | Remove from variable |
| `repeat(count, fn)` | KarateJsApi | Generate list by repeating function |
| `scenario` | KarateJs | Get scenario info (name, description, line, sectionIndex, exampleIndex, exampleData) |
| `scenarioOutline` | KarateJs | Get scenario outline info (null if not in outline) |
| `set(name, path?, value)` | KarateJs | Set variable value |
| `setup()` | KarateJs | Get setup scenario result |
| `setupOnce()` | KarateJs | Get cached setup result |
| `setXml(name, path, value)` | KarateJs | Set XML value |
| `signal(value)` | KarateJs | Signal to listener |
| `sizeOf(value)` | KarateJsApi | Get size of list/map/string |
| `sort(list, fn)` | KarateJsApi | Sort list by key function |
| `start(options)` | KarateJs | Start mock server |
| `tags` | KarateJs | Get effective tags list (feature + scenario) |
| `tagValues` | KarateJs | Get tag values map (tag name → list of values) |
| `toBean(obj, className)` | KarateJsApi | Convert to Java bean |
| `toBytes(list)` | KarateJsApi | Convert number list to byte[] |
| `toCsv(list)` | KarateJsApi | Convert list of maps to CSV |
| `toJava(value)` | KarateJs | No-op (V1 compat) |
| `toJson(value, removeNulls?)` | KarateJsApi | Convert to JSON |
| `toString(value)` | KarateJsApi | Convert to string (JSON/XML aware) |
| `toStringPretty(value)` | KarateJs | Pretty format value |
| `typeOf(value)` | KarateJsApi | Get Karate type name |
| `urlDecode(string)` | KarateJsApi | URL decode |
| `urlEncode(string)` | KarateJsApi | URL encode |
| `valuesOf(map)` | KarateJsApi | Get map values |
| `xmlPath(xml, path)` | KarateJs | Apply XPath expression |

### Pending (Non-UI/Extension)

| Method | Description | Notes |
|--------|-------------|-------|
| `logger` | Log facade (debug/info/warn/error) | Needs LogContext |
| `prevRequest` | Get previous HTTP request | Needs HTTP state |
| `request` | Get current request (mock) | Mock context only |
| `response` | Get current response | Mock context only |
| `readAsStream(path)` | Read as InputStream | Needs root |
| `render(template)` | Render HTML template | Similar to doc |
| `stop(port)` | Wait for port to close | Polling |
| `toAbsolutePath(path)` | Convert to absolute | Needs root |
| `waitForHttp(url, options)` | Poll HTTP endpoint | Polling |
| `waitForPort(host, port)` | Wait for port available | Polling |
| `write(path, content)` | Write to file | Needs root |

### Out of Scope (UI/Extensions)

| Method | Reason |
|--------|--------|
| `driver`, `robot` | Browser/UI not in V2 |
| `channel()`, `consume()` | Kafka extension |
| `webSocket()`, `webSocketBinary()` | WebSocket not yet |
| `compareImage()` | UI testing |

### Pending V1 Parity (Advanced)

| Feature | V1 Pattern | Notes |
|---------|-----------|-------|
| Java Function as callable | `Hello.sayHelloFactory()` returns `Function<String,String>` | JS engine needs to wrap `java.util.function.Function`, `Callable`, `Runnable`, `Predicate` as `JsCallable` |
| callSingle returning Java fn | `karate.callSingle('file.js')` where JS returns Java Function | Depends on above |

---

## Configure Keys

### Implemented
`ssl`, `proxy`, `readTimeout`, `connectTimeout`, `followRedirects`, `headers`, `cookies`, `charset`

### TODO
| Key | Priority |
|-----|----------|
| `url` | High |
| `lowerCaseResponseHeaders` | Medium |
| `logPrettyRequest/Response` | Medium |
| `printEnabled` | Medium |
| `retry` | Medium |
| `report` | Medium |
| `httpRetryEnabled` | Low |
| `localAddress` | Low |
| `ntlmAuth` | Low |
| `callSingleCache` | Low |
| `continueOnStepFailure` | Low |

### Out of Scope
`driver`, `robot`, `driverTarget`, `kafka`, `grpc`, `websocket`, `webhook`, `responseHeaders`, `responseDelay`, `cors`

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

### Priority 8: Configure Report

```cucumber
* configure report = { showJsLineNumbers: true }
```

Controls report verbosity, JS line-level capture, HTTP detail, payload size limits.

See [HTML_REPORTS.md](./HTML_REPORTS.md) for specification.

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
