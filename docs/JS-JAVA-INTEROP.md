# JavaScript-Java Type Interop

This document tracks the bidirectional type conversion between JS and Java in karate-js.

## Completed: JsPrimitive Interface

Added `JsPrimitive` marker interface extending `JavaMirror` for boxed primitive types (JsNumber, JsString, JsBoolean).

**Benefits**:
- Single `instanceof JsPrimitive` check instead of 3 separate checks
- Cleaner code in `Terms.isTruthy()`, `Terms.typeOf()`, `Terms.eq()`
- `getJavaValue()` provides uniform unwrapping for loose equality

**Files changed**: `JsPrimitive.java` (new), `JsNumber.java`, `JsString.java`, `JsBoolean.java`, `Terms.java`

---

## Completed: JavaMirror.getInternalValue()

Added `getInternalValue()` method to `JavaMirror` interface for consistent internal value access.

**Problem solved**: Code in `Terms.java` had to handle each wrapper type separately:
```java
// OLD: Many cases for each wrapper type
return switch (o) {
    case JsNumber jn -> jn.value;
    case JsString js -> toNumber(js.text.trim());
    case JsBoolean jb -> jb.value ? 1 : 0;
    case Number n -> n;
    // ... more cases
};
```

**Solution**: Two methods on `JavaMirror`:
- `getJavaValue()` - Returns idiomatic Java type for **external** use (e.g., JsDate → Date)
- `getInternalValue()` - Returns raw internal value for **internal** operations (e.g., JsDate → Long millis)

**Implementation**:
```java
interface JavaMirror {
    Object getJavaValue();

    default Object getInternalValue() {
        return getJavaValue();  // Default: same as getJavaValue()
    }
}
```

**What each type returns**:

| Class | `getJavaValue()` | `getInternalValue()` |
|-------|------------------|---------------------|
| JsNumber | Number | Number (default) |
| JsString | String | String (default) |
| JsBoolean | Boolean | Boolean (default) |
| JsDate | Date | Long (millis) |

**Refactored pattern in Terms.java**:
```java
// NEW: Unwrap first, then switch on raw types
static Number objectToNumber(Object o) {
    if (o instanceof JavaMirror jm) {
        o = jm.getInternalValue();
    }
    return switch (o) {
        case Number n -> n;
        case Boolean b -> b ? 1 : 0;
        case Date d -> d.getTime();
        case String s -> toNumber(s.trim());
        case null -> 0;
        default -> Double.NaN;
    };
}
```

**Benefits**:
- Cleaner code: "unwrap first, then switch on raw types" pattern
- JsDate comparison/arithmetic works correctly (millis is a Number)
- Single point of type unwrapping
- Extensible: new JavaMirror types just implement `getJavaValue()` (and optionally `getInternalValue()` if different)

**Files changed**: `JavaMirror.java`, `JsDate.java`, `Terms.java`

---

## Completed: Boxed Primitives (CallInfo)

Added `CallInfo` to provide reflection-like awareness for callables about their invocation context.

**Problem solved**: JS constructors behave differently with vs without `new`:
- `Number(5)` → primitive `5`
- `new Number(5)` → boxed Number object

**Implementation**:
- `CallInfo.java` - holds `constructor` flag and `called` reference
- `Context.getCallInfo()` - returns null for normal calls (zero overhead), CallInfo for `new`
- `JsNumber`, `JsString`, `JsBoolean` check `context.getCallInfo().constructor` to decide return type
- `Terms.typeOf()`, `isTruthy()`, `objectToNumber()`, `eq()` updated to handle boxed types

**Files changed**: `CallInfo.java` (new), `Context.java`, `CoreContext.java`, `Interpreter.java`, `JsNumber.java`, `JsString.java`, `JsBoolean.java`, `Terms.java`

---

## Completed: JsDate Internal Migration

**Goal**: Refactor JsDate internals from `java.util.Date` + `Calendar` to `long millis` + `java.time`.

**Status**: DONE.

### Changes
- Internal field: `private long millis` (was `private final Date value`)
- Getters use: `ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())`
- Setters use: `ZonedDateTime` + `plusDays/plusMonths` for overflow handling
- Formatting uses: `DateTimeFormatter` (thread-safe)
- Added `getInternalValue()` returning millis for numeric operations
- `call()` method returns JsDate for `new` calls, Date for function calls (via CallInfo)
- Uses `fromThis(context)` pattern for "this" resolution

### Benefits
- Thread-safe formatting (no `synchronized` needed)
- Modern API (java.time is cleaner than Calendar)
- Simpler code (no Calendar boilerplate)
- Consistent internal representation (just millis)

**Files changed**: `JsDate.java`

---

## Completed: fromThis() Pattern for "this" Resolution

**Goal**: Unify "this" handling across all JsObject subclasses with a polymorphic `fromThis(Context)` method.

**Problem solved**: Inconsistent patterns for resolving `this` in prototype methods:
- JsDate had `asJsDate(Context)` returning `JsDate`
- JsArray had `asList(Context)` returning raw `List<Object>`
- JsObject had `asMap(Context)` returning raw `Map<String, Object>`
- JsRegex had inline checks duplicated in each method

**Solution**: Base method on `JsObject` with covariant overrides:

```java
// JsObject - base implementation
JsObject fromThis(Context context) {
    Object thisObject = context.getThisObject();
    if (thisObject instanceof JsObject jo) return jo;
    if (thisObject instanceof Map<?, ?> map) return new JsObject((Map<String, Object>) map);
    return this;
}
```

**Overrides with covariant returns**:

| Class | `fromThis()` returns | Also handles raw type |
|-------|---------------------|----------------------|
| JsObject | JsObject | Map |
| JsArray | JsArray | List |
| JsDate | JsDate | - |
| JsRegex | JsRegex | - |
| JsString | JsString | String |
| JsNumber | JsNumber | Number |
| JsUint8Array | JsUint8Array | byte[] |

**Convenience helpers now delegate**:
```java
List<Object> asList(Context c) { return fromThis(c).list; }
Map<String, Object> asMap(Context c) { return fromThis(c).toMap(); }
String asString(Context c) { return fromThis(c).text; }
```

**Benefits**:
- Single consistent pattern across all types
- Covariant returns give type safety
- Handles both wrapper and raw types (List/Map/String/Number)
- Enables proper `.call()` support: `Number.prototype.toFixed.call(5, 2)`

**Files changed**: `JsObject.java`, `JsArray.java`, `JsDate.java`, `JsRegex.java`, `JsString.java`, `JsNumber.java`, `JsUint8Array.java`

---

## Completed: Terms.toObjectLike() Helper

**Goal**: Consolidate object wrapping logic for property access in `JsProperty.get()`.

**Problem solved**: Scattered type checks and wrapping in JsProperty.get():
```java
// OLD: 3 separate wrapping patterns
if (object instanceof ObjectLike objectLike) {
    return objectLike.get(name);
}
if (object instanceof List) {
    return (new JsArray((List<Object>) object).get(name));
}
JavaMirror mirror = Terms.toJavaMirror(object);
if (mirror instanceof ObjectLike ol) {
    return ol.get(name);
}
```

**Solution**: New helper method in `Terms.java`:
```java
static ObjectLike toObjectLike(Object o) {
    if (o instanceof ObjectLike ol) return ol;
    if (o instanceof List list) return new JsArray(list);
    JavaMirror mirror = toJavaMirror(o);
    return mirror instanceof ObjectLike ol ? ol : null;
}
```

**Simplified JsProperty.get()**:
```java
// NEW: Single call (Map optimization preserved separately)
ObjectLike ol = Terms.toObjectLike(object);
if (ol != null) {
    return ol.get(name);
}
```

**Files changed**: `Terms.java`, `JsProperty.java`

---

## TODO: JsRegex + JavaMirror

**Goal**: Make `JsRegex` implement `JavaMirror` so `getJavaValue()` returns `java.util.regex.Pattern`.

**Challenge**: Unlike Number/String/Boolean, `RegExp('foo')` without `new` ALSO returns an object in JS (not a primitive).

**Current behavior**:
| Expression | Returns |
|------------|---------|
| `new RegExp('foo')` | JsRegex |
| `RegExp('foo')` | JsRegex |
| `/foo/` | JsRegex |

**If JavaMirror added naively**, `RegExp('foo')` would return `Pattern` (wrong).

**Proposed fix**: Skip `getJavaValue()` unwrapping for JsRegex specifically:
```java
// In Interpreter.evalFnCall()
if (result instanceof JavaMirror jm && !(result instanceof JsRegex)) {
    return newKeyword ? result : jm.getJavaValue();
}
```

Or handle in `JsRegex.call()` similar to boxed primitives - always return JsRegex.

---

## Roadmap: Prioritized TODO

For a JS engine focused on **JVM glue logic** (API testing, data transformation, business rules).

### High Priority

| Feature | Java Type | Why | Status |
|---------|-----------|-----|--------|
| **BigInt** | `BigInteger` | Large IDs, timestamps, financial identifiers | TODO |
| **BigDecimal** | `BigDecimal` | Money/finance - floating point is dangerous | TODO |
| **ArrayBuffer** | `byte[]` | Raw binary data container | TODO |
| **Uint8Array** | `byte[]` | TypedArray view over ArrayBuffer | DONE (`JsUint8Array`) |

### Medium Priority

| Feature | Java Type | Why | Status |
|---------|-----------|-----|--------|
| **Set** | `java.util.Set` | Deduplication, membership checks | TODO |
| **Map (proper JS Map)** | `java.util.Map` | Ordered keys, non-string keys | TODO (plain objects work) |
| **Iterator/for-of** | `java.util.Iterator` | Clean iteration over Java collections | TODO |

### Lower Priority (Roadmap)

| Feature | Java Type | Why | Status |
|---------|-----------|-----|--------|
| **Promise/async-await** | `CompletableFuture` | Async APIs | TODO |
| **Generator/yield** | `Iterator` with state | Lazy evaluation | TODO |
| **Symbol** | Custom `JsSymbol` | Unique identifiers (library internals) | TODO |
| **Proxy** | Dynamic proxy | Metaprogramming | TODO |
| **WeakMap/WeakSet** | `WeakHashMap` | GC-friendly references | TODO |

### Notes

**JsFunction → JsCallable**: When a JS function is passed to Java, users cast to `JsCallable` which has a simple contract: `Object call(Context context, Object... args)`. Wrapping into `java.util.function.Function` was considered but rejected - the `Context` dependency is fundamental to JS semantics (this binding, closures, scope). Keeping `JsCallable` as the API gives users explicit control over context with no hidden behavior.

**BigDecimal**: Critical for finance/money calculations where `0.1 + 0.2 !== 0.3` is unacceptable.

**ArrayBuffer vs Uint8Array**: `JsUint8Array` is a TypedArray VIEW. `ArrayBuffer` is the raw container. Both map to `byte[]` on Java side, but JS semantics differ (multiple views can share one buffer).

---

## Design Principles

1. **Lazy overhead**: Only create wrapper objects when needed (e.g., `CallInfo` only for `new`)
2. **Internal vs external representation**: Internal state can differ from `getJavaValue()` output
3. **Preserve JS semantics**: `typeof`, `instanceof`, truthiness must match JS spec
4. **Java interop friendly**: `getJavaValue()` should return idiomatic Java types
5. **Performance first**: Primitives stay as Java primitives in the common case
6. **Flexible input, consistent output**: Accept multiple Java types as input, return one preferred type
7. **Unwrap first pattern**: Use `getInternalValue()` to unwrap JavaMirror before switching on raw types
8. **Consistent "this" resolution**: Use `fromThis(Context)` pattern across all JsObject subclasses

---

## Bidirectional Conversion Pattern

For types with multiple Java equivalents, use this pattern:

```
┌─────────────────┐      Java → JS       ┌─────────────┐
│  java.util.Date │ ──────────────────►  │             │
│  Instant        │ ──────────────────►  │   JsDate    │
│  LocalDateTime  │ ──────────────────►  │  (internal  │
│  LocalDate      │ ──────────────────►  │   millis)   │
│  ZonedDateTime  │ ──────────────────►  │             │
└─────────────────┘                      └──────┬──────┘
                                                │
                         JS → Java              │
                    ◄───────────────────────────┘
                    │
                    ▼
            ┌───────────────┐
            │ java.util.Date │  (or Instant, configurable)
            └───────────────┘
```

**Where to implement Java → JS conversion**:
- `Engine.put()` - for context variables set before eval (convert Date/LocalDate/etc → JsDate)
- `ExternalBridge.java` - for Java objects accessed via `Java.type()`
- `Terms.java` or similar - for type coercion during operations

**Test locations**:
- `ExternalBridgeTest.java` - test `engine.put()` with Java date types and `Java.type()` interop

**Example tests (in ExternalBridgeTest.java)**:
```java
@Test
void testJavaDateConversion() {
    Engine engine = new Engine();

    // java.util.Date should be usable as JS Date
    engine.put("javaDate", new java.util.Date(1609459200000L));
    assertEquals(1609459200000L, engine.eval("javaDate.getTime()"));
    assertEquals(2021, engine.eval("javaDate.getFullYear()"));
    assertEquals("object", engine.eval("typeof javaDate"));

    // LocalDateTime should also work
    engine.put("localDT", LocalDateTime.of(2025, 3, 15, 10, 30));
    assertEquals(2025, engine.eval("localDT.getFullYear()"));
    assertEquals(2, engine.eval("localDT.getMonth()"));  // 0-indexed

    // Instant should work
    engine.put("instant", Instant.ofEpochMilli(1609459200000L));
    assertEquals(1609459200000L, engine.eval("instant.getTime()"));
}
```

**Where to implement JS → Java conversion**:
- `JavaMirror.getJavaValue()` - called when result leaves the JS engine

This pattern applies to:
- **Date**: Date, Instant, LocalDateTime, LocalDate, ZonedDateTime → JsDate → Date/Instant
- **Regex**: Pattern, String → JsRegex → Pattern
- **Promise**: CompletableFuture, Future → JsPromise → CompletableFuture
- **Collections**: List, Set, array → JsArray → List

---

## Completed: Lazy Input Conversion Strategy

**Decision**: Keep lazy conversion at point-of-use in `JsProperty.get()` via `Terms.toJavaMirror()`.

**Why lazy over up-front**:
1. **Thread-safety**: Engine bindings may be updated by external threads. Lazy conversion naturally handles this without synchronization issues.
2. **Simplicity**: Single conversion point in `toJavaMirror()` handles all entry paths (Engine.put, evalWith, direct map access, ExternalBridge).
3. **Performance**: The `instanceof` chain is fast; overhead is negligible compared to JS interpretation.
4. **Flexibility**: Users can access `engine.getBindings()` directly and modify it; lazy conversion handles this transparently.

**Implementation**: Extend `Terms.toJavaMirror()` to handle java.time types:

```java
static JavaMirror toJavaMirror(Object o) {
    return switch (o) {
        case String s -> new JsString(s);
        case Number n -> new JsNumber(n);
        case Boolean b -> new JsBoolean(b);
        case java.util.Date d -> new JsDate(d);
        case Instant i -> new JsDate(i);
        case LocalDateTime ldt -> new JsDate(ldt);
        case LocalDate ld -> new JsDate(ld);
        case ZonedDateTime zdt -> new JsDate(zdt);
        case byte[] bytes -> new JsUint8Array(bytes);
        case null, default -> null;
    };
}
```

**Files changed**: `Terms.java`, `JsDate.java` (add constructors for java.time types)

---

## TODO: Engine State JSON Serialization

**Goal**: Persist engine state (bindings) to JSON for session continuity, debugging, or transfer.

**Types requiring special handling**:

| JS/Internal Type | JSON Representation | Notes |
|------------------|---------------------|-------|
| `JsDate` | `{"$type":"date","value":1609459200000}` | Epoch millis |
| `JsRegex` | `{"$type":"regex","pattern":"foo","flags":"gi"}` | Pattern + flags |
| `JsFunction` | `{"$type":"function","source":"..."}` or skip | May not be serializable |
| `Terms.UNDEFINED` | `{"$type":"undefined"}` | Distinguish from null |
| `Double.NaN` | `{"$type":"NaN"}` | Not valid JSON number |
| `Double.POSITIVE_INFINITY` | `{"$type":"Infinity"}` | Not valid JSON number |
| `Double.NEGATIVE_INFINITY` | `{"$type":"-Infinity"}` | Not valid JSON number |
| `JsUint8Array` / `byte[]` | `{"$type":"bytes","value":"base64..."}` | Base64 encoded |
| Circular reference | Error or `{"$type":"circular","ref":"$.path"}` | Detect and handle |

**Proposed API**:
```java
// Engine.java
public String toJson() {
    return JsSerializer.toJson(bindings);
}

public static Engine fromJson(String json) {
    Engine engine = new Engine();
    engine.bindings.putAll(JsSerializer.fromJson(json));
    return engine;
}
```

**Implementation notes**:
- Use `$type` prefix convention for special types (unlikely to conflict with user data)
- Functions: Option to serialize source code or skip entirely
- Circular references: Track visited objects during serialization
- Consider: Store metadata like engine version for compatibility checks

**Types that are naturally JSON-compatible** (no special handling):
- `null`
- `Boolean` / `boolean`
- `Number` (Integer, Long, Double - except NaN/Infinity)
- `String`
- `List` / `JsArray`
- `Map` / `JsObject`

