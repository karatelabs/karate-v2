# Karate v2 Runtime Design

This document describes the runtime architecture for executing Karate tests in v2. The design leverages the AST-based GherkinParser and embedded JavaScript engine for a clean, maintainable implementation.

> See also: [GHERKIN-PARSER-REWRITE.md](./GHERKIN-PARSER-REWRITE.md) | [JS-JAVA-INTEROP.md](./JS-JAVA-INTEROP.md) | [PRINCIPLES.md](./PRINCIPLES.md)

---

## Design Philosophy

### v1 Problems (What We're Fixing)

The v1 runtime used regex-based step matching via `@When` annotations:

```java
// v1 approach - BAD: string manipulation, regex matching
@When("^match (.+)(=+)(.+)$")
public void matchEquals(String expression, String eqSymbol, String expected) {
    // String manipulation to figure out what to do
}
```

**Problems:**
- Complex regex patterns that are hard to maintain
- String manipulation to extract operands
- No leverage of the parsed AST structure
- Difficult to extend with new syntax

### v2 Approach (AST-Based)

In v2, the GherkinParser already extracts:
- `step.keyword` → action identifier (`match`, `def`, `url`, `method`, etc.)
- `step.text` → expression to evaluate (parsed by JsParser)

```java
// v2 approach - GOOD: AST-driven, clean dispatch
switch (step.getKeyword()) {
    case "match" -> executeMatch(step);
    case "def" -> executeDef(step);
    case "url" -> executeUrl(step);
    case "method" -> executeMethod(step);
    // ...
}
```

**Benefits:**
- Clean keyword-based dispatch
- Expressions parsed by JsParser (proper AST)
- No regex, no string manipulation
- Easy to extend

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Suite                                       │
│  - Configuration (karate-config.js)                                     │
│  - Tag filtering                                                        │
│  - Parallel execution settings                                          │
│  - Runtime hooks                                                        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          FeatureRuntime                                  │
│  - Owns Feature domain object                                           │
│  - Iterates scenarios (with filtering)                                  │
│  - Manages parallel execution via virtual threads                       │
│  - CALLONCE_CACHE, SETUPONCE_CACHE                                      │
│  - Before/After feature hooks                                           │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         ScenarioRuntime                                  │
│  - Owns Scenario domain object                                          │
│  - Manages JS Engine instance                                           │
│  - Variable scope (local + inherited)                                   │
│  - Step iteration and execution                                         │
│  - Before/After scenario hooks                                          │
│  - Result collection                                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          StepExecutor                                    │
│  - Keyword-based dispatch (no regex!)                                   │
│  - Uses JsParser for expression parsing                                 │
│  - Before/After step hooks                                              │
│  - Result (pass/fail/skip) with timing                                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
              ┌─────────┐    ┌─────────┐    ┌─────────┐
              │  Match  │    │  Http   │    │  Other  │
              │ Engine  │    │ Client  │    │ Actions │
              └─────────┘    └─────────┘    └─────────┘
```

---

## Core Classes

### KarateJs - The Reusable Execution Context

`KarateJs` (already exists in `karate-core`) is the foundational execution context. It owns:
- **Engine** - JS engine with variables/state
- **HttpClient** + **HttpRequestBuilder** - HTTP execution
- **karate bridge** - JS-accessible `karate.*` functions

This pattern is already proven - it's used in external projects like `PmRuntime` (Postman emulation) which wraps `KarateJs` and adds domain-specific functionality.

```java
// KarateJs is reusable - can be embedded in different runtimes
public class KarateJs implements SimpleObject {
    public final Resource root;
    public final Engine engine;           // JS engine with variable state
    public final HttpClient client;
    public final HttpRequestBuilder http;
    // ... karate.* functions (read, match, http, etc.)
}
```

### Suite

Top-level orchestrator for test execution. Configures the overall test run.

```java
package io.karatelabs.core;

public class Suite {

    // Configuration
    private final Config config;
    private final String env;                    // karate.env
    private final String tagSelector;            // tag expression filter

    // Execution
    private final ExecutorService executor;      // virtual threads
    private final int threadCount;
    private final boolean parallel;
    private final boolean dryRun;

    // Hooks
    private final List<RuntimeHook> hooks;

    // Results
    private final List<FeatureResult> results;

    // Caches (shared across features)
    private final Map<String, Object> CALLONCE_CACHE;

    public static Suite of(String... paths) { ... }

    public Suite parallel(int threads) { ... }
    public Suite tags(String tagExpression) { ... }
    public Suite env(String env) { ... }
    public Suite hook(RuntimeHook hook) { ... }

    public SuiteResult run() { ... }
}
```

**Key Design Decisions:**
- Uses **virtual threads** (Java 21+) for parallel execution
- Implements `Callable<SuiteResult>` for composability
- Configuration via builder pattern
- Thread-safe result collection

### FeatureRuntime

Executes a single feature file.

```java
package io.karatelabs.core;

public class FeatureRuntime implements Callable<FeatureResult> {

    private final Suite suite;
    private final Feature feature;
    private final FeatureRuntime caller;        // for nested calls
    private final Map<String, Object> callArg;  // arguments from caller

    // Caches (feature-level)
    final Map<String, Object> CALLONCE_CACHE;
    final Map<String, Object> SETUPONCE_CACHE;

    // State
    private ScenarioRuntime lastExecuted;
    private FeatureResult result;

    public static FeatureRuntime of(Feature feature) { ... }
    public static FeatureRuntime of(Suite suite, Feature feature) { ... }

    @Override
    public FeatureResult call() {
        beforeFeature();
        for (Scenario scenario : selectedScenarios()) {
            ScenarioRuntime sr = new ScenarioRuntime(this, scenario);
            sr.call();
            result.addScenarioResult(sr.getResult());
        }
        afterFeature();
        return result;
    }

    // Scenario filtering based on tags, line numbers, names
    private Iterable<Scenario> selectedScenarios() { ... }

    // Resource resolution relative to feature file
    public Resource resolve(String path) { ... }
}
```

**Key Design Decisions:**
- Implements `Callable<FeatureResult>` for clean parallel execution
- Scenario iteration via iterator (memory efficient for large outlines)
- Separate caches for `callonce` vs `setuponce`
- Resource resolution relative to feature file location

### ScenarioRuntime

Executes a single scenario. **Wraps KarateJs** to leverage the existing execution context.

```java
package io.karatelabs.core;

public class ScenarioRuntime implements Callable<ScenarioResult> {

    private final FeatureRuntime featureRuntime;
    private final Scenario scenario;

    // KarateJs is the execution context - owns Engine, Http, etc.
    private final KarateJs karate;

    private final StepExecutor executor;

    // State
    private final ScenarioResult result;
    private Step currentStep;
    private boolean stopped;
    private boolean aborted;
    private Throwable error;

    // Magic variables (separate from engine bindings)
    private final Map<String, Object> magicVars;

    public ScenarioRuntime(FeatureRuntime featureRuntime, Scenario scenario) {
        this.featureRuntime = featureRuntime;
        this.scenario = scenario;

        // KarateJs owns the Engine and Http infrastructure
        Resource featureResource = featureRuntime.getFeature().getResource();
        this.karate = new KarateJs(featureResource);

        this.executor = new StepExecutor(this);
        this.result = new ScenarioResult(scenario);
        this.magicVars = initMagicVariables();
        initEngine();
    }

    private void initEngine() {
        // KarateJs already sets up "karate" object
        // Add scenario-specific variables

        // Inherit parent variables if called
        if (featureRuntime.getCaller() != null) {
            inheritVariables();
        }

        // Set example data for outline scenarios
        if (scenario.isOutlineExample()) {
            for (var entry : scenario.getExampleData().entrySet()) {
                karate.engine.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public ScenarioResult call() {
        beforeScenario();
        try {
            List<Step> steps = scenario.getStepsIncludingBackground();
            for (Step step : steps) {
                if (stopped) break;
                currentStep = step;
                StepResult sr = executor.execute(step);
                result.addStepResult(sr);
                if (sr.isFailed()) {
                    stopped = true;
                    error = sr.getError();
                }
            }
        } finally {
            afterScenario();
        }
        return result;
    }

    // Delegate to KarateJs for expression evaluation
    public Object eval(String expression) {
        return karate.engine.eval(expression);
    }

    public void setVariable(String name, Object value) {
        karate.engine.put(name, value);
    }

    public Object getVariable(String name) {
        return karate.engine.get(name);
    }

    // Access to HTTP for step execution
    public HttpRequestBuilder getHttp() {
        return karate.http;
    }

    public KarateJs getKarate() {
        return karate;
    }
}
```

**Key Design Decisions:**
- **Wraps KarateJs** rather than duplicating Engine/Http ownership
- KarateJs provides: Engine, HttpClient, HttpRequestBuilder, karate bridge
- ScenarioRuntime adds: Gherkin lifecycle, step iteration, results, hooks
- Implements `Callable<ScenarioResult>` for parallelism
- Magic variables separate from engine bindings
- This follows the same pattern as `PmRuntime` wrapping `KarateJs`

### StepExecutor

Executes individual steps using AST-based dispatch. Has access to the ScenarioRuntime (and through it, KarateJs).

```java
package io.karatelabs.core;

public class StepExecutor {

    private final ScenarioRuntime runtime;
    private final HttpActions http;
    private final MatchActions match;

    public StepExecutor(ScenarioRuntime runtime) {
        this.runtime = runtime;
        // HttpActions uses runtime.getHttp() (from KarateJs)
        this.http = new HttpActions(runtime);
        // MatchActions uses runtime.eval() (from KarateJs.engine)
        this.match = new MatchActions(runtime);
    }

    public StepResult execute(Step step) {
        long startTime = System.currentTimeMillis();
        long startNanos = System.nanoTime();

        try {
            // Keyword-based dispatch - NO REGEX!
            String keyword = step.getKeyword();
            if (keyword == null) {
                // Plain expression (e.g., "* print foo")
                executeExpression(step);
            } else {
                switch (keyword) {
                    // Variable assignment
                    case "def" -> executeDef(step);
                    case "set" -> executeSet(step);
                    case "remove" -> executeRemove(step);
                    case "table" -> executeTable(step);
                    case "text" -> executeText(step);
                    case "yaml" -> executeYaml(step);
                    case "csv" -> executeCsv(step);
                    case "json" -> executeJson(step);
                    case "xml" -> executeXml(step);
                    case "bytes" -> executeBytes(step);
                    case "copy" -> executeCopy(step);

                    // Assertions
                    case "match" -> match.execute(step);
                    case "assert" -> executeAssert(step);
                    case "print" -> executePrint(step);

                    // HTTP
                    case "url" -> http.url(step);
                    case "path" -> http.path(step);
                    case "param" -> http.param(step);
                    case "params" -> http.params(step);
                    case "header" -> http.header(step);
                    case "headers" -> http.headers(step);
                    case "cookie" -> http.cookie(step);
                    case "cookies" -> http.cookies(step);
                    case "form" -> http.form(step);
                    case "request" -> http.request(step);
                    case "method" -> http.method(step);
                    case "status" -> http.status(step);
                    case "soap" -> http.soap(step);
                    case "multipart" -> http.multipart(step);

                    // Control flow
                    case "call" -> executeCall(step);
                    case "callonce" -> executeCallOnce(step);
                    case "eval" -> executeEval(step);
                    case "retry" -> executeRetry(step);

                    // Config
                    case "configure" -> executeConfigure(step);

                    default -> throw new RuntimeException("unknown keyword: " + keyword);
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            return StepResult.passed(step, startTime, elapsedNanos);

        } catch (AssertionError | Exception e) {
            long elapsedNanos = System.nanoTime() - startNanos;
            return StepResult.failed(step, startTime, elapsedNanos, e);
        }
    }

    // --- Expression Execution (leverages JsParser) ---

    private void executeDef(Step step) {
        // step.text is like "foo = bar" or "foo = { a: 1 }"
        // Parse with JsParser to get proper AST
        String text = step.getText();
        int eqIndex = findAssignmentOperator(text);
        String name = text.substring(0, eqIndex).trim();
        String expr = text.substring(eqIndex + 1).trim();

        Object value = runtime.eval(expr);
        runtime.setVariable(name, value);
    }

    private void executeExpression(Step step) {
        // Plain expression like "print response" or "foo.bar()"
        runtime.eval(step.getText());
    }
}
```

**Key Design Decisions:**
- **No regex matching** - clean `switch` on `step.getKeyword()`
- Expressions parsed by the JS engine
- Modular action classes (`HttpActions`, `MatchActions`)
- Step results with precise timing

---

## Match Execution (AST-Based)

The `match` keyword is critical for Karate. In v2, we parse the match expression properly.

### Match Syntax

```gherkin
* match response == { name: 'foo' }
* match response.items contains { id: 1 }
* match each response.items == { active: true }
* match response !contains { secret: '#notnull' }
```

### AST-Based Parsing

```java
package io.karatelabs.core;

public class MatchActions {

    private final ScenarioRuntime runtime;

    public void execute(Step step) {
        String text = step.getText();

        // Parse the match expression
        MatchExpression expr = parseMatchExpression(text);

        // Evaluate actual value
        Object actual = runtime.eval(expr.actualExpr);

        // Evaluate expected value (if expression, not literal)
        Object expected = expr.expectedExpr != null
            ? runtime.eval(expr.expectedExpr)
            : expr.expectedLiteral;

        // Execute match
        Result result = Match.that(actual)
            .operation(expr.operation)
            .expected(expected)
            .execute();

        if (!result.pass) {
            throw new AssertionError(result.message);
        }
    }

    private MatchExpression parseMatchExpression(String text) {
        // Use a lightweight parser for match syntax
        // Recognizes: each, !contains, contains only, ==, !=, etc.

        MatchExpression expr = new MatchExpression();

        // Check for "each" prefix
        if (text.startsWith("each ")) {
            expr.each = true;
            text = text.substring(5);
        }

        // Find the operator
        for (MatchOperator op : MatchOperator.values()) {
            int idx = text.indexOf(op.symbol);
            if (idx > 0) {
                expr.actualExpr = text.substring(0, idx).trim();
                expr.operation = op;
                String rhs = text.substring(idx + op.symbol.length()).trim();

                // RHS could be expression or literal
                if (rhs.startsWith("{") || rhs.startsWith("[") || rhs.startsWith("'") || rhs.startsWith("\"")) {
                    // Literal JSON/string - parse as JS
                    expr.expectedExpr = rhs;
                } else {
                    // Variable reference
                    expr.expectedExpr = rhs;
                }
                break;
            }
        }

        return expr;
    }
}

enum MatchOperator {
    EQUALS("=="),
    NOT_EQUALS("!="),
    CONTAINS("contains"),
    NOT_CONTAINS("!contains"),
    CONTAINS_ONLY("contains only"),
    CONTAINS_ANY("contains any"),
    CONTAINS_DEEP("contains deep");

    final String symbol;
    MatchOperator(String symbol) { this.symbol = symbol; }
}
```

---

## HTTP Actions

Uses `KarateJs.http` (HttpRequestBuilder) which is already a well-designed fluent API.

```java
package io.karatelabs.core;

public class HttpActions {

    private final ScenarioRuntime runtime;

    public HttpActions(ScenarioRuntime runtime) {
        this.runtime = runtime;
    }

    // Access the HttpRequestBuilder from KarateJs
    private HttpRequestBuilder http() {
        return runtime.getHttp();
    }

    public void url(Step step) {
        String urlExpr = step.getText();
        String url = (String) runtime.eval(urlExpr);
        http().url(url);
    }

    public void path(Step step) {
        String pathExpr = step.getText();
        Object path = runtime.eval(pathExpr);
        if (path instanceof List<?> list) {
            for (Object item : list) {
                http().path(item.toString());
            }
        } else {
            http().path(path.toString());
        }
    }

    public void header(Step step) {
        // Parse "name = value" or use table
        if (step.getTable() != null) {
            for (Map<String, Object> row : step.getTable().getRowsAsMaps()) {
                http().header((String) row.get("name"), row.get("value").toString());
            }
        } else {
            String[] parts = step.getText().split("=", 2);
            String name = parts[0].trim();
            Object value = runtime.eval(parts[1].trim());
            http().header(name, value.toString());
        }
    }

    public void request(Step step) {
        Object body;
        if (step.getDocString() != null) {
            body = runtime.eval(step.getDocString());
        } else {
            body = runtime.eval(step.getText());
        }
        http().body(body);
    }

    public void method(Step step) {
        String method = step.getText().trim().toUpperCase();
        HttpResponse response = http().invoke(method);

        // Set response variables in the JS engine
        runtime.setVariable("response", response.getBody());
        runtime.setVariable("responseStatus", response.getStatus());
        runtime.setVariable("responseHeaders", response.getHeaders());
        runtime.setVariable("responseTime", response.getResponseTime());
        runtime.setVariable("responseCookies", response.getCookies());
    }

    public void status(Step step) {
        int expected = Integer.parseInt(step.getText().trim());
        int actual = (Integer) runtime.getVariable("responseStatus");
        if (actual != expected) {
            throw new AssertionError("expected status: " + expected + ", actual: " + actual);
        }
    }
}
```

---

## Result Classes

### StepResult

```java
package io.karatelabs.core;

public class StepResult {

    public enum Status { PASSED, FAILED, SKIPPED }

    private final Step step;
    private final Status status;
    private final long startTime;
    private final long durationNanos;
    private final Throwable error;
    private String log;
    private List<Embed> embeds;

    public static StepResult passed(Step step, long startTime, long durationNanos) {
        return new StepResult(step, Status.PASSED, startTime, durationNanos, null);
    }

    public static StepResult failed(Step step, long startTime, long durationNanos, Throwable error) {
        return new StepResult(step, Status.FAILED, startTime, durationNanos, error);
    }

    public static StepResult skipped(Step step, long startTime) {
        return new StepResult(step, Status.SKIPPED, startTime, 0, null);
    }

    public boolean isPassed() { return status == Status.PASSED; }
    public boolean isFailed() { return status == Status.FAILED; }
    public boolean isSkipped() { return status == Status.SKIPPED; }

    public double getDurationMillis() {
        return durationNanos / 1_000_000.0;
    }
}
```

### ScenarioResult

```java
package io.karatelabs.core;

public class ScenarioResult {

    private final Scenario scenario;
    private final List<StepResult> stepResults = new ArrayList<>();
    private long startTime;
    private long endTime;
    private String executorName;

    public void addStepResult(StepResult sr) {
        stepResults.add(sr);
    }

    public boolean isPassed() {
        return stepResults.stream().noneMatch(StepResult::isFailed);
    }

    public boolean isFailed() {
        return stepResults.stream().anyMatch(StepResult::isFailed);
    }

    public long getDurationMillis() {
        return endTime - startTime;
    }

    public String getFailureMessage() {
        return stepResults.stream()
            .filter(StepResult::isFailed)
            .findFirst()
            .map(sr -> sr.getError().getMessage())
            .orElse(null);
    }
}
```

### FeatureResult

```java
package io.karatelabs.core;

public class FeatureResult {

    private final Feature feature;
    private final List<ScenarioResult> scenarioResults = new ArrayList<>();
    private int callDepth;
    private Object callArg;
    private Map<String, Object> resultVariables;

    public synchronized void addScenarioResult(ScenarioResult sr) {
        scenarioResults.add(sr);
    }

    public int getPassedCount() {
        return (int) scenarioResults.stream().filter(ScenarioResult::isPassed).count();
    }

    public int getFailedCount() {
        return (int) scenarioResults.stream().filter(ScenarioResult::isFailed).count();
    }

    public boolean isEmpty() {
        return scenarioResults.isEmpty();
    }
}
```

---

## Runtime Hooks

```java
package io.karatelabs.core;

public interface RuntimeHook {

    default boolean beforeSuite(Suite suite) { return true; }
    default void afterSuite(Suite suite) { }

    default boolean beforeFeature(FeatureRuntime fr) { return true; }
    default void afterFeature(FeatureRuntime fr) { }

    default boolean beforeScenario(ScenarioRuntime sr) { return true; }
    default void afterScenario(ScenarioRuntime sr) { }

    default boolean beforeStep(Step step, ScenarioRuntime sr) { return true; }
    default void afterStep(StepResult result, ScenarioRuntime sr) { }
}
```

---

## Parallel Execution with Virtual Threads

```java
package io.karatelabs.core;

public class Suite {

    public SuiteResult run() {
        if (parallel && threadCount > 1) {
            return runParallel();
        } else {
            return runSequential();
        }
    }

    private SuiteResult runParallel() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FeatureResult>> futures = features.stream()
                .map(f -> executor.submit(() -> {
                    FeatureRuntime fr = new FeatureRuntime(this, f);
                    return fr.call();
                }))
                .toList();

            List<FeatureResult> results = new ArrayList<>();
            for (Future<FeatureResult> future : futures) {
                results.add(future.get());
            }
            return new SuiteResult(results);
        }
    }

    private SuiteResult runSequential() {
        List<FeatureResult> results = new ArrayList<>();
        for (Feature feature : features) {
            FeatureRuntime fr = new FeatureRuntime(this, feature);
            results.add(fr.call());
        }
        return new SuiteResult(results);
    }
}
```

---

## Scenario Outline Expansion

```java
package io.karatelabs.core;

public class ScenarioIterator implements Iterator<Scenario> {

    private final Feature feature;
    private final List<FeatureSection> sections;
    private int sectionIndex = 0;
    private int exampleIndex = 0;

    @Override
    public boolean hasNext() {
        // Logic to check if more scenarios exist
        // (including expanded outline examples)
    }

    @Override
    public Scenario next() {
        FeatureSection section = sections.get(sectionIndex);

        if (section.isOutline()) {
            ScenarioOutline outline = section.getScenarioOutline();
            ExamplesTable examples = outline.getExamplesTables().get(currentTableIndex);
            Map<String, Object> exampleData = examples.getRow(exampleIndex);

            // Create scenario from outline with substituted values
            Scenario scenario = outline.toScenario(null, exampleIndex,
                examples.getLine(), examples.getTags());
            scenario.setExampleData(exampleData);

            // Substitute placeholders in steps
            for (String key : exampleData.keySet()) {
                scenario.replace("<" + key + ">", String.valueOf(exampleData.get(key)));
            }

            exampleIndex++;
            return scenario;
        } else {
            sectionIndex++;
            return section.getScenario();
        }
    }
}
```

---

## Call and CallOnce

```java
public class StepExecutor {

    private void executeCall(Step step) {
        String text = step.getText();
        CallExpression call = parseCallExpression(text);

        // Resolve feature file
        Resource resource = runtime.getFeatureRuntime().resolve(call.path);
        Feature feature = Feature.read(resource);

        // Create nested FeatureRuntime
        FeatureRuntime nestedFr = new FeatureRuntime(
            runtime.getFeatureRuntime().getSuite(),
            feature,
            runtime.getFeatureRuntime(),  // caller
            call.arg                       // arguments
        );

        FeatureResult result = nestedFr.call();

        // Assign result to variable if specified
        if (call.resultVar != null) {
            runtime.setVariable(call.resultVar, result.getResultVariables());
        }
    }

    private void executeCallOnce(Step step) {
        String text = step.getText();
        String cacheKey = text; // Use full expression as cache key

        Map<String, Object> cached = runtime.getFeatureRuntime().CALLONCE_CACHE.get(cacheKey);
        if (cached != null) {
            // Use cached result
            for (Map.Entry<String, Object> entry : cached.entrySet()) {
                runtime.setVariable(entry.getKey(), entry.getValue());
            }
            return;
        }

        // Execute call and cache result
        executeCall(step);

        // Cache the result variables
        runtime.getFeatureRuntime().CALLONCE_CACHE.put(cacheKey,
            runtime.getAllVariables());
    }
}
```

---

## Implementation Roadmap

### Phase 1: Core Execution (API Testing MVP)

1. **Suite, FeatureRuntime, ScenarioRuntime** - basic execution flow
2. **StepExecutor** - keyword dispatch for core keywords:
   - `def`, `set`, `print`, `assert`
   - `url`, `path`, `param`, `header`, `request`, `method`, `status`
   - `match` (basic operators)
3. **Result classes** - StepResult, ScenarioResult, FeatureResult
4. **Config loading** - karate-config.js evaluation

### Phase 2: Full Match Support

1. All match operators (`contains`, `contains only`, `contains any`, `contains deep`)
2. `each` variants
3. Fuzzy markers (`#string`, `#number`, `#array`, `#object`, `#null`, `#notnull`, `#present`, `#notpresent`, `#ignore`, `#uuid`, `#regex`)
4. Schema validation

### Phase 3: Advanced Features

1. `call` and `callonce`
2. `retry until`
3. Scenario Outline expansion
4. Background steps
5. Tag filtering and tag expressions
6. Runtime hooks

### Phase 4: Parallel Execution

1. Virtual thread executor
2. Thread-safe result collection
3. `@parallel=false` tag support
4. Configurable thread count

### Phase 5: Reporting

1. Karate JSON report format
2. JUnit XML report format
3. HTML report generation
4. Console output with ANSI colors

---

## File Structure

```
karate-core/src/main/java/io/karatelabs/
├── log/                        # ✅ DONE - cross-cutting logging
│   ├── LogLevel.java
│   ├── LogAppender.java
│   ├── LogContext.java
│   └── JvmLogger.java
└── core/                       # runtime execution
    ├── Suite.java
    ├── SuiteResult.java
    ├── Config.java
    ├── RuntimeHook.java
    ├── FeatureRuntime.java
    ├── FeatureResult.java
    ├── ScenarioRuntime.java
    ├── ScenarioResult.java
    ├── ScenarioIterator.java
    ├── StepExecutor.java
    ├── StepResult.java
    ├── KarateBridge.java           # karate.* functions
    ├── actions/
    │   ├── HttpActions.java
    │   ├── MatchActions.java
    │   └── ConfigActions.java
    └── report/
        ├── ReportGenerator.java
        ├── JsonReport.java
        ├── JunitReport.java
        └── HtmlReport.java
```

---

## Logging Architecture (Simplified)

**Package:** `io.karatelabs.log` (cross-cutting, not tied to core runtime)

### Design Philosophy

Two distinct logging destinations:

| Destination | Purpose | Consumers |
|-------------|---------|-----------|
| `JVM` | Infrastructure logging | Developers debugging Java code, CI logs |
| `CONTEXT` | User-script logging | Reports (karate-json), `print` output, HTTP logs |

**Key insight:** These are separate concerns. If Java code needs to log to both, it calls both explicitly (or uses a helper). No magic `BOTH` destination that hides what's happening.

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    User Scripts (JS, Gherkin)                     │
│                                                                   │
│  * print "hello"                                                  │
│  * karate.log("debug info")                                       │
│  * HTTP request/response logging                                  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      LogContext (CONTEXT)                         │
│  - Thread-local, bound to scenario execution                      │
│  - Collects logs for reports (karate-json, HTML)                  │
│  - Appends to step/scenario log buffer                            │
│  - NO SLF4J dependency                                            │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                      JvmLogger (JVM)                              │
│  - Static utility for Java infrastructure                         │
│  - Default: System.err                                            │
│  - Optional: SLF4J adapter (set at runtime)                       │
│  - Category-based (karate.http, karate.match, etc.) - LATER       │
└──────────────────────────────────────────────────────────────────┘
```

### MVP Classes (4 files in `io.karatelabs.log`)

```
io.karatelabs.log/
├── LogContext.java      # Thread-local log collector for reports
├── LogAppender.java     # Interface for JVM logging backends
├── LogLevel.java        # TRACE, DEBUG, INFO, WARN, ERROR
└── JvmLogger.java       # Static utility for Java infrastructure logging
```

### LogContext - For Reports

The primary logging mechanism for user-visible output. Thread-local, bound to scenario execution.

```java
package io.karatelabs.log;

/**
 * Thread-local log collector for scenario execution.
 * All user-script logging (print, karate.log, HTTP) goes here.
 * The collected log is written to karate-json reports.
 */
public class LogContext {

    private static final ThreadLocal<LogContext> CURRENT = new ThreadLocal<>();

    private final StringBuilder buffer = new StringBuilder();

    // ========== Thread-Local Access ==========

    public static LogContext get() {
        LogContext ctx = CURRENT.get();
        if (ctx == null) {
            ctx = new LogContext();
            CURRENT.set(ctx);
        }
        return ctx;
    }

    public static void set(LogContext ctx) {
        CURRENT.set(ctx);
    }

    public static void clear() {
        CURRENT.remove();
    }

    // ========== Logging ==========

    public void log(String message) {
        buffer.append(message).append('\n');
    }

    public void log(String format, Object... args) {
        // Simple {} placeholder replacement (no SLF4J dependency)
        String message = formatMessage(format, args);
        buffer.append(message).append('\n');
    }

    // ========== Collect ==========

    /** Get accumulated log and clear buffer (for step/scenario end). */
    public String collect() {
        String result = buffer.toString();
        buffer.setLength(0);
        return result;
    }

    /** Get accumulated log without clearing. */
    public String peek() {
        return buffer.toString();
    }

    // ========== Format Helper ==========

    private static String formatMessage(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < format.length()) {
            if (i < format.length() - 1 && format.charAt(i) == '{' && format.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append("{}");
                }
                i += 2;
            } else {
                sb.append(format.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }
}
```

### JvmLogger - For Java Infrastructure

Static utility for Java code that needs traditional logging. No dependency on SLF4J in the interface.

```java
package io.karatelabs.log;

/**
 * Static logger for Java infrastructure code.
 * Default: prints to System.err with level prefix.
 * Can be configured to use SLF4J at runtime.
 */
public class JvmLogger {

    private static LogAppender appender = LogAppender.STDERR;
    private static LogLevel threshold = LogLevel.INFO;

    // ========== Configuration ==========

    public static void setAppender(LogAppender appender) {
        JvmLogger.appender = appender;
    }

    public static void setLevel(LogLevel level) {
        JvmLogger.threshold = level;
    }

    // ========== Logging ==========

    public static void trace(String format, Object... args) {
        log(LogLevel.TRACE, format, args);
    }

    public static void debug(String format, Object... args) {
        log(LogLevel.DEBUG, format, args);
    }

    public static void info(String format, Object... args) {
        log(LogLevel.INFO, format, args);
    }

    public static void warn(String format, Object... args) {
        log(LogLevel.WARN, format, args);
    }

    public static void error(String format, Object... args) {
        log(LogLevel.ERROR, format, args);
    }

    public static void error(String message, Throwable t) {
        if (LogLevel.ERROR.isEnabled(threshold)) {
            appender.log(LogLevel.ERROR, message, t);
        }
    }

    private static void log(LogLevel level, String format, Object... args) {
        if (level.isEnabled(threshold)) {
            appender.log(level, format, args);
        }
    }

    // ========== Level Checks ==========

    public static boolean isTraceEnabled() { return LogLevel.TRACE.isEnabled(threshold); }
    public static boolean isDebugEnabled() { return LogLevel.DEBUG.isEnabled(threshold); }
}
```

### LogAppender - Pluggable Backend

```java
package io.karatelabs.log;

/**
 * Backend for JVM logging. Default is stderr, can be replaced with SLF4J.
 */
public interface LogAppender {

    void log(LogLevel level, String format, Object... args);

    void log(LogLevel level, String message, Throwable t);

    /** Default appender: prints to System.err */
    LogAppender STDERR = new LogAppender() {
        @Override
        public void log(LogLevel level, String format, Object... args) {
            String msg = LogContext.formatMessage(format, args);  // reuse formatter
            System.err.println("[" + level + "] " + msg);
        }

        @Override
        public void log(LogLevel level, String message, Throwable t) {
            System.err.println("[" + level + "] " + message);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        }
    };
}
```

### Usage Examples

```java
// ========== In ScenarioRuntime ==========
LogContext.set(new LogContext());
try {
    executeSteps();
} finally {
    String scenarioLog = LogContext.get().collect();
    result.setLog(scenarioLog);  // goes to karate-json
    LogContext.clear();
}

// ========== In StepExecutor (print keyword) ==========
private void executePrint(Step step) {
    Object value = runtime.eval(step.getText());
    LogContext.get().log(String.valueOf(value));
}

// ========== In HttpActions (request/response logging) ==========
public void method(Step step) {
    String method = step.getText().toUpperCase();
    HttpResponse response = http().invoke(method);

    // Log to CONTEXT for reports
    LogContext ctx = LogContext.get();
    ctx.log("REQUEST: {} {}", method, http().getUrl());
    ctx.log("RESPONSE: {} ({} ms)", response.getStatus(), response.getResponseTime());

    // Optionally also log to JVM for debugging
    if (JvmLogger.isDebugEnabled()) {
        JvmLogger.debug("HTTP {} {} -> {}", method, http().getUrl(), response.getStatus());
    }
}

// ========== In KarateJs (JS console.log) ==========
engine.setOnConsoleLog(text -> {
    LogContext.get().log(text);  // JS logs go to report
});

// ========== Configure SLF4J at startup (optional) ==========
// In a karate-slf4j module or user code:
JvmLogger.setAppender(new Slf4jAppender());  // adapter implementation
JvmLogger.setLevel(LogLevel.DEBUG);
```

### What's Deferred (Not MVP)

1. **LogMasker** - secrets masking for reports (add when needed)
2. **LogCategory** - hierarchical namespaces (add when we need fine-grained config)
3. **ConsumerLogListener** - live streaming to HTML (add for HTML reports)
4. **Structured LogEvent** - timestamp, threadName, scenarioId (add if reports need it)

For MVP, the simple `StringBuilder` buffer in `LogContext` is sufficient for karate-json generation.

### Implementation Status: ✅ DONE

Logging refactored from 9 files to 4 files. Package moved from `io.karatelabs.core.log` to `io.karatelabs.log`.

| File | Status | Notes |
|------|--------|-------|
| `LogLevel.java` | ✅ | Enum: TRACE, DEBUG, INFO, WARN, ERROR |
| `LogAppender.java` | ✅ | Interface with `STDERR` default |
| `LogContext.java` | ✅ | Thread-local StringBuilder for reports |
| `JvmLogger.java` | ✅ | Static utility, no SLF4J dependency |

---

## Implementation Roadmap Status

### Phase 1: Core Execution (API Testing MVP)

| Component | Status | Notes |
|-----------|--------|-------|
| **Logging** | ✅ | `io.karatelabs.log` - 4 classes |
| **Suite** | ⬜ | Top-level orchestrator |
| **FeatureRuntime** | ⬜ | Executes feature files |
| **ScenarioRuntime** | ⬜ | Wraps KarateJs, manages execution |
| **StepExecutor** | ⬜ | Keyword dispatch |
| **Result classes** | ⬜ | StepResult, ScenarioResult, FeatureResult |
| **Config loading** | ⬜ | karate-config.js evaluation |
| **karate-json report** | ⬜ | JSON report generation |

### Next Steps

1. **StepResult / ScenarioResult / FeatureResult** - result classes for reporting
2. **ScenarioRuntime** - wraps KarateJs, iterates steps, collects results
3. **StepExecutor** - keyword dispatch (start with `def`, `print`, `match`)
4. **karate-json generation** - output report from results

---

## Open Design Questions

### Resolved (follow v1 behavior)
1. **Config inheritance**: karate-config.js runs once per Suite, values inherited by all scenarios
2. **Variable isolation**: Called features get a copy, modifications don't affect caller (unless explicitly returned)
3. **Abort behavior**: `karate.abort()` stops current scenario only, not parallel ones

### To Decide During Implementation
4. **Error recovery**: Should we support "soft assertions" / `configure continueOnStepFailure`?
5. **`configure` options**: Which v1 configs to support initially? (ssl, followRedirects, connectTimeout, readTimeout, proxy, charset, retry)
6. **`read()` caching**: Should `read('file.json')` cache parsed content within a scenario?
7. **Response body parsing**: Auto-parse JSON/XML based on content-type, or always return raw + parsed?
8. **Setup scenarios**: How should `@setup` tagged scenarios work with parallelism?
9. **Dynamic outlines**: Support `Examples:` with `karate.setup()` result? (v1 feature)

---

## References

- Karate v1 source: `../karate/karate-core/src/main/java/com/intuit/karate/core/`
- v1 Results/Reports: `../karate/karate-core/src/main/java/com/intuit/karate/core/` (see `ScenarioResult.java`, `FeatureResult.java`, `StepResult.java` for karate-json format)
- Current v2 domain classes: `karate-js/src/main/java/io/karatelabs/gherkin/`
- JS engine: `karate-js/src/main/java/io/karatelabs/js/`
- HTTP client: `karate-core/src/main/java/io/karatelabs/io/http/`
- Match engine: `karate-core/src/main/java/io/karatelabs/match/`
- Logging: `karate-core/src/main/java/io/karatelabs/log/` (LogContext, JvmLogger)
