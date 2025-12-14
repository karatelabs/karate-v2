# JavaScript-Java Type Interop

This document tracks the bidirectional type conversion between JS and Java in karate-js.

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

## TODO: JsDate Migration

**Goal**: Migrate from `java.util.Date` to `java.time` classes while maintaining full Java interop.

**Challenge**: JS Date is mutable (`setMonth()`, `setDate()`, etc.), but java.time classes are immutable.

### Bidirectional Conversion

**Java → JS (input)**: When Java passes date objects into the engine, convert to JsDate:
```java
// In the engine's input conversion logic (e.g., ExternalBridge or similar)
if (value instanceof java.util.Date d) {
    return new JsDate(d.getTime());
}
if (value instanceof Instant i) {
    return new JsDate(i.toEpochMilli());
}
if (value instanceof LocalDateTime ldt) {
    return new JsDate(ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
}
if (value instanceof LocalDate ld) {
    return new JsDate(ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
}
if (value instanceof ZonedDateTime zdt) {
    return new JsDate(zdt.toInstant().toEpochMilli());
}
```

**JS → Java (output)**: `toJava()` returns the appropriate type:
```java
@Override
public Object toJava() {
    // Option 1: Return java.util.Date for backward compatibility
    return new Date(millis);

    // Option 2: Return Instant (preferred modern approach)
    // return Instant.ofEpochMilli(millis);
}
```

### Proposed Implementation

```java
class JsDate extends JsObject implements JavaMirror {
    private long millis;  // mutable internal representation

    // Constructors for all Java date types
    JsDate(java.util.Date date) { this.millis = date.getTime(); }
    JsDate(Instant instant) { this.millis = instant.toEpochMilli(); }
    JsDate(LocalDateTime ldt) { this.millis = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    JsDate(LocalDate ld) { this.millis = ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    JsDate(ZonedDateTime zdt) { this.millis = zdt.toInstant().toEpochMilli(); }

    @Override
    public Object toJava() {
        return new Date(millis);  // or Instant.ofEpochMilli(millis)
    }

    void setMonth(int m) {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        ldt = ldt.withMonth(m + 1);  // JS months are 0-indexed
        this.millis = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
```

**Key insight**: Use `long millis` internally for mutability. Accept any Java date type as input, output a consistent type via `toJava()`.

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
- `EngineTest.java` - test `engine.put()` with Java date types
- `ExternalBridgeTest.java` - test `Java.type()` interop

**Example test for EngineTest.java**:
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
