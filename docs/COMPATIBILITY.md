# V1-V2 Compatibility Testing

This document describes the process for validating Karate V2 against V1's test suite.

> See also: [CLI.md](./CLI.md) | [CAPABILITIES.yaml](./CAPABILITIES.yaml)

---

## Overview

Karate V2 aims to be backwards compatible with V1. This testing process:

1. Extracts all V1 tests into a baseline tracker (CSV)
2. Runs V1 tests against the V2 engine
3. Triages failures and fixes V2 or adapts tests
4. Adopts valuable tests into the V2 codebase

---

## Quick Start

### Extract Baseline (first time)

```bash
./etc/extract-v1-baseline.sh
```

This creates `docs/V1_BASELINE.csv` with all V1 test methods and feature files.

### Run a Single Test

```bash
# Basic run
./etc/run-v1-compat.sh copy.feature

# With debug (copies to home/v1-compat/ on failure)
./etc/run-v1-compat.sh parallel/parallel.feature --debug

# Update CSV with result
./etc/run-v1-compat.sh call-feature.feature --update-csv
```

### Run Tests by Subdirectory

```bash
# List features in a subdirectory
grep ",root," docs/V1_BASELINE.csv | head -10
```

---

## CSV Schema

The baseline is tracked in `docs/V1_BASELINE.csv`:

| Column | Description |
|--------|-------------|
| `id` | Unique identifier (java-001, feature-042) |
| `type` | `java_test` or `feature` |
| `source_path` | Path relative to V1 core dir |
| `class_name` | Java class name (if applicable) |
| `method_name` | Test method name (if applicable) |
| `feature_name` | Feature filename (if applicable) |
| `description` | What the test validates |
| `subdirectory` | V1 subdirectory (root, parallel, etc.) |
| `status` | Current status (see below) |
| `notes` | Failure reason or notes |
| `v2_location` | Path in V2 if adopted |
| `date_tested` | Last test date |

### Status Values

| Status | Meaning |
|--------|---------|
| `pending` | Not yet tested against V2 |
| `passed` | Works on V2 |
| `failed` | Fails on V2 (needs investigation) |
| `skipped` | Out of scope (mock, browser, perf) |
| `adopted` | Copied to V2 codebase permanently |

---

## Workflow

**This is a monitored triage flow, not full automation.** Each test may need different setup (config, env, file structure). We work step-by-step with human oversight.

**IMPORTANT: Stop and ask for confirmation when:**
- A test fails and needs investigation
- A v2 bug is found that needs fixing
- Deciding whether to adopt a test or skip it
- Any decision point in the triage flow

Do NOT batch process tests automatically. Work through one test at a time, report findings, and wait for direction.

### Phase 1: Initial Triage

1. Run extraction to create baseline
2. Mark out-of-scope tests as `skipped` (mock, browser, perf)
3. Work through tests by priority (see below)

### Phase 2: Per-Test Process

For each v1 test:

1. **Copy files to sandbox** (`home/v1-compat/`)
2. **Review and edit** - may need:
   - Different karate-config.js
   - Environment setup
   - Additional dependency files
3. **Run test** against v2 engine
4. **Evaluate result:**

```
Test result?
├── Passes → Is it valuable? Adopt or mark passed
├── Fails:
│   ├── Feature not in V2 scope → Mark skipped, document
│   ├── Feature planned for V2 → Mark pending, link to CAPABILITIES.yaml
│   └── Should work → Investigate
│       ├── V2 bug → Fix V2, add regression test
│       └── Test needs adaptation → Adapt and re-test
```

### Phase 3: Adoption

When a test passes and is valuable:

1. Adapt to V2 testing patterns (see below)
2. Add to appropriate V2 test class
3. Update CSV: status=`adopted`, v2_location=path

**Lifecycle tests** (bootstrap, karateenv, config) typically need real feature files on disk - don't try to inline these.

---

## V2 Testing Guidelines

**IMPORTANT:** V2 optimizes for fast test execution. Follow these patterns when adopting tests:

### 1. Use In-Memory HTTP (No Network)

**Don't** run actual HTTP servers. Use `InMemoryHttpClient`:

```java
@Test
void testGetWithParams() {
    InMemoryHttpClient client = new InMemoryHttpClient(req -> {
        String page = req.getParam("page");
        if ("1".equals(page)) {
            return json("{ \"page\": 1 }");
        }
        return status(400);
    });

    ScenarioRuntime sr = run(client, """
        Feature:
        Scenario:
        * url 'http://test/items'
        * param page = 1
        * method get
        * match response.page == 1
        """);
    assertPassed(sr);
}
```

See: `karate-core/src/test/java/io/karatelabs/core/HttpStepTest.java`

### 2. Use Inline Feature Strings

**Don't** create separate `.feature` files for unit tests. Use Java text blocks:

```java
@Test
void testDefJson() {
    ScenarioRuntime sr = run("""
        Feature:
        Scenario:
        * def foo = { name: 'bar' }
        """);
    assertPassed(sr);
    matchVar(sr, "foo", Map.of("name", "bar"));
}
```

See: `karate-core/src/test/java/io/karatelabs/core/DefStepTest.java`

### 3. Use Feature Files for Call/Dependencies

When testing `call`, `read`, or multi-file scenarios, use real `.feature` files in test resources:

```
karate-core/src/test/resources/
├── feature/
│   ├── caller.feature        # Main test feature
│   └── called.feature        # Called dependency
```

This is appropriate for:
- `call` and `callonce` tests
- `read()` file operations
- Multi-feature scenarios
- Shared utilities

### 4. Reuse Server Instances (When HTTP Required)

If actual HTTP is needed, use `ServerTestHarness` with `@BeforeAll/@AfterAll`:

```java
static ServerTestHarness harness;

@BeforeAll
static void beforeAll() {
    harness = new ServerTestHarness("classpath:test-resources");
    harness.start();
}

@AfterAll
static void afterAll() {
    harness.stop();
}
```

See: `karate-core/src/test/java/io/karatelabs/io/http/ServerTestHarness.java`

### 5. Watch for Duplicates

Before adopting a v1 test, check if v2 already tests the same thing:
- Search existing test classes for similar functionality
- Avoid redundant test coverage
- Consolidate into existing tests when appropriate

### Test Utilities

Key utilities in `io.karatelabs.core.TestUtils`:

| Method | Usage |
|--------|-------|
| `run(feature)` | Run inline feature string |
| `run(client, feature)` | Run with mock HTTP client |
| `assertPassed(sr)` | Assert scenario passed |
| `assertFailed(sr)` | Assert scenario failed |
| `get(sr, name)` | Get variable value |
| `matchVar(sr, name, expected)` | Assert variable matches |

### Test Organization

The **JUnit class name** is the category. The **method name** describes what's being tested. Keep related tests together to avoid duplicates.

| Class | What it tests |
|-------|---------------|
| `DefStepTest` | def, set, copy, remove, text, json |
| `MatchStepTest` | match assertions |
| `HttpStepTest` | HTTP with InMemoryHttpClient |
| `CallFeatureTest` | call, callonce (uses resource files) |
| `copy/CopyTest` | shared vs isolated scope, variable inheritance |
| `ConfigTest` | configure keyword |
| `KarateConfigTest` | karate-config.js lifecycle |
| `ScenarioOutlineTest` | outlines, examples |
| `RuntimeHookTest` | hooks |

Before adding a test, check if the class already covers it.

---

## Directory Structure

```
karate-v2/
├── docs/
│   ├── V1_BASELINE.csv         # Master tracking
│   ├── COMPATIBILITY.md        # This file
│   └── CAPABILITIES.yaml       # Feature spec
├── etc/
│   ├── test-cli.sh             # V2 CLI runner
│   ├── extract-v1-baseline.sh  # CSV extraction
│   └── run-v1-compat.sh        # Single test runner
├── home/
│   └── v1-compat/              # Debug workspace (gitignored)
├── target/
│   └── v1-compat-results/      # Test logs (gitignored)
└── karate-core/src/test/java/io/karatelabs/core/
    ├── DefStepTest.java        # def, set, copy, remove, text, json
    ├── MatchStepTest.java      # match assertions
    ├── HttpStepTest.java       # HTTP with InMemoryHttpClient
    ├── CallFeatureTest.java    # call, callonce
    ├── ConfigTest.java         # configure, karate-config
    └── ...                     # Add to existing or create new as needed
```

**Note:** Adopted tests go into existing test classes (inline feature strings), not separate resource files.

---

## Testing Priority

| Priority | Subdirectory | Count | Why |
|----------|--------------|-------|-----|
| 1 | root | ~100 | Core runtime features |
| 2 | parser/ | ~24 | Gherkin parsing |
| 3 | jsread/, jscall/ | ~25 | JS interop |
| 4 | runner/ | ~35 | Runner API |
| 5 | parallel/ | ~15 | Parallel execution |
| 6 | xml/ | ~10 | XML handling |
| 7 | hooks/ | ~20 | Hook system |
| 8 | tags/, retry/ | ~5 | Tag filtering, retry |

**Skip:** mock/, parajava/, parasimple/

---

## Out of Scope

These V1 features have known differences in V2 or are out of scope:

| Area | Reason |
|------|--------|
| **Java API** | `Runner`, `Suite`, `Results` classes differ |
| **HTML Reports** | V2 has new report format |
| **Mock Server** | Not yet implemented in V2 |
| **Browser/Driver** | Not yet implemented in V2 |
| **Performance** | Not yet implemented in V2 |
| **WebSocket** | Not yet implemented in V2 |

---

## Relationship to CAPABILITIES.yaml

| File | Purpose |
|------|---------|
| **CAPABILITIES.yaml** | Feature specification (what V2 should do) |
| **V1_BASELINE.csv** | Test execution tracking (proof V1 tests pass) |

They complement each other:
- CAPABILITIES defines the goal
- BASELINE proves it works

---

## V1 Source Location

All V1 tests are sourced from:

```
/Users/peter/dev/zcode/karate/karate-core/src/test/java/com/intuit/karate/core/
```

This directory contains:
- **63 Java test files** (46 with @Test annotations)
- **~239 feature files**
- **14 subdirectories** organized by feature area

---

## Investigating V1 Behavior

When a V2 test fails, consult V1 source code to understand expected behavior:

```
/Users/peter/dev/zcode/karate/karate-core/src/main/java/com/intuit/karate/core/
```

Key V1 implementation files:
| File | What it handles |
|------|-----------------|
| `ScenarioCall.java` | call/callonce scope handling, shared vs isolated |
| `ScenarioEngine.java` | Step execution, variable management |
| `StepRuntime.java` | Keyword parsing, special syntax handling |
| `ScenarioBridge.java` | `karate` object methods |

**Workflow when debugging:**
1. Run V1 test against V2, note the failure
2. Find the relevant V1 implementation code
3. Compare behavior - understand what V1 does
4. Fix V2 to match, or document the intentional difference

---

## CSV Update Guidelines

When updating the CSV after testing:

| Field | When to update | Example |
|-------|----------------|---------|
| `status` | Always | `passed`, `failed`, `adopted`, `skipped` |
| `notes` | Action taken or failure reason | `implemented shared scope; added CopyTest` |
| `v2_location` | Path in V2 if adopted | `io/karatelabs/core/copy/` |
| `date_tested` | Today's date | `2025-12-17` |

**Helper features:** Features that are called by other features (not standalone tests) should be marked as `adopted` with note `helper for X.feature`.

**Example CSV update:**
```
# Main test feature
feature-040,feature,copy.feature,...,adopted,implemented shared/isolated scope; added CopyTest,io/karatelabs/core/copy/,2025-12-17

# Helper features
feature-037,feature,copy-called-nested.feature,...,adopted,helper for copy.feature,io/karatelabs/core/copy/,2025-12-17
```

---

## Lightweight Test Harness

When adopting tests, prefer `FeatureRuntime` over `Suite` for simpler/faster execution:

```java
// Lightweight - no reports, just pass/fail
Resource resource = Resource.path("src/test/resources/path/to/test.feature");
Feature feature = Feature.read(resource);
FeatureResult result = FeatureRuntime.of(feature).call();

assertTrue(result.isPassed());
assertEquals(6, result.getScenarioCount());
```

Use `Suite` only when you need:
- Report generation
- Tag filtering
- Multiple features
- Config path specification

---

## Running Tests

### Prerequisites

```bash
# Build V2 (first time)
mvn install -DskipTests

# Or build fatjar for faster testing
mvn package -DskipTests -Pfatjar
```

### Single Feature Test

```bash
./etc/run-v1-compat.sh copy.feature
```

### List Tests by Subdirectory

```bash
# Show features in parallel/ subdirectory
grep ",parallel," docs/V1_BASELINE.csv

# Show features in root
grep ",root," docs/V1_BASELINE.csv | head -20
```

**Note:** Don't batch process. Work through tests one at a time with human review.

### Debug Failed Test

```bash
# Run with --debug to copy files on failure
./etc/run-v1-compat.sh some-test.feature --debug

# Files are copied to home/v1-compat/
# Edit and rerun:
./etc/test-cli.sh home/v1-compat/some-test.feature
```

---

## Viewing Results

### Check Status Summary

```bash
# Status counts (column 9 = status)
awk -F',' '{print $9}' docs/V1_BASELINE.csv | sort | uniq -c

# Pending tests by subdirectory
grep ",pending," docs/V1_BASELINE.csv | awk -F',' '{print $8}' | sort | uniq -c | sort -rn

# Failed tests
grep ",failed," docs/V1_BASELINE.csv
```

### View Test Logs

```bash
ls -la target/v1-compat-results/
cat target/v1-compat-results/copy.log
```
