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

# List pending features by subdirectory
grep ",pending," docs/V1_BASELINE.csv | awk -F',' '{print $8}' | sort | uniq -c | sort -rn
```

---

## Workflow

**This is a monitored triage flow.** Work through tests one at a time with human oversight. Do NOT batch process. **When in doubt, ask for confirmation.**

### Step 1: Pick Next Test

Choose from priority order (see [Testing Priority](#testing-priority)):
1. **root** - Core runtime features (highest priority)
2. **parser/** - Gherkin parsing
3. **jsread/, jscall/** - JS interop
4. etc.

### Step 2: Run Test

```bash
./etc/run-v1-compat.sh <feature>.feature
```

### Step 3: Evaluate Result

```
Test passes?
├── YES → Go to Step 4
└── NO → Investigate:
    ├── Feature not in V2 scope → Mark skipped, document reason
    ├── Test needs adaptation → Adapt and re-run
    └── V2 bug → Go to Step 3a (MUST fix before moving on)
```

### Step 3a: Fix V2 Bug (Required Before Moving On)

**IMPORTANT:** Do NOT skip failures. Fix the bug, verify the fix, then continue.

1. **Fix the bug** in V2 source code
2. **Add unit test** with inline feature text-block:
   ```java
   @Test
   void testFeatureName() {
       ScenarioRuntime sr = run("""
           Feature:
           Scenario:
           * def foo = 'bar'
           """);
       assertPassed(sr);
   }
   ```
3. **Run the unit test**: `mvn test -Dtest=StepDefTest#testFeatureName -pl karate-core`
4. **Re-run the V1 compat test** to confirm it passes
5. Continue to Step 4

> **Build Note:** The `run-v1-compat.sh` script automatically detects source changes in
> `karate-core/src` and `karate-js/src` and rebuilds if needed. If you make changes to
> `karate-js` module (e.g., `Xml.java`), the rebuild will include both modules.
> For manual rebuilds: `mvn install -DskipTests -q -pl karate-js,karate-core`

### Step 4: Update CSV

Mark the feature AND any related JUnit test(s) as passed:

| Field | Value |
|-------|-------|
| `status` | `passed` |
| `notes` | What was fixed or tested |
| `v2_location` | Test class path (if bug was fixed) |
| `date_tested` | Today's date |

**Related items to mark together:**
- Feature file → corresponding JUnit test method (e.g., `copy.feature` → `testCopyAndClone`)
- Helper features → mark as `passed` with note `helper for X.feature`

### Step 5: Consider Adding Test (Even if Passing)

Even if a V1 test passes without code changes, consider adopting it if:
- It demonstrates valuable syntax usage
- It explains edge cases or behavior well
- It provides coverage V2 doesn't have yet

Before adding, check for duplicates:
- Search existing `Step*Test` classes for similar functionality
- If already covered → just mark as passed, no new test needed
- If not covered → add test to appropriate class

---

## CSV Schema

Tracked in `docs/V1_BASELINE.csv`:

| Column | Description |
|--------|-------------|
| `id` | Unique identifier (java-001, feature-042) |
| `type` | `java_test` or `feature` |
| `source_path` | Path relative to V1 core dir |
| `subdirectory` | V1 subdirectory (root, parallel, etc.) |
| `status` | `pending`, `passed`, `failed`, `skipped` |
| `notes` | What was done or failure reason |
| `v2_location` | V2 test class (if bug was fixed) |
| `date_tested` | Last test date |

### JUnit and Feature Relationships

Some JUnit test methods directly run feature files. When marking results:
- If a feature passes, mark BOTH the feature AND its JUnit test as passed
- JUnit tests with complex lifecycle (env setup, karate-config.js) remain separate entries
- Simple JUnit tests that just run a feature can note: `runs X.feature which passes`

---

## V2 Unit Test Guidelines

When fixing bugs or adopting tests, use inline feature text-blocks:

### Test Class Locations

| Fix Area | Test Class |
|----------|------------|
| def, set, copy, table, replace | `StepDefTest` |
| match assertions | `StepMatchTest` |
| HTTP keywords | `StepHttpTest` |
| multipart | `StepMultipartTest` |
| karate.abort() | `StepAbortTest` |
| call/callonce | `CallFeatureTest` |
| JS functions, arrays | `StepJsTest` |

### Patterns

**Inline feature strings (preferred):**
```java
ScenarioRuntime sr = run("""
    Feature:
    Scenario:
    * def foo = { name: 'bar' }
    """);
assertPassed(sr);
matchVar(sr, "foo", Map.of("name", "bar"));
```

**With mock HTTP:**
```java
InMemoryHttpClient client = new InMemoryHttpClient(req -> json("{ \"ok\": true }"));
ScenarioRuntime sr = run(client, """
    Feature:
    Scenario:
    * url 'http://test'
    * method get
    * match response.ok == true
    """);
assertPassed(sr);
```

**Multi-file tests** (call/read/lifecycle scenarios):

Option A - Create on-the-fly with `@TempDir` (see `CallFeatureTest`):
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

Option B - Static files in subfolder (see `copy/CopyTest`):
```
karate-core/src/test/resources/io/karatelabs/core/copy/
├── copy.feature           # Main test
├── copy-called.feature    # Helper
└── copy-called-nested.feature
```
Use Option B when feature files are complex, explain syntax well, or are instructive to read together.

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

## Testing Priority

| Priority | Subdirectory | Why |
|----------|--------------|-----|
| 1 | root | Core runtime features |
| 2 | parser/ | Gherkin parsing |
| 3 | jsread/, jscall/ | JS interop |
| 4 | runner/ | Runner API |
| 5 | parallel/ | Parallel execution |
| 6 | xml/ | XML handling |
| 7 | runner/hooks/ | Hook system |
| 8 | tags/, retry/ | Tag filtering, retry |

**Skip:** mock/, parajava/, parasimple/ (out of scope)

---

## Out of Scope

| Area | Reason |
|------|--------|
| Mock Server | Not yet implemented in V2 |
| Browser/Driver | Not yet implemented in V2 |
| Performance | Not yet implemented in V2 |
| WebSocket | Not yet implemented in V2 |
| Java Runner API | `Runner`, `Suite`, `Results` classes differ |

---

## V1 Source Reference

**V1 tests location:**
```
/Users/peter/dev/zcode/karate/karate-core/src/test/java/com/intuit/karate/core/
```

**Key V1 implementation files** (for understanding expected behavior):

| File | What it handles |
|------|-----------------|
| `ScenarioCall.java` | call/callonce scope handling |
| `ScenarioEngine.java` | Step execution, variable management |
| `ScenarioBridge.java` | `karate` object methods |

**Key V2 implementation files:**

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
    └── ...
```
