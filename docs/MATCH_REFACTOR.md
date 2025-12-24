# Match System Refactoring Plan

This document outlines the refactoring plan for the Match system in `io.karatelabs.match`.

## Overview

The Match system is the core assertion engine for Karate test automation. This refactor addresses three key improvements:

1. **Collect All Failures** - Continue matching after first mismatch to report all failures
2. **Large Collection Support** - Memory-efficient handling of large collections via disk-based storage
3. **WITHIN Assertion** - New assertion type (reverse of CONTAINS)
4. **Structured Failure Results** - Return structured data instead of just strings

## Implementation Order

Given the architectural impact, implement in this order:

1. **Phase 1: Large Collection Support** (deepest change - affects data access patterns)
2. **Phase 2: Structured Failure Results** (extends Result class)
3. **Phase 3: Collect All Failures** (removes early-return patterns)
4. **Phase 4: WITHIN Assertion** (new match type)

---

## Phase 1: Large Collection Support

### Goal
Avoid loading entire collections into memory when size exceeds threshold (memory-based, e.g., 10MB estimated).

### Current State
- `Value.java:65-74`: Arrays/Sets eagerly converted to ArrayList
- `Operation.java:154,427,547`: Lists fully loaded via `actual.getValue()`
- No streaming or lazy evaluation

### Design

#### 1.1 Create `LargeValueStore` Interface

```java
// New file: LargeValueStore.java
public interface LargeValueStore {
    int size();
    Object get(int index);
    Iterator<Object> iterator();
    void close(); // cleanup temp files
}
```

#### 1.2 Create `DiskBackedList` Implementation

```java
// New file: DiskBackedList.java
public class DiskBackedList implements LargeValueStore {
    private final File tempFile;
    private final int size;

    // Serialize items to temp file
    // RandomAccessFile for indexed access
    // Cleanup on close()
}
```

#### 1.3 Modify `Value` Class

Add threshold detection and lazy wrapping:

```java
// Value.java additions
private static final long MEMORY_THRESHOLD = 10 * 1024 * 1024; // 10MB
private LargeValueStore largeStore;

// In constructor, estimate size before converting
private static long estimateSize(Object value) { ... }

boolean isLargeCollection() {
    return largeStore != null;
}

LargeValueStore getLargeStore() {
    return largeStore;
}
```

#### 1.4 Modify `Operation` Iteration Patterns

Update loops to use iterator pattern:

```java
// Before (Operation.java:434-442)
for (int i = 0; i < actListCount; i++) {
    Value actListValue = new Value(actList.get(i));
    ...
}

// After
Iterator<Object> iter = actual.isLargeCollection()
    ? actual.getLargeStore().iterator()
    : actList.iterator();
int i = 0;
while (iter.hasNext()) {
    Value actListValue = new Value(iter.next());
    ...
    i++;
}
```

### Files to Modify
- `Value.java` - Add threshold detection, lazy store creation
- `Operation.java` - Update iteration patterns in 5 locations
- New: `LargeValueStore.java`, `DiskBackedList.java`

### Considerations
- Temp file cleanup via try-with-resources or explicit close()
- Serialization format: JSON lines or Java serialization
- Thread safety for concurrent reads

---

## Phase 2: Structured Failure Results

### Goal
Extend `Result` class to include structured failure data alongside string message.

### Current State
- `Result.java`: Only `pass` boolean and `message` string
- `Operation.java:656-691`: Failures formatted as string

### Design

#### 2.1 Create `Failure` Record

```java
// New file or nested in Result.java
public record Failure(
    String path,           // e.g., "$.foo[0].bar"
    String reason,         // e.g., "not equal"
    Value.Type actualType,
    Value.Type expectedType,
    Object actualValue,    // raw value
    Object expectedValue,  // raw value
    int depth
) {}
```

#### 2.2 Extend `Result` Class

```java
// Result.java modifications
public class Result {
    public static final Result PASS = new Result(true, null, List.of());

    public final String message;
    public final boolean pass;
    public final List<Failure> failures;  // NEW

    public static Result fail(String message, List<Failure> failures) {
        return new Result(false, message, failures);
    }

    // Convenience for backward compatibility
    public static Result fail(String message) {
        return new Result(false, message, List.of());
    }
}
```

#### 2.3 Modify `Operation.getFailureReasons()`

Return both string and structured data:

```java
// Operation.java
Result getResult() {
    if (pass) {
        return Result.PASS;
    }
    List<Failure> structuredFailures = collectStructuredFailures();
    String message = formatFailureMessage(structuredFailures);
    return Result.fail(message, structuredFailures);
}

private List<Failure> collectStructuredFailures() {
    return failures.stream()
        .filter(op -> !previousPaths.contains(op.context.path))
        .map(op -> new Failure(
            op.context.path,
            op.failReason,
            op.actual.type,
            op.expected.type,
            op.actual.getValue(),
            op.expected.getValue(),
            op.context.depth
        ))
        .collect(Collectors.toList());
}
```

### Files to Modify
- `Result.java` - Add `Failure` record and `failures` list
- `Operation.java` - Generate structured failures
- `Match.java:66-76` - Use new `getResult()` method
- `Value.java:213-226` - Use new `getResult()` method

### Backward Compatibility
- `Result.message` remains available for string-based assertions
- `Result.failures` provides structured access when needed
- Existing tests continue to work with `result.message`

---

## Phase 3: Collect All Failures

### Goal
Remove early-return patterns to collect all mismatches before failing.

### Current State - Early Return Locations

| Location | Pattern | Current Behavior |
|----------|---------|------------------|
| `Operation.java:166-168` | EACH match | Stops on first item failure |
| `Operation.java:439-441` | List equality | Stops on first index mismatch |
| `Operation.java:515-517` | Map equality | Stops on first key mismatch |
| `Operation.java:489-491` | Map missing key | Stops on first missing key |
| `Operation.java:600-602` | List contains | Stops on first missing item |

### Design

#### 3.1 Remove Early-Return Patterns

**Pattern A: EACH Match (lines 160-169)**
```java
// Before
for (int i = 0; i < count; i++) {
    ...
    if (!mo.pass) {
        return fail("match each failed at index " + i);
    }
}

// After
boolean anyFailed = false;
List<Integer> failedIndices = new ArrayList<>();
for (int i = 0; i < count; i++) {
    ...
    if (!mo.pass) {
        anyFailed = true;
        failedIndices.add(i);
        // Continue to next iteration instead of returning
    }
}
if (anyFailed) {
    return fail("match each failed at indices " + failedIndices);
}
```

**Pattern B: List Equality (lines 434-442)**
```java
// Before
for (int i = 0; i < actListCount; i++) {
    ...
    if (!mo.pass) {
        return fail("array match failed at index " + i);
    }
}

// After
List<Integer> failedIndices = new ArrayList<>();
for (int i = 0; i < actListCount; i++) {
    ...
    if (!mo.pass) {
        failedIndices.add(i);
    }
}
if (!failedIndices.isEmpty()) {
    return fail("array match failed at indices " + failedIndices);
}
```

**Pattern C: Map Equality (lines 470-517)**
```java
// After
List<String> failedKeys = new ArrayList<>();
for (Map.Entry<String, Object> expEntry : expMap.entrySet()) {
    ...
    if (!mo.pass && type == Match.Type.EQUALS) {
        failedKeys.add(key);
        // Continue instead of returning
    }
}
if (!failedKeys.isEmpty()) {
    return fail("match failed for keys: " + failedKeys);
}
```

**Pattern D: List Contains (lines 559-602)**
```java
// After
List<String> notFoundItems = new ArrayList<>();
for (Object exp : expList) {
    ...
    if (!found && type != Match.Type.CONTAINS_ANY && type != Match.Type.CONTAINS_ANY_DEEP) {
        notFoundItems.add(expListValue.getAsString());
        // Continue instead of returning
    }
}
if (!notFoundItems.isEmpty()) {
    return fail("actual array does not contain expected items - " + notFoundItems);
}
```

#### 3.3 Preserve Early-Exit for CONTAINS_ANY

Keep early return for match types that should exit on first success:
```java
if (type == Match.Type.CONTAINS_ANY || type == Match.Type.CONTAINS_ANY_DEEP) {
    return true; // KEEP this early exit
}
```

### Files to Modify
- `Operation.java` - Modify ~6 early-return patterns
- `MatchContext.java` - Optional: add configuration flag

### Test Updates
Tests that check specific failure messages will need updating to handle multiple failures in messages.

---

## Phase 4: WITHIN Assertion

### Goal
Add WITHIN assertion type (reverse of CONTAINS): checks if actual is within expected.

### Semantics

| Type | Meaning | Example |
|------|---------|---------|
| CONTAINS | actual contains expected | `["a","b","c"]` contains `["a"]` |
| WITHIN | actual is within expected | `["a"]` is within `["a","b","c"]` |

### Design

Only two types needed: `WITHIN` and `NOT_WITHIN`.

#### 4.1 Add Match.Type Enum Values

```java
// Match.java additions
public enum Type {
    // ... existing ...
    WITHIN,              // actual is subset of expected
    NOT_WITHIN
}
```

#### 4.2 Add Macro Syntax

```java
// Operation.java:105-121 additions
private static Match.Type macroToMatchType(boolean each, String macro) {
    // ... existing ...
    } else if (macro.startsWith("<")) {
        return Match.Type.WITHIN;
    } else if (macro.startsWith("!<")) {
        return Match.Type.NOT_WITHIN;
    }
    // ... existing ...
}
```

#### 4.3 Implement `actualWithinExpected()`

```java
// Operation.java new method
private boolean actualWithinExpected() {
    switch (actual.type) {
        case STRING:
            String actString = actual.getValue();
            String expString = expected.getValue();
            return expString.contains(actString);  // REVERSED

        case LIST:
            // For each item in actual, check if it exists in expected
            List<Object> actList = actual.getValue();
            List<Object> expList = expected.getValue();
            for (Object act : actList) {
                boolean found = false;
                Value actListValue = new Value(act);
                for (Object exp : expList) {
                    Value expListValue = new Value(exp);
                    Operation mo = new Operation(context, Match.Type.EQUALS,
                        actListValue, expListValue, matchEachEmptyAllowed);
                    mo.execute();
                    if (mo.pass) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return fail("expected does not contain actual item - " + actListValue.getAsString());
                }
            }
            return true;

        case MAP:
            // For each key in actual, check if it exists in expected with same value
            return matchMapWithin(actual.getValue(), expected.getValue());

        // ... XML handling similar to CONTAINS
    }
}
```

#### 4.4 Add execute() Case

```java
// Operation.java:238-254 additions
case WITHIN:
    return actualWithinExpected() ? pass() : fail("actual is not within expected");
case NOT_WITHIN:
    return actualWithinExpected() ? fail("actual is within expected") : pass();
```

#### 4.5 Add Value Convenience Methods

```java
// Value.java additions
public Result within(Object expected) {
    return is(Match.Type.WITHIN, expected);
}

public Result notWithin(Object expected) {
    return is(Match.Type.NOT_WITHIN, expected);
}
```

#### 4.6 Add jsGet() Cases

```java
// Value.java:316-334 additions
case "within" -> call(Match.Type.WITHIN);
case "notWithin" -> call(Match.Type.NOT_WITHIN);
```

### Files to Modify
- `Match.java` - Add WITHIN, NOT_WITHIN enum values
- `Operation.java` - Add macro parsing, execute cases, actualWithinExpected()
- `Value.java` - Add within() and notWithin() convenience methods

---

## Summary of Files to Modify

| File | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| `Operation.java` | Iteration patterns | getResult() | Early-return patterns | WITHIN logic |
| `Value.java` | Large store | - | - | within(), notWithin() |
| `Result.java` | - | Add Failure, list | - | - |
| `Match.java` | - | - | - | WITHIN, NOT_WITHIN |
| `MatchContext.java` | - | - | Config flag (optional) | - |
| `LargeValueStore.java` | NEW | - | - | - |
| `DiskBackedList.java` | NEW | - | - | - |

## Known Issue: CONTAINS_ONLY Error Output

When CONTAINS_ONLY fails, the current output is confusing because it shows all nested comparison failures:

```cucumber
* def response = [{a: 1}, {b: 2}]
* match response contains only [{a: 2}, {b: 2}]
```

Produces:
```
match failed: CONTAINS_ONLY
  $ | actual does not contain expected | actual array does not contain expected item - {"a":2} (LIST:LIST)
  [{"a":1},{"b":2}]
  [{"a":2},{"b":2}]

    $[1] | not equal | actual does not contain key - 'a' (MAP:MAP)
    {"b":2}
    {"a":2}

    $[0] | not equal | match failed for name: 'a' (MAP:MAP)
    {"a":1}
    {"a":2}
```

**Solution:** With structured failures (Phase 2), implement smarter error reporting:
- For CONTAINS_ONLY, only show the "expected item not found" message at the top level
- Suppress nested comparison details that are just "how we determined it wasn't found"
- Or show only the "closest match" with its single point of failure

---

## Test Updates Required

1. **Phase 1**: Add tests for large collection handling
2. **Phase 2**: Add tests for `Result.failures` structure
3. **Phase 3**: Update tests expecting single-failure messages to expect multi-failure messages
4. **Phase 4**: Add comprehensive WITHIN assertion tests

---

## Migration Notes

- Phase 2 is backward compatible (failures list is additive)
- Phase 3 changes error message format (may break string assertions in tests)
- Phase 4 adds new keywords (no breaking changes)
- Consider feature flag for Phase 3 during transition period
