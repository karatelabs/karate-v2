# JavaScript-Java Type Interop

This document tracks the bidirectional type conversion between JS and Java in karate-js.

## Completed: JsPrimitive Interface

Added `JsPrimitive` marker interface extending `JavaMirror` for boxed primitive types (JsNumber, JsString, JsBoolean).

**Benefits**:
- Single `instanceof JsPrimitive` check instead of 3 separate checks
- Cleaner code in `Terms.isTruthy()`, `Terms.typeOf()`, `Terms.eq()`
- `toJava()` provides uniform unwrapping for loose equality

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
- `toJava()` - Returns idiomatic Java type for **external** use (e.g., JsDate → Date)
- `getInternalValue()` - Returns raw internal value for **internal** operations (e.g., JsDate → Long millis)

**Implementation**:
```java
interface JavaMirror {
    Object toJava();

    default Object getInternalValue() {
        return toJava();  // Default: same as toJava()
    }
}
```

**What each type returns**:

| Class | `toJava()` | `getInternalValue()` |
|-------|------------|---------------------|
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
- Extensible: new JavaMirror types just need to implement `getInternalValue()`

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

## In Progress: JsDate Internal Migration

**Goal**: Refactor JsDate internals from `java.util.Date` + `Calendar` to `long millis` + `java.time`.

**Status**: Core migration DONE. Remaining: fix test failures related to `new Date(original)` copy semantics and date parsing.

### Completed Changes
- Internal field: `private long millis` (was `private final Date value`)
- Getters use: `ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())`
- Setters use: `ZonedDateTime` + `plusDays/plusMonths` for overflow handling
- Formatting uses: `DateTimeFormatter` (thread-safe)
- Added `getInternalValue()` returning millis for numeric operations
- `call()` method returns JsDate for `new` calls, Date for function calls (via CallInfo)

### Remaining Issues
1. **Date parsing**: `parseToMillis()` needs to handle more formats
2. **Test expectations**: Some tests compare `JsDate.parse()` (returns Date) with `get("a")` (returns JsDate)
3. **Date comparison**: `date2 > date1` with JsDate objects - verify `getInternalValue()` is used

### Key Implementation Details

### Refactor Steps

1. **Change internal field**:
   ```java
   // FROM
   private final Date value;
   // TO
   private long millis;
   ```

2. **Update constructors** - all should set `this.millis = ...`:
   ```java
   JsDate() { this.millis = System.currentTimeMillis(); }
   JsDate(long timestamp) { this.millis = timestamp; }
   JsDate(Date date) { this.millis = date.getTime(); }
   JsDate(Instant i) { this.millis = i.toEpochMilli(); }
   // etc.
   ```

3. **Add helper method** for local time operations:
   ```java
   private ZonedDateTime toZonedDateTime() {
       return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
   }
   ```

4. **Update getters** (getFullYear, getMonth, getDate, getDay, getHours, getMinutes, getSeconds, getMilliseconds):
   ```java
   case "getFullYear" -> (JsCallable) (ctx, args) -> asJsDate(ctx).toZonedDateTime().getYear();
   case "getMonth" -> (JsCallable) (ctx, args) -> asJsDate(ctx).toZonedDateTime().getMonthValue() - 1; // 0-indexed
   case "getDate" -> (JsCallable) (ctx, args) -> asJsDate(ctx).toZonedDateTime().getDayOfMonth();
   case "getDay" -> (JsCallable) (ctx, args) -> asJsDate(ctx).toZonedDateTime().getDayOfWeek().getValue() % 7; // Sun=0
   case "getHours" -> (JsCallable) (ctx, args) -> asJsDate(ctx).toZonedDateTime().getHour();
   // etc.
   ```

5. **Update setters** (setFullYear, setMonth, setDate, setHours, setMinutes, setSeconds, setMilliseconds, setTime):
   ```java
   case "setMonth" -> (JsCallable) (ctx, args) -> {
       JsDate jsDate = asJsDate(ctx);
       int month = ((Number) args[0]).intValue();
       ZonedDateTime zdt = jsDate.toZonedDateTime().withMonth(month + 1); // 0-indexed
       jsDate.millis = zdt.toInstant().toEpochMilli();
       return jsDate.millis;
   };
   ```

6. **Update formatting methods**:
   ```java
   private static final DateTimeFormatter ISO_FORMATTER =
       DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

   case "toISOString" -> (JsCallable) (ctx, args) ->
       ISO_FORMATTER.format(Instant.ofEpochMilli(asJsDate(ctx).millis));
   ```

7. **Update helper methods**:
   ```java
   // FROM
   Date asDate(Context context) { ... return date.value; }
   // TO
   JsDate asJsDate(Context context) {
       if (context.getThisObject() instanceof JsDate date) return date;
       return this;
   }
   ```

8. **Update toJava()**:
   ```java
   @Override
   public Object toJava() {
       return new Date(millis); // backward compatible
   }
   ```

9. **Update call() method** - returns `Date` for backward compat but creates JsDate internally

### Benefits After Migration
- Thread-safe formatting (no `synchronized` needed)
- Modern API (java.time is cleaner than Calendar)
- Simpler code (no Calendar boilerplate)
- Consistent internal representation (just millis)

---

## TODO: JsRegex + JavaMirror

**Goal**: Make `JsRegex` implement `JavaMirror` so `toJava()` returns `java.util.regex.Pattern`.

**Challenge**: Unlike Number/String/Boolean, `RegExp('foo')` without `new` ALSO returns an object in JS (not a primitive).

**Current behavior**:
| Expression | Returns |
|------------|---------|
| `new RegExp('foo')` | JsRegex |
| `RegExp('foo')` | JsRegex |
| `/foo/` | JsRegex |

**If JavaMirror added naively**, `RegExp('foo')` would return `Pattern` (wrong).

**Proposed fix**: Skip `toJava()` unwrapping for JsRegex specifically:
```java
// In Interpreter.evalFnCall()
if (result instanceof JavaMirror jm && !(result instanceof JsRegex)) {
    return newKeyword ? result : jm.toJava();
}
```

Or handle in `JsRegex.call()` similar to boxed primitives - always return JsRegex.

---

## TODO: Promise ↔ Java

**Goal**: Bidirectional mapping between JS Promise and Java async constructs.

**Options for Java side**:
- `CompletableFuture<T>` - most flexible, supports chaining
- `Future<T>` - simpler but blocking
- `Function<T, R>` - for callbacks/continuations

**Proposed mapping**:
```java
class JsPromise extends JsObject implements JavaMirror {
    private CompletableFuture<Object> future;

    @Override
    public Object toJava() {
        return future;
    }

    // JS: promise.then(fn)
    // Java: future.thenApply(fn)
}
```

**Considerations**:
- Error handling: `.catch()` ↔ `.exceptionally()`
- Chaining: `.then().then()` ↔ `.thenApply().thenApply()`
- `Promise.all()` ↔ `CompletableFuture.allOf()`
- `Promise.race()` ↔ `CompletableFuture.anyOf()`

---

## Future: Other Bidirectional Conversions

| JS Type | Java Type | Notes |
|---------|-----------|-------|
| `Map` | `java.util.Map` | Already works via JsObject |
| `Set` | `java.util.Set` | Could add JsSet |
| `ArrayBuffer` | `byte[]` | For binary data |
| `TypedArray` | `ByteBuffer` / primitive arrays | Int8Array, Uint8Array, etc. |
| `Symbol` | Custom `JsSymbol` | Unique identifiers |
| `BigInt` | `java.math.BigInteger` | Arbitrary precision |
| `Iterator` | `java.util.Iterator` | For `for..of` interop |
| `Generator` | `Iterator` with state | Yield support |
| `Proxy` | Dynamic proxy | Metaprogramming |
| `WeakMap/WeakSet` | `WeakHashMap` | GC-friendly references |

---

## Design Principles

1. **Lazy overhead**: Only create wrapper objects when needed (e.g., `CallInfo` only for `new`)
2. **Internal vs external representation**: Internal state can differ from `toJava()` output
3. **Preserve JS semantics**: `typeof`, `instanceof`, truthiness must match JS spec
4. **Java interop friendly**: `toJava()` should return idiomatic Java types
5. **Performance first**: Primitives stay as Java primitives in the common case
6. **Flexible input, consistent output**: Accept multiple Java types as input, return one preferred type
7. **Unwrap first pattern**: Use `getInternalValue()` to unwrap JavaMirror before switching on raw types

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
- `JavaMirror.toJava()` - called when result leaves the JS engine

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

