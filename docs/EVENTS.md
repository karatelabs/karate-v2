# Karate v2 Event System

> See also: [RUNTIME.md](./RUNTIME.md) | [JS_ENGINE.md](./JS_ENGINE.md) | [REPORTS.md](./REPORTS.md) | [PRINCIPLES.md](./PRINCIPLES.md)

---

## Overview

The event system provides a unified way to observe and control test execution. It replaces the separate `RuntimeHook` and `ResultListener` interfaces with a single, consistent API.

**Design inspiration:** `karate-js` Event/ContextListener pattern (see `karate-js/src/main/java/io/karatelabs/js/Event.java`)

**Reference implementation:** Current `RuntimeHook` usage in `karate-core/src/main/java/io/karatelabs/core/` (Suite.java, FeatureRuntime.java, ScenarioRuntime.java, StepExecutor.java)

---

## Key Design Decisions

### Thread Safety

Events are fired on different threads during parallel execution. Listeners must be thread-safe:
- Avoid shared mutable state
- Use thread-safe collections if aggregating data
- Consider using `Suite` as a synchronization point for shared state

### All Calls Tracked

Events fire for **all** features and scenarios, including `call`ed ones:

```gherkin
Feature: Main test        # FEATURE_ENTER fires
  Scenario: Test          # SCENARIO_ENTER fires
    * call read('helper.feature')  # FEATURE_ENTER fires for helper too
```

**Rationale:** Debuggers and advanced tooling need visibility into nested calls for:
- Step-in/step-out navigation
- Stack frame tracking
- Variable inspection at any call depth

Use `event.isTopLevel()` to filter if needed (e.g., for reporting).

### JavaScript Event Handling

> **Out of scope:** JS event handling will be provided as a separate optional library in the future. The core event system is Java-only.

---

## Architecture

```
Suite.fireEvent(RunEvent)
         ↓
   RunListener.onEvent(RunEvent)
         ↓
   ┌─────┴─────┐
   │  return   │
   │  boolean  │
   └─────┬─────┘
         ↓
   true = continue, false = skip (for ENTER events)
```

---

## Core Classes

### RunEventType

Location: `karate-core/src/main/java/io/karatelabs/core/RunEventType.java`

```java
public enum RunEventType {
    SUITE_ENTER,
    SUITE_EXIT,
    FEATURE_ENTER,
    FEATURE_EXIT,
    SCENARIO_ENTER,
    SCENARIO_EXIT,
    STEP_ENTER,
    STEP_EXIT,
    ERROR,
    PROGRESS  // periodic progress updates
}
```

**Naming rationale:** Uses ENTER/EXIT to match `karate-js` EventType pattern (`CONTEXT_ENTER`, `STATEMENT_EXIT`, etc.) and to convey hierarchical scope semantics.

### RunEvent

Location: `karate-core/src/main/java/io/karatelabs/core/RunEvent.java`

Contains full runtime objects (not just data) for maximum flexibility.

```java
public class RunEvent {

    private final RunEventType type;
    private final Suite suite;
    private final FeatureRuntime featureRuntime;  // null for suite events
    private final ScenarioRuntime scenarioRuntime; // null for suite/feature events
    private final Step step;                       // null except step events
    private final Object result;                   // StepResult, ScenarioResult, etc.
    private final Throwable error;                 // non-null for ERROR events

    // Getters
    public RunEventType getType() { ... }
    public Suite getSuite() { ... }
    public FeatureRuntime getFeatureRuntime() { ... }
    public ScenarioRuntime getScenarioRuntime() { ... }
    public Step getStep() { ... }
    public StepResult getStepResult() { ... }
    public ScenarioResult getScenarioResult() { ... }
    public FeatureResult getFeatureResult() { ... }
    public SuiteResult getSuiteResult() { ... }
    public Throwable getError() { ... }

    // Convenience methods
    public boolean isTopLevel() {
        if (scenarioRuntime != null) {
            return scenarioRuntime.caller.depth == 0;
        }
        if (featureRuntime != null) {
            return featureRuntime.caller.depth == 0;
        }
        return true;  // suite events are always top-level
    }

    // Factory methods
    public static RunEvent suiteEnter(Suite suite) { ... }
    public static RunEvent suiteExit(Suite suite, SuiteResult result) { ... }
    public static RunEvent featureEnter(FeatureRuntime fr) { ... }
    public static RunEvent featureExit(FeatureRuntime fr, FeatureResult result) { ... }
    public static RunEvent scenarioEnter(ScenarioRuntime sr) { ... }
    public static RunEvent scenarioExit(ScenarioRuntime sr, ScenarioResult result) { ... }
    public static RunEvent stepEnter(Step step, ScenarioRuntime sr) { ... }
    public static RunEvent stepExit(StepResult result, ScenarioRuntime sr) { ... }
    public static RunEvent error(Throwable error, ScenarioRuntime sr) { ... }
}
```

**Why full objects?** Debuggers need access to:
- `scenarioRuntime.stepBack()`, `stepReset()`, `stepProceed()`
- `scenarioRuntime.engine.vars` for variable inspection
- `scenarioRuntime.caller.depth` for call stack tracking
- `step.getLine()`, `step.getFeature()` for breakpoint resolution

### RunListener

Location: `karate-core/src/main/java/io/karatelabs/core/RunListener.java`

```java
public interface RunListener {

    /**
     * Called for all runtime events (including ERROR).
     * @param event the event
     * @return true to continue execution, false to skip (only meaningful for *_ENTER events)
     */
    default boolean onEvent(RunEvent event) {
        return true;
    }
}
```

**Note:** Error handling uses the `ERROR` event type. Access the exception via `event.getError()`.

### RunListenerFactory

For listeners that need per-thread state (e.g., debuggers):

```java
@FunctionalInterface
public interface RunListenerFactory {
    /**
     * Create a listener instance for the current thread.
     * Called once per execution thread.
     */
    RunListener create();
}
```

**When `create()` is called:** At the start of each parallel execution thread, before any features run on that thread.

**Use case:** The debug adapter creates one `DebugThread` per execution thread, each with its own stack frames and state.

**Suite integration:**
```java
Suite.of("features/")
    .listenerFactory(() -> new MyPerThreadListener())
    .parallel(10)
    .run();
```

---

## Backward Compatibility

### RuntimeHookAdapter

Wraps the new event system to support existing `RuntimeHook` implementations:

```java
public class RuntimeHookAdapter implements RunListener {

    private final RuntimeHook hook;

    public RuntimeHookAdapter(RuntimeHook hook) {
        this.hook = hook;
    }

    @Override
    public boolean onEvent(RunEvent event) {
        return switch (event.getType()) {
            case SUITE_ENTER -> hook.beforeSuite(event.getSuite());
            case SUITE_EXIT -> { hook.afterSuite(event.getSuite()); yield true; }
            case FEATURE_ENTER -> hook.beforeFeature(event.getFeatureRuntime());
            case FEATURE_EXIT -> { hook.afterFeature(event.getFeatureRuntime()); yield true; }
            case SCENARIO_ENTER -> hook.beforeScenario(event.getScenarioRuntime());
            case SCENARIO_EXIT -> { hook.afterScenario(event.getScenarioRuntime()); yield true; }
            case STEP_ENTER -> hook.beforeStep(event.getStep(), event.getScenarioRuntime());
            case STEP_EXIT -> { hook.afterStep(event.getStepResult(), event.getScenarioRuntime()); yield true; }
            default -> true;
        };
    }
}
```

### RuntimeHookFactoryAdapter

For per-thread hooks (like the debug adapter):

```java
public class RuntimeHookFactoryAdapter implements RunListenerFactory {

    private final RuntimeHookFactory factory;

    public RuntimeHookFactoryAdapter(RuntimeHookFactory factory) {
        this.factory = factory;
    }

    @Override
    public RunListener create() {
        return new RuntimeHookAdapter(factory.create());
    }
}
```

### Usage

```java
// Existing RuntimeHook continues to work
Suite.of("features/")
    .hook(existingRuntimeHook)  // internally wrapped with RuntimeHookAdapter
    .run();

// New RunListener API
Suite.of("features/")
    .listener(event -> {
        if (event.getType() == SCENARIO_ENTER && event.isTopLevel()) {
            System.out.println("Starting: " + event.getScenarioRuntime().getScenario().getName());
        }
        return true;
    })
    .run();
```

---

## Control Flow

| Event Type | Return `false` Effect |
|------------|----------------------|
| `*_ENTER` | Skip execution (scenario, step, etc.) |
| `*_EXIT` | Ignored |
| `ERROR` | Ignored |

---

## Event Lifecycle

```
SUITE_ENTER
├── FEATURE_ENTER
│   ├── SCENARIO_ENTER
│   │   ├── STEP_ENTER
│   │   │   └── [execute step]
│   │   ├── STEP_EXIT
│   │   ├── STEP_ENTER
│   │   │   └── [execute step]
│   │   └── STEP_EXIT
│   └── SCENARIO_EXIT
└── FEATURE_EXIT
SUITE_EXIT
```

**Error events:** `ERROR` is fired when an exception occurs during step execution. The corresponding `STEP_EXIT` still fires after `ERROR`, with the failure captured in `StepResult`.

---

## JSONL Event Stream

Karate v2 writes a **unified event stream** to `karate-events.jsonl`. This single file serves both **live progress monitoring** and **report generation**:

- **During execution:** Lightweight events for real-time UIs
- **On feature completion:** Full `toKarateJson()` output for report generation
- **Post-execution:** "Replay" the stream to generate reports

See [REPORTS.md](./REPORTS.md#jsonl-event-stream) for complete event format specification.

### Benefits

- **Decoupled consumers** - External tools don't need classpath access or network protocols
- **Parallel-safe** - Each line is atomic, includes timestamp and thread ID
- **Replayable** - File can be saved and replayed for report aggregation, debugging, or analysis
- **Simple integration** - Consumers just tail the file (poll or watch)
- **Dual-purpose** - Same stream supports live progress AND static report generation
- **Future-proof** - Standard envelope with `data` payload allows schema evolution

### Standard Event Envelope

All events share a common structure:

```json
{
  "type": "EVENT_TYPE",
  "ts": 1703500000200,
  "threadId": "worker-1",
  "data": { ... }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | Event type (UPPER_CASE) |
| `ts` | long | Epoch milliseconds |
| `threadId` | string? | Thread identifier (nullable for suite-level events) |
| `data` | object | Event-specific payload |

### File Format

```jsonl
{"type":"SUITE_ENTER","ts":1703500000000,"threadId":null,"data":{"version":"2.0.0","schemaVersion":"1","env":"dev","threads":5}}
{"type":"FEATURE_ENTER","ts":1703500000010,"threadId":"worker-1","data":{"path":"login.feature","name":"Login Tests","line":1}}
{"type":"SCENARIO_ENTER","ts":1703500000020,"threadId":"worker-1","data":{"feature":"login.feature","name":"Valid login","line":5,"refId":"[1:5]"}}
{"type":"SCENARIO_EXIT","ts":1703500000100,"threadId":"worker-1","data":{"feature":"login.feature","name":"Valid login","line":5,"passed":true,"durationMs":80}}
{"type":"FEATURE_EXIT","ts":1703500000200,"threadId":"worker-1","data":{...full toKarateJson()...}}
{"type":"PROGRESS","ts":1703500005000,"threadId":null,"data":{"completed":15,"total":42,"percent":35,"elapsedMs":5000}}
{"type":"SUITE_EXIT","ts":1703500010000,"threadId":null,"data":{"featuresPassed":10,"featuresFailed":2,"featureSummary":[...]}}
```

### Core Event Types

| Event Type | Description |
|------------|-------------|
| `SUITE_ENTER` | Suite start with metadata (version, schemaVersion, env, threads, paths) |
| `SUITE_EXIT` | Suite end with summary stats and `featureSummary` for HTML reports |
| `FEATURE_ENTER` | Feature start (lightweight: path, name, line) |
| `FEATURE_EXIT` | Feature complete - **full `toKarateJson()` in `data`** |
| `SCENARIO_ENTER` | Scenario start - **key for IDE test runners** |
| `SCENARIO_EXIT` | Scenario complete with status - **key for IDE test runners** |
| `PROGRESS` | Periodic progress update for live dashboards |
| `ERROR` | Explicit error capture |

### Future Event Types (Reserved)

| Event Type | Purpose |
|------------|---------|
| `HTTP` | HTTP request/response capture with cURL export |
| `SCRIPT_ENTER` / `SCRIPT_EXIT` | JavaScript test execution (`.karate.js`) |
| `MOCK_REQUEST` | Mock server request handling |
| `CALL_ENTER` / `CALL_EXIT` | Feature call tracking (opt-in) |
| `STEP_ENTER` / `STEP_EXIT` | Step-level events (opt-in debug) |
| `RETRY` | Scenario retry attempts |
| `ABORT` | Execution aborted |

### Notes

- **Step events not default:** Reduces event volume. Full step details in `FEATURE_EXIT` via `toKarateJson()`.
- **Tag filtering:** `FEATURE_EXIT` contains only executed scenarios (those matching tag filters).
- **Schema versioning:** `schemaVersion` in `SUITE_ENTER.data` allows consumers to handle format changes.
- **`threadId` as string:** Allows flexibility for future thread naming schemes.

### Consumer Pattern

External tools can consume events by tailing the file:

```java
long lastPosition = 0;
while (running) {
    try (RandomAccessFile raf = new RandomAccessFile(jsonlFile, "r")) {
        raf.seek(lastPosition);
        String line;
        while ((line = raf.readLine()) != null) {
            JsonObject event = parseJson(line);
            String type = event.getString("type");
            JsonObject data = event.getJsonObject("data");
            handleEvent(type, data);
        }
        lastPosition = raf.getFilePointer();
    }
    Thread.sleep(100);  // poll interval
}
```

Alternatively, use `java.nio.file.WatchService` for file change notifications.

### Use Cases

- **Real-time test dashboards** - Stream lightweight events as tests run
- **Static HTML reports** - Extract `FEATURE_EXIT` events, render with Alpine.js
- **Cucumber JSON generation** - Convert `FEATURE_EXIT` events to cucumber format
- **HTTP traffic analysis** - Future: analyze `HTTP` events
- **Report aggregation** - Combine JSONL from multiple runs/machines
- **CI/CD integration** - Parse events for build status, notifications
- **Debugging** - Replay execution sequence for analysis

---

## Usage

### Java API

```java
Suite.of("features/")
    .listener(event -> {
        switch (event.getType()) {
            case SCENARIO_ENTER -> {
                var scenario = event.getScenarioRuntime().getScenario();
                System.out.println("Starting: " + scenario.getName());
            }
            case STEP_EXIT -> {
                StepResult sr = event.getStepResult();
                if (sr != null && sr.isFailed()) {
                    System.out.println("Step failed: " + event.getStep().getText());
                }
            }
        }
        return true;
    })
    .parallel(10)
    .run();
```

### Skipping Scenarios

```java
Suite.of("features/")
    .listener(event -> {
        if (event.getType() == RunEventType.SCENARIO_ENTER) {
            String name = event.getScenarioRuntime().getScenario().getName();
            if (name.contains("slow")) {
                return false;  // skip this scenario
            }
        }
        return true;
    })
    .run();
```

---

## Migration from RuntimeHook

`RuntimeHook` remains supported via `RuntimeHookAdapter`. For new code, prefer `RunListener`:

| RuntimeHook Method | RunListener Equivalent |
|-------------------|------------------------|
| `beforeSuite(Suite)` | `onEvent(event)` where `event.type == SUITE_ENTER` |
| `afterSuite(Suite)` | `onEvent(event)` where `event.type == SUITE_EXIT` |
| `beforeFeature(FeatureRuntime)` | `onEvent(event)` where `event.type == FEATURE_ENTER` |
| `afterFeature(FeatureRuntime)` | `onEvent(event)` where `event.type == FEATURE_EXIT` |
| `beforeScenario(ScenarioRuntime)` | `onEvent(event)` where `event.type == SCENARIO_ENTER` |
| `afterScenario(ScenarioRuntime)` | `onEvent(event)` where `event.type == SCENARIO_EXIT` |
| `beforeStep(Step, ScenarioRuntime)` | `onEvent(event)` where `event.type == STEP_ENTER` |
| `afterStep(StepResult, ScenarioRuntime)` | `onEvent(event)` where `event.type == STEP_EXIT` |

**Return value:** Both `RuntimeHook.beforeX()` and `RunListener.onEvent()` return boolean to control flow.

---

## Implementation TODOs

### Phase 1: Core Classes
- [ ] Create `RunEventType.java` enum
- [ ] Create `RunEvent.java` with full runtime object access and `isTopLevel()`
- [ ] Create `RunListener.java` interface
- [ ] Create `RunListenerFactory.java` for per-thread listeners

### Phase 2: Suite Integration
- [ ] Add `List<RunListener>` and `List<RunListenerFactory>` to Suite
- [ ] Add `Suite.listener(RunListener)` builder method
- [ ] Add `Suite.listenerFactory(RunListenerFactory)` builder method
- [ ] Add `boolean Suite.fireEvent(RunEvent)` internal method
- [ ] Create per-thread listeners from factories at thread start

### Phase 3: Runtime Integration

Integration points (see existing `RuntimeHook` calls for reference):

| File | Location | Event |
|------|----------|-------|
| `Suite.java` | `run()` method, before/after feature loop | SUITE_ENTER, SUITE_EXIT |
| `FeatureRuntime.java` | `call()` method, before/after scenario loop | FEATURE_ENTER, FEATURE_EXIT |
| `ScenarioRuntime.java` | `call()` method, before/after step loop | SCENARIO_ENTER, SCENARIO_EXIT |
| `StepExecutor.java` | `execute()` method, before/after step execution | STEP_ENTER, STEP_EXIT |

- [ ] Refactor FeatureRuntime to use `fireEvent()`
- [ ] Refactor ScenarioRuntime to use `fireEvent()`
- [ ] Refactor StepExecutor to use `fireEvent()`

### Phase 4: Backward Compatibility
- [ ] Create `RuntimeHookAdapter.java` wrapping RuntimeHook as RunListener
- [ ] Create `RuntimeHookFactoryAdapter.java` for per-thread hooks
- [ ] Keep `RuntimeHook.java` for backward compatibility (mark as legacy)
- [ ] Create `RunListenerTest.java`

### Phase 5: JSONL Event Stream
- [ ] Implement `JsonLinesEventWriter` that writes RunEvents to `karate-events.jsonl`
- [ ] Standard event envelope: `{"type":"...","ts":...,"threadId":"...","data":{...}}`
- [ ] `threadId` as nullable string for flexibility
- [ ] `schemaVersion` in `SUITE_ENTER.data` for forward compatibility
- [ ] Lightweight events for ENTER types (name, path, line only)
- [ ] `FEATURE_EXIT.data` contains full `toKarateJson()` output - **single source of truth**
- [ ] `SUITE_EXIT.data` includes `featureSummary` array for `karate-summary.html`
- [ ] Ensure atomic line writes (thread-safe)
- [ ] Add `Suite.outputJsonLines(true)` builder method
- [ ] Add `PROGRESS` event type for progress tracking
- [ ] Periodic console progress summary (every 10 seconds, Gatling-style):
  ```
  ================================================================================
  ---- Progress (10s elapsed) ----------------------------------------------------
  [████████████░░░░░░░░░░░░░░░░░░] 42% | 15/35 scenarios | 3 passed | 0 failed
  ================================================================================
  ```

### Phase 6: Report Generation from JSONL
- [ ] Remove direct `CucumberJsonWriter` - render Cucumber JSON from JSONL instead
- [ ] `CucumberJsonConverter.fromJsonLines(path)` - filter `FEATURE_EXIT` events, convert to cucumber format
- [ ] HTML summary from `SUITE_EXIT.data.featureSummary`
- [ ] HTML feature pages from `FEATURE_EXIT.data`
- [ ] Review JSONL event data sufficiency for external tools:
  - Allure report generation
  - ReportPortal integration
  - JIRA / X-Ray test management cross-referencing
  - Requirements traceability
- [ ] Document JSONL → report format conversion utilities in [REPORTS.md](./REPORTS.md)

### Phase 7: Future Event Types
- [ ] `HTTP` event for request/response capture with cURL export
- [ ] `SCRIPT_ENTER` / `SCRIPT_EXIT` for `.karate.js` execution
- [ ] `MOCK_REQUEST` for mock server requests
- [ ] `CALL_ENTER` / `CALL_EXIT` for feature call tracking (opt-in)
- [ ] `STEP_ENTER` / `STEP_EXIT` for step-level debugging (opt-in)
- [ ] `RETRY` for scenario retry attempts
- [ ] `ABORT` for execution aborted

### Future: JavaScript Event Handling (Separate Library)

> Out of scope for core. Will be provided as an optional library.

- [ ] `karate-boot.js` suite-level hook for event registration
- [ ] `karate.on(eventType, callback)` API

---

## Design Decisions

### Why ENTER/EXIT instead of BEFORE/AFTER or START/END?

1. **Internal consistency:** Matches `karate-js` EventType (`CONTEXT_ENTER`, `STATEMENT_EXIT`)
2. **Semantic clarity:** Test execution is hierarchical scope traversal - you *enter* a scope, execute, then *exit*
3. **Symmetry:** Both words are similar length, clear antonyms

### Why a single onEvent() instead of multiple methods?

1. **Simpler interface:** One method to implement instead of 8
2. **Pattern matching:** Modern Java switch expressions make dispatch elegant
3. **Extensibility:** New event types don't require interface changes
4. **Consistency:** Matches `karate-js` ContextListener pattern

### Why replace RuntimeHook instead of extending it?

1. **Cleaner API:** Single unified interface vs two separate ones
2. **Reduced complexity:** One dispatch mechanism instead of two
3. **JavaScript parity:** Same API works in Java and JS
