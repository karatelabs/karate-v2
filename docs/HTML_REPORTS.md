# Karate v2 HTML Reports

> See also: [RUNTIME.md](./RUNTIME.md) | [CLI.md](./CLI.md)

This document describes the HTML report architecture for Karate v2.

---

## Overview

Karate v2 generates rich interactive HTML reports using Bootstrap 5 + Alpine.js (no jQuery). Reports are generated asynchronously as tests complete, with JSON data inlined in HTML for client-side rendering.

### Core Classes

| Class | Description |
|-------|-------------|
| `HtmlReportListener` | Async HTML report generation (default), implements `ResultListener` |
| `HtmlReportWriter` | HTML report generation with inlined JSON |
| `NdjsonReportListener` | NDJSON streaming (opt-in via `.outputNdjson(true)`) |
| `HtmlReport` | Report aggregation API |

### Runner API

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

---

## Architecture

### Memory-Efficient Generation

The `HtmlReportListener` writes feature HTML files asynchronously as each feature completes, using a single-thread executor. Only small summary data is kept in memory. At suite end, summary pages are generated from the in-memory summaries.

```
Test Execution
    │
HtmlReportListener (default)
    ├── onFeatureEnd() → Queue feature HTML to executor (async)
    │                  → Collect small summary info in memory
    └── onSuiteEnd()   → Write karate-summary.html
                       → Wait for executor to finish
                       → Copy static resources
```

```java
// In HtmlReportListener
@Override
public void onFeatureEnd(FeatureResult result) {
    result.sortScenarioResults();  // deterministic ordering
    summaries.add(new FeatureSummary(result));  // small in-memory data
    executor.submit(() -> writeFeatureHtml(result));  // async
}

@Override
public void onSuiteEnd(SuiteResult result) {
    writeSummaryPages(summaries, result);
    executor.shutdown();
    executor.awaitTermination(60, TimeUnit.SECONDS);
}
```

### Single-File HTML Architecture

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
  { /* inlined feature/summary JSON */ }
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

### Output Structure

```
target/karate-reports/
├── karate-summary.json           # JSON data
├── karate-junit.xml              # JUnit XML
├── index.html                    # Redirect to summary
├── karate-summary.html           # Summary view
├── features/
│   ├── users.list.html           # Per-feature reports (dot-based naming)
│   └── orders.create.html
└── res/
    ├── bootstrap.min.css
    ├── alpine.min.js
    └── karate-report.css
```

---

## Report Views

### Summary Page (`karate-summary.html`)

- Overview of all features with pass/fail counts
- Sortable table columns (client-side)
- Tag filter chips for scenario-level filtering
- Expandable feature rows to show matching scenarios when filtering

### Feature Page (`features/*.html`)

- Left sidebar with scenario navigation
- Scenarios displayed with RefId format: `[section.exampleIndex:line]`
- Step rows with color-coded status (green=pass, red=fail, yellow=skip)
- Expandable logs on step click
- Dark mode theme toggle with localStorage persistence

---

## Key Decisions

- **File naming**: Dot-based path flattening (`users/list.feature` → `users.list.html`)
- **Scenario RefId**: Use existing `Scenario.getRefId()` → `[section.exampleIndex:line]`
- **Tags**: Filter chips on summary page, filter at scenario level
- **Summary table**: Sortable columns (client-side)
- **JS framework**: Alpine.js (no jQuery)
- **CSS**: Bootstrap 5 utilities, minimal custom CSS in karate-report.css

---

## JSON Schema

### Karate JSON (v2 - simplified)

The v2 JSON uses **step-level source** - each step carries its own text with indentation preserved. This handles:
- **Loops** - Same step executes multiple times (each execution is a separate entry)
- **Scenario Outline** - Same source lines in different scenario instances
- **Called features** - Steps from other files appear nested
- **Embedded JS** - JS execution spans files/contexts

### Suite Summary (`karate-summary.json`)

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
      "fileName": "features.users",
      "failed": false,
      "passedCount": 5,
      "failedCount": 0,
      "durationMillis": 1234.5,
      "scenarios": [
        {
          "name": "Get users",
          "refId": "[1:5]",
          "passed": true,
          "ms": 500,
          "tags": ["@smoke"]
        }
      ]
    }
  ]
}
```

### Feature Detail (inlined in HTML)

```json
{
  "name": "User Management",
  "path": "features/users.feature",
  "scenarios": [
    {
      "name": "Create user",
      "line": 5,
      "refId": "[1:5]",
      "sectionIndex": 1,
      "exampleIndex": -1,
      "isOutlineExample": false,
      "tags": ["@smoke"],
      "passed": true,
      "ms": 50,
      "steps": [
        { "prefix": "*", "keyword": "url", "text": "baseUrl", "status": "passed", "ms": 2, "line": 6, "hasLogs": false },
        { "prefix": "*", "keyword": "def", "text": "user = { name: 'John' }", "status": "passed", "ms": 1, "line": 7, "hasLogs": false },
        { "prefix": "*", "keyword": "method", "text": "post", "status": "passed", "ms": 45, "line": 8, "hasLogs": true, "logs": "POST http://..." },
        { "prefix": "*", "keyword": "status", "text": "201", "status": "passed", "ms": 0, "line": 9, "hasLogs": false }
      ]
    }
  ]
}
```

### Field Reference

| Field | Description |
|-------|-------------|
| `scenarios[].refId` | Scenario reference ID: `[section.exampleIndex:line]` |
| `scenarios[].sectionIndex` | 1-based section index within feature |
| `scenarios[].exampleIndex` | Example row index for outlines (-1 if not outline) |
| `scenarios[].isOutlineExample` | True if this is a scenario outline example |
| `scenarios[].steps` | Array of executed steps in order |
| `steps[].prefix` | Gherkin prefix (`*`, `Given`, `When`, `Then`, `And`, `But`) |
| `steps[].keyword` | Karate keyword (`def`, `match`, `url`, `method`, etc.) |
| `steps[].text` | Step text after keyword |
| `steps[].status` | `"passed"`, `"failed"`, `"skipped"` |
| `steps[].ms` | Duration in milliseconds |
| `steps[].line` | Source line number |
| `steps[].hasLogs` | True if step has log output |
| `steps[].logs` | Log entries (HTTP, print, etc.) |
| `steps[].error` | Error message if failed |

---

## NDJSON Streaming (Opt-In)

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

---

## Report Aggregation

Merge reports from multiple test runs:

```java
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

---

## Template Customization

### Template Resolution Order

```
user templates (--templates dir)
  ↓ fallback
classpath:karate-report-templates/  (commercial JAR)
  ↓ fallback
classpath:io/karatelabs/report/  (built-in)
```

### Custom Templates

```java
Runner.path("features/")
    .outputHtmlReport(true)
    .reportTemplates("src/custom-templates/")  // user templates
    .parallel(5);
```

### Variable Injection

```java
Runner.path("features/")
    .reportVariable("company", "Acme Corp")
    .reportVariable("logo", "classpath:branding/logo.png")
    .parallel(5);
```

---

## Execution Sequence UX

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
- **Pass/fail indicators** per step with timing
- **Click to expand** logs for HTTP, print, etc.
- **Left nav** for quick scenario navigation

---

## Deferred Features

The following features are planned for future implementation:

- **Called feature nesting** - Show steps from called features with indentation (currently flat)
- **Tags page** - Separate page for tag analysis (currently integrated into summary)

### ✅ Timeline Page (Implemented)

The timeline page (`karate-timeline.html`) provides a Gantt-style visualization of parallel test execution using [vis-timeline](https://visjs.github.io/vis-timeline/docs/timeline/) loaded from CDN.

**Features:**
- Thread-based lanes showing concurrent execution
- Scenario-level granularity with pass/fail coloring
- Hover tooltips with timing details
- Zoom and pan navigation
- Dark mode support

---

## Implementation Details

### Scenario Ordering

`ScenarioResult` implements `Comparable<ScenarioResult>` for deterministic ordering:

```java
@Override
public int compareTo(ScenarioResult other) {
    if (other == null) return 1;
    // Compare by section index first
    int sectionCmp = Integer.compare(
        this.scenario.getSection().getIndex(),
        other.scenario.getSection().getIndex()
    );
    if (sectionCmp != 0) return sectionCmp;
    // Then by example index
    int exampleCmp = Integer.compare(
        this.scenario.getExampleIndex(),
        other.scenario.getExampleIndex()
    );
    if (exampleCmp != 0) return exampleCmp;
    // Finally by line number
    return Integer.compare(
        this.scenario.getLine(),
        other.scenario.getLine()
    );
}
```

`FeatureResult.sortScenarioResults()` is called before writing feature HTML to ensure consistent ordering.

### File Naming

Feature HTML files use dot-based path flattening:

```java
private static String pathToFileName(String path) {
    // "users/list.feature" → "users.list"
    return path.replace(".feature", "")
               .replace("/", ".")
               .replace("\\", ".")
               .replaceAll("[^a-zA-Z0-9_.-]", "_")
               .toLowerCase();
}
```

---

## Test Coverage

| Test Class | Coverage |
|------------|----------|
| `HtmlReportListenerTest` | Async HTML report generation |
| `NdjsonReportListenerTest` | NDJSON streaming (opt-in) |
| `HtmlReportWriterTest` | HTML report generation and aggregation |

---

## Local Development Guide

This section is for contributors working on HTML report templates and styling.

### Quick Start

**Recommended:** Run the dev test to generate reports:

```bash
cd karate-core

# Run the dev test (outputs to target/karate-report-dev/)
mvn test -Dtest=HtmlReportWriterTest#testHtmlReportGeneration -q

# Open in browser
open target/karate-report-dev/karate-summary.html
```

**Alternative:** Use CLI directly:

```bash
# Compile and run (outputs to target/karate-reports by default)
mvn compile -q && mvn exec:java \
  -Dexec.mainClass="io.karatelabs.Main" \
  -Dexec.args="run -T 3 src/test/resources/io/karatelabs/report"

open target/karate-reports/karate-summary.html
```

**Note:** Chrome blocks favicons for `file://` URLs. For full fidelity, use a local server:
```bash
cd target/karate-report-dev && python3 -m http.server 8000
# Then open http://localhost:8000/karate-summary.html
```

### Key Files

| File | Purpose |
|------|---------|
| `src/main/resources/io/karatelabs/report/karate-summary.html` | Summary page template |
| `src/main/resources/io/karatelabs/report/karate-feature.html` | Feature page template |
| `src/main/resources/io/karatelabs/report/karate-timeline.html` | Timeline page template |
| `src/main/resources/io/karatelabs/report/res/` | Static resources (CSS, JS, images) |
| `src/main/java/io/karatelabs/core/HtmlReportWriter.java` | Java code that builds JSON data |
| `src/main/java/io/karatelabs/core/HtmlReportListener.java` | Async report generation listener |

### Development Workflow

1. **Edit HTML templates** in `src/main/resources/io/karatelabs/report/`
2. **Run `mvn compile`** to copy resources to target/classes
3. **Generate reports** using the command above
4. **Refresh browser** to see changes

**Note:** If updating static resources (CSS, JS, SVG), you may need to delete the target file first if its timestamp is newer than the source:
```bash
rm target/classes/io/karatelabs/report/res/karate-logo.svg
mvn compile
```

### Template Architecture

Templates use **Alpine.js** for reactivity and **Bootstrap 5** for styling:

```html
<body x-data="reportData()">
  <!-- Alpine.js bindings -->
  <div x-show="condition" x-text="data.value"></div>
</body>

<script id="karate-data" type="application/json">/* KARATE_DATA */</script>
<script>
  function reportData() {
    const data = JSON.parse(document.getElementById('karate-data').textContent);
    return { data, /* reactive state */ };
  }
</script>
```

The `/* KARATE_DATA */` placeholder is replaced with actual JSON by `HtmlReportWriter.inlineJson()`.

### Adding New Data Fields

1. **Update Java** - Add field in `HtmlReportWriter.buildStepData()` or similar
2. **Update template** - Use the new field with Alpine.js binding
3. **Update this doc** - Add to JSON schema and field reference

### Testing Tips

- Use the test features in `src/test/resources/io/karatelabs/report/`
- Check both light and dark themes
- Test with parallel execution (`-T 3`) for timeline
- Verify mobile/responsive layout

### Using Claude for Report Development

When working with Claude on HTML reports:

1. **Share context** - Point Claude to this doc and the template files
2. **Share screenshots** - Use `Cmd+Shift+4` on Mac, paste path for Claude to read
3. **Iterate quickly** - Claude can edit templates, you regenerate and check browser
4. **Reference v1** - v1 reports are at `/Users/peter/dev/zcode/karate/karate-core/src/main/java/com/intuit/karate/report/`

**Example prompt:**
```
I want to change the step display in karate-feature.html to show
the prefix and keyword. The JSON already has these fields.
Can you update the template?
```

### Report Directory Backup

By default, the CLI backs up existing report directories with a timestamp:
```
karate-reports_20251217_153045/   # backup
karate-reports/                    # new reports
```

Disable with `-B=false` or `--backup-reportdir=false`.

---

## TODO: Configure Report

> **Status:** Not yet implemented

Support for `configure report` to control report verbosity and content, similar to Karate v1.

**Planned options:**

| Option | Default | Description |
|--------|---------|-------------|
| `showStepDetails` | `true` | Show step-level details in report |
| `showJsLineNumbers` | `false` | Capture JS line-of-code execution (like Gherkin steps) |
| `showCallDetails` | `true` | Show called feature details inline |
| `showHttpDetails` | `true` | Show HTTP request/response details |
| `maxPayloadSize` | `4096` | Max size for embedded payloads |

**Usage (planned):**
```cucumber
* configure report = { showJsLineNumbers: true }
```

**JS Line Capture:**

When `showJsLineNumbers: true`, JS execution would capture line-by-line execution similar to Gherkin steps:
```
1: var proc = karate.fork({ args: ['node', 'server.js'] })
2: proc.waitForPort('localhost', 8080, 30, 250)
3: var response = http.get()
```

This is particularly useful for `.karate.js` script execution where there are no Gherkin steps to display.

**Implementation notes:**
- Requires JS engine instrumentation to capture line execution
- Should be opt-in due to performance overhead
- See also: [RUNTIME.md](./RUNTIME.md) Priority 7 (JS Script Execution), [LOGGING.md](./LOGGING.md)
