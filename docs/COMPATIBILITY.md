# V1-V2 Compatibility Testing

Validates Karate V2 against V1's test suite.

> See also: [CLI.md](./CLI.md) | [CAPABILITIES.yaml](./CAPABILITIES.yaml)

---

## Quick Reference

```bash
# Run a feature test
./etc/run-v1-compat.sh copy.feature

# Run with debug (copies files on failure)
./etc/run-v1-compat.sh copy.feature --debug

# Check status counts
awk -F',' 'NR>1 {print $9}' docs/V1_BASELINE.csv | sort | uniq -c
```

---

## Workflow

**This is a monitored triage flow.** Work through tests one at a time with human oversight. Do NOT batch process or skip to easier tests. **When in doubt, ask for confirmation.**

### Step 1: Pick Next Test

Pick the next `pending` feature from `docs/V1_BASELINE.csv` (top to bottom). Do not skip around.

### Step 2: Run Test

```bash
./etc/run-v1-compat.sh <feature>.feature
```

> **Note:** The script does NOT automatically copy all dependencies. If the test fails due to missing files (e.g., called features), manually copy them to the temp directory one by one and re-run.

> **Note:** V1 uses `classpath:` prefixes (e.g., `classpath:com/intuit/karate/core/file.feature`) which don't work in the temp directory. Adapt these to relative paths in the debug directory and re-run.

### Step 3: Evaluate Result

```
Test passes?
├── YES → Go to Step 4
└── NO → Investigate:
    ├── Feature not in V2 scope → Mark skipped, document reason
    ├── Test needs adaptation → Adapt and re-run
    └── V2 bug → Go to Step 3a (MUST fix before moving on)
```

### Step 3a: Fix V2 Bug (Required)

**Do NOT skip failures or move to easier tests.** Fix the bug, verify, then continue.

1. **Fix the bug** in V2 source code (refer to V1 code if needed, or ask user)
2. **Add unit test** (see [Unit Test Guidelines](#unit-test-guidelines))
3. **Run the unit test**: `mvn test -Dtest=StepDefTest#testName -pl karate-core`
4. **Re-run V1 compat test** to confirm it passes
5. Continue to Step 4

> **Build Note:** `run-v1-compat.sh` auto-detects source changes and rebuilds.
> Manual rebuild: `mvn install -DskipTests -q -pl karate-js,karate-core`

### Step 3b: Verify Full Test Suite

Before marking complete, run the full test suite to ensure no regressions:

```bash
mvn test -pl karate-core
```

Fix any failures before proceeding.

### Step 4: Update CSV

Mark the feature AND any related JUnit tests as passed:

| Field | Value |
|-------|-------|
| `status` | `passed` |
| `notes` | What was fixed or tested |
| `v2_location` | Test class (if bug was fixed) |
| `date_tested` | Today's date |

**Related items:** Feature file → corresponding JUnit test method (mark both).

### Step 5: Consider Adding Test

Even if passing without code changes, consider adding a V2 test if:
- It demonstrates valuable syntax or edge cases
- It provides coverage V2 doesn't have yet

Check for duplicates first in existing `Step*Test` classes.

---

## CSV Schema

Tracked in `docs/V1_BASELINE.csv`:

| Column | Description |
|--------|-------------|
| `id` | Unique identifier (java-001, feature-042) |
| `type` | `java_test` or `feature` |
| `source_path` | Path relative to V1 core dir |
| `subdirectory` | V1 subdirectory (root, parser, etc.) |
| `status` | `pending`, `passed`, `failed`, `skipped` |
| `notes` | What was done or failure reason |
| `v2_location` | V2 test class (if applicable) |
| `date_tested` | Last test date |

---

## Unit Test Guidelines

### Test Class Naming

Follow `Step{Keyword}Test.java` convention:

| Area | Test Class |
|------|------------|
| def, set, copy, table, replace, csv, yaml | `StepDefTest` |
| match assertions | `StepMatchTest` |
| HTTP keywords | `StepHttpTest` |
| multipart | `StepMultipartTest` |
| karate.abort() | `StepAbortTest` |
| call/callonce | `CallFeatureTest` |
| JS functions, arrays | `StepJsTest` |
| XML | `StepXmlTest` |
| eval | `StepEvalTest` |

**When in doubt:** Ask user which test class to use; user may suggest existing class or a new one.

### Test Patterns (Preference Order)

**1. Inline text-blocks (preferred):**
```java
@Test
void testFeatureName() {
    ScenarioRuntime sr = run("""
        Feature:
        Scenario:
        * def foo = { name: 'bar' }
        """);
    assertPassed(sr);
    matchVar(sr, "foo", Map.of("name", "bar"));
}
```

**2. @TempDir for simple multi-file scenarios:**
```java
@TempDir
Path tempDir;

@Test
void testCallFeature() throws Exception {
    Path called = tempDir.resolve("called.feature");
    Files.writeString(called, """
        Feature: Called
        Scenario:
        * def x = 1
        """);
    // ... create caller and run
}
```

**3. Static files in subfolder** (for complex multi-file, binary, or files needing to be viewable):
```
karate-core/src/test/resources/io/karatelabs/core/copy/
├── copy.feature
├── copy-called.feature
└── copy-called-nested.feature
```

Use this when: many files, complex relationships, or non-text files (.json, .html, .xml, binary).

### Test Utilities

From `io.karatelabs.core.TestUtils`:

| Method | Usage |
|--------|-------|
| `run(feature)` | Run inline feature string |
| `run(client, feature)` | Run with mock HTTP client |
| `assertPassed(sr)` | Assert scenario passed |
| `assertFailed(sr)` | Assert scenario failed |
| `matchVar(sr, name, expected)` | Assert variable matches |

---

## Pending V1 Parity

Features that need implementation for full V1 compatibility:

| Feature | V1 Pattern | V2 Status | Notes |
|---------|-----------|-----------|-------|
| Java Function as callable | `Hello.sayHelloFactory()` returns `Function<String,String>`, callable in JS | ❌ Pending | JS engine needs to wrap `java.util.function.Function`, `Callable`, `Runnable`, `Predicate` as `JsCallable` |
| callSingle returning Java fn | `karate.callSingle('file.js')` where JS returns Java Function | ❌ Pending | Depends on above |

**Reference:** V1 parallel test `call-single-from-config3.js`:
```javascript
var Hello = Java.type('com.intuit.karate.core.parallel.Hello');
result.sayHello = Hello.sayHelloFactory();  // Function<String, String>
```

Used in feature:
```gherkin
* match sayHello('world') == 'hello world'
```

---

## Out of Scope

| Area | Reason |
|------|--------|
| Browser/Driver | Not yet implemented in V2 |
| Performance | Not yet implemented in V2 |
| WebSocket | Not yet implemented in V2 |
| Java Runner API | `Runner`, `Suite`, `Results` classes differ |

---

## Source Reference

**V1 tests:**
```
/Users/peter/dev/zcode/karate/karate-core/src/test/java/com/intuit/karate/core/
```

**Key V1 files** (for understanding expected behavior):

| File | What it handles |
|------|-----------------|
| `ScenarioCall.java` | call/callonce scope |
| `ScenarioEngine.java` | Step execution, variables |
| `ScenarioBridge.java` | `karate` object methods |

**Key V2 files:**

| File | What it handles |
|------|-----------------|
| `StepExecutor.java` | Keyword execution |
| `KarateJs.java` | `karate.*` methods |
| `ScenarioRuntime.java` | Scenario execution |

---

## Directory Structure

```
karate-v2/
├── docs/
│   ├── V1_BASELINE.csv         # Test tracking
│   ├── COMPATIBILITY.md        # This file
│   └── CAPABILITIES.yaml       # Feature spec
├── etc/
│   ├── run-v1-compat.sh        # Test runner
│   └── test-cli.sh             # V2 CLI
├── home/v1-compat/             # Debug workspace (gitignored)
└── karate-core/src/test/java/io/karatelabs/core/
    ├── StepDefTest.java
    ├── StepMatchTest.java
    ├── StepJsTest.java
    ├── copy/                   # Multi-file test example
    ├── xml/                    # Multi-file test example
    └── interop/                # Multi-file test example
```
