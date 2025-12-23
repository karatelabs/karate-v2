# Karate v2 Logging

This document describes the logging architecture in Karate v2.

> See also: [RUNTIME.md](./RUNTIME.md) | [CLI.md](./CLI.md)

---

## Overview

Karate v2 has two distinct logging systems:

| System | Purpose | Output |
|--------|---------|--------|
| **LogContext** | Test logs (`print`, `karate.log`, HTTP) | Reports, cascade to SLF4J |
| **JvmLogger** | Framework/infrastructure logs | JUL or custom appender |

```
┌─────────────────────────────────────────────────────────────┐
│  Test Execution                                              │
│                                                              │
│  print "hello"  ──┐                                          │
│  karate.log()   ──┼──► LogContext ──► Report Buffer          │
│  HTTP logs      ──┘         │                                │
│                             └──► Cascade (optional)          │
│                                      │                       │
│                                      ▼                       │
│                               Slf4jCascade ──► SLF4J         │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Framework Infrastructure                                    │
│                                                              │
│  JvmLogger.info() ──► LogAppender ──► JUL / STDERR / Custom │
└─────────────────────────────────────────────────────────────┘
```

---

## Test Logs (LogContext)

`LogContext` is a thread-local log collector that captures all test output:

- `print` statements
- `karate.log()` calls
- HTTP request/response logs
- Match failures and assertions

### Basic Usage

```gherkin
Scenario: Logging example
  * print 'Starting test'
  * karate.log('Debug info:', someVar)
  * url 'https://api.example.com'
  * method get
  # All output captured in LogContext
```

### karate.logger API

Use `karate.logger` for level-aware logging in your tests:

```gherkin
Scenario: Level-aware logging
  * karate.logger.debug('Detailed debug info:', data)
  * karate.logger.info('Processing item:', itemId)
  * karate.logger.warn('Rate limit approaching')
  * karate.logger.error('Failed to connect:', error)
```

The `karate.log()` method is equivalent to `karate.logger.info()`.

### Log Level Filtering

Control which log levels appear in reports:

**In karate-config.js:**
```javascript
function fn() {
  karate.configure('report', { logLevel: 'warn' });  // Only WARN and ERROR
  return { };
}
```

**CLI:**
```bash
karate run --log-level debug features/   # Include DEBUG and above
karate run -L warn features/              # Only WARN and ERROR
```

**Runner API:**
```java
Runner.path("features/")
    .logLevel("info")     // INFO, WARN, ERROR
    .parallel(5);
```

**karate-pom.json:**
```json
{
  "paths": ["features/"],
  "output": {
    "logLevel": "debug"
  }
}
```

| Level | Shows |
|-------|-------|
| `trace` | All logs |
| `debug` | DEBUG, INFO, WARN, ERROR |
| `info` | INFO, WARN, ERROR (default) |
| `warn` | WARN, ERROR only |
| `error` | ERROR only |

### Accessing Logs Programmatically

```java
LogContext ctx = LogContext.get();
ctx.log("Custom message");
ctx.log("Value: {}", someValue);  // SLF4J-style formatting

String allLogs = ctx.collect();  // Get captured logs
```

### Embedding Content

`LogContext` also collects embedded content (HTML, images, etc.) for inclusion in reports:

```java
LogContext ctx = LogContext.get();

// Embed HTML content
ctx.embed(htmlBytes, "text/html");

// Embed with optional name
ctx.embed(imageBytes, "image/png", "screenshot.png");

// Collect embeds (typically done by StepExecutor)
List<StepResult.Embed> embeds = ctx.collectEmbeds();
```

The `doc` keyword uses this system to embed rendered templates:

```gherkin
* doc 'report.html'  # Rendered HTML is embedded in step result
```

Embeds appear in HTML reports and are included in the JSON output as Base64-encoded data.

### Forwarding to SLF4J (Cascade)

By default, test logs only go to reports. To also forward to SLF4J:

```java
import io.karatelabs.log.Slf4jCascade;
import io.karatelabs.log.LogContext;

// Forward test logs to SLF4J category "karate.run"
LogContext.setCascade(Slf4jCascade.create());

// Custom category
LogContext.setCascade(Slf4jCascade.create("myapp.tests"));
```

The cascade is level-aware: logs from `karate.logger.debug()` go to SLF4J at DEBUG level, `karate.logger.warn()` at WARN level, etc.

Configure in logback.xml:
```xml
<logger name="karate.run" level="DEBUG">
    <appender-ref ref="FILE" />
</logger>
```

---

## Framework Logs (JvmLogger)

`JvmLogger` handles framework/infrastructure logging (not test output):

```java
import io.karatelabs.log.JvmLogger;

JvmLogger.info("Starting suite");
JvmLogger.debug("Loading config from {}", path);
JvmLogger.warn("Config not found: {}", path);
JvmLogger.error("Execution failed", exception);
```

### Log Levels

```java
public enum LogLevel {
    TRACE,  // Finest detail
    DEBUG,  // Debugging info
    INFO,   // Normal operation
    WARN,   // Potential issues
    ERROR   // Failures
}
```

### Custom Appender

```java
import io.karatelabs.log.LogAppender;
import io.karatelabs.log.JvmLogger;

// Route to custom logging system
JvmLogger.setAppender((level, format, args) -> {
    MyLogger.log(level.name(), String.format(format, args));
});
```

---

## Log Masking

Mask sensitive data in logs to prevent credential leaks.

### CLI

```bash
# Single preset
karate run --log-mask PASSWORDS features/

# Multiple presets
karate run --log-mask PASSWORDS,CREDIT_CARDS features/

# All sensitive data
karate run --log-mask ALL_SENSITIVE features/
```

### karate-pom.json

```json
{
  "paths": ["features/"],
  "logMask": {
    "presets": ["PASSWORDS", "CREDIT_CARDS"],
    "patterns": [
      {"regex": "secret[=:]\\s*([^\\s]+)", "replacement": "***"}
    ],
    "headers": ["Authorization", "X-Api-Key"]
  }
}
```

### Runner.Builder API

```java
import io.karatelabs.log.LogMask;
import io.karatelabs.log.LogMaskPreset;

Runner.path("features/")
    .logMask(LogMask.builder()
        .preset(LogMaskPreset.PASSWORDS)
        .preset(LogMaskPreset.CREDIT_CARDS)
        .pattern("secret[=:]\\s*([^\\s]+)", "***")
        .headerMask("Authorization", "***")
        .headerMask("X-Api-Key", "***")
        .build())
    .parallel(5);
```

### Built-in Presets

| Preset | Description |
|--------|-------------|
| `PASSWORDS` | password, passwd, pwd, secret fields |
| `CREDIT_CARDS` | 16-digit numbers (keeps last 4) |
| `SSN` | Social security numbers (xxx-xx-xxxx) |
| `EMAILS` | Masks local part of email addresses |
| `API_KEYS` | Authorization, X-Api-Key headers |
| `BEARER_TOKENS` | Bearer token values |
| `BASIC_AUTH` | Basic auth credentials |
| `ALL_SENSITIVE` | Combines all above presets |

---

## Console Output

### Summary Output

Control console summary after test execution:

```java
Runner.path("features/")
    .outputConsoleSummary(true)   // default: print summary
    .parallel(5);
```

### ANSI Colors

Console output uses ANSI colors for readability:

| Element | Color |
|---------|-------|
| Pass | Green |
| Fail | Red |
| Skip | Yellow |
| Info | Cyan |
| Headers | Bold |

Disable colors:
```bash
karate run --no-color features/
```

Or programmatically:
```java
Console.setColorsEnabled(false);
```

---

## Configuration Priority

For log masking and other settings:

**CLI Entry Point:**
```
CLI flags > KARATE_OPTIONS > karate-pom.json > system props > defaults
```

**Java API Entry Point:**
```
Runner.Builder API > system props > defaults
```

---

## Migration from V1

### HttpLogModifier

V1 used a Java interface for HTTP log modification:

```java
// V1 (deprecated)
public interface HttpLogModifier {
    boolean enableForUri(String uri);
    String header(String header, String value);
    String request(String uri, String request);
    String response(String uri, String response);
}
```

V2 replaces this with declarative `LogMask`:

```java
// V2
Runner.path("features/")
    .logMask(LogMask.builder()
        .pattern("token=([^&]+)", "token=***")
        .headerMask("Authorization")
        .build())
    .parallel(5);
```

### SLF4J Dependency

V1 included SLF4J. V2 uses JUL (java.util.logging) by default.

To use logback with V2:
1. Add `jul-to-slf4j` bridge to your classpath
2. Configure in your logging setup

---

## Implementation Classes

| Class | Purpose |
|-------|---------|
| `io.karatelabs.log.LogContext` | Thread-local test log collector |
| `io.karatelabs.log.JvmLogger` | Framework logger |
| `io.karatelabs.log.LogAppender` | Pluggable log output |
| `io.karatelabs.log.LogLevel` | Log level enum |
| `io.karatelabs.log.Slf4jCascade` | Forward test logs to SLF4J |
| `io.karatelabs.log.LogMask` | Log masking (future) |
| `io.karatelabs.log.LogMaskPreset` | Built-in masking patterns (future) |

---

## TODO: JS Line-Level Logging

> **Status:** Not yet implemented

Capture JS line-of-code execution for reports, similar to how Gherkin steps are displayed.

**Goal:** When executing `.karate.js` scripts or embedded JS, show each line execution in the report:
```
1: var proc = karate.fork({ args: ['node', 'server.js'] })
2: proc.waitForPort('localhost', 8080, 30, 250)
3: var response = http.get()
```

**Implementation approach:**
- JS engine instrumentation to emit line execution events
- Opt-in via `configure report = { showJsLineNumbers: true }`
- Integrate with `LogContext` for report capture
- Performance overhead acceptable since opt-in

**Related:**
- [HTML_REPORTS.md](./HTML_REPORTS.md) - Configure report TODO
- [RUNTIME.md](./RUNTIME.md) - Priority 7 (JS Script Execution)
