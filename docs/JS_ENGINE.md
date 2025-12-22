# JavaScript Engine Reference

This document describes the JavaScript engine architecture, type system, and Java interop patterns for karate-js.

> See also: [ROADMAP.md](./ROADMAP.md#javascript-engine-karate-js) for pending work | [karate-js README](../karate-js/README.md)

---

## Overview

karate-js is a lightweight JavaScript engine implemented in Java, designed for:
- Thread-safe concurrent execution
- Seamless Java interop
- API testing and data transformation
- Minimal footprint (no GraalVM dependency)

---

## Design Principles

1. **Lazy overhead** - Only create wrapper objects when needed (e.g., `CallInfo` only for `new`)
2. **Internal vs external representation** - Internal state can differ from `getJavaValue()` output
3. **Preserve JS semantics** - `typeof`, `instanceof`, truthiness must match JS spec
4. **Java interop friendly** - `getJavaValue()` returns idiomatic Java types
5. **Performance first** - Primitives stay as Java primitives in the common case
6. **Flexible input, consistent output** - Accept multiple Java types as input, return one preferred type
7. **Unwrap first pattern** - Use `getInternalValue()` to unwrap JavaMirror before switching on raw types
8. **Consistent "this" resolution** - Use `fromThis(Context)` pattern across all JsObject subclasses

---

## Type System

### Core Interfaces

```java
// All JS wrapper types implement this
interface JavaMirror {
    Object getJavaValue();              // For external use (e.g., JsDate → Date)

    default Object getInternalValue() { // For internal operations (e.g., JsDate → Long millis)
        return getJavaValue();
    }
}

// Marker for boxed primitives (JsNumber, JsString, JsBoolean)
interface JsPrimitive extends JavaMirror {
    // Enables single instanceof check instead of 3 separate checks
}
```

### Type Mapping

| JS Type | Java Wrapper | `getJavaValue()` | `getInternalValue()` |
|---------|--------------|------------------|---------------------|
| Number | JsNumber | Number | Number |
| String | JsString | String | String |
| Boolean | JsBoolean | Boolean | Boolean |
| Date | JsDate | Date | Long (millis) |
| RegExp | JsRegex | Pattern | Pattern |
| Array | JsArray | List | List |
| Object | JsObject | Map | Map |
| Uint8Array | JsUint8Array | byte[] | byte[] |

### Boxed Primitives

JS constructors behave differently with vs without `new`:

```javascript
Number(5)      // → primitive 5
new Number(5)  // → boxed Number object
```

The engine uses `CallInfo` to track invocation context:
- `context.getCallInfo().constructor` is true for `new` calls
- Zero overhead for normal calls (returns null)

---

## Java ↔ JS Type Conversion

### Bidirectional Pattern

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
           │ java.util.Date │
           └───────────────┘
```

### Lazy Input Conversion

Conversion happens at point-of-use in `Terms.toJavaMirror()`:

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

**Why lazy?**
- Thread-safety: Engine bindings may be updated by external threads
- Simplicity: Single conversion point handles all entry paths
- Performance: `instanceof` chain is fast; overhead is negligible

---

## The `fromThis()` Pattern

Unified "this" resolution across all JsObject subclasses:

```java
// JsObject - base implementation
JsObject fromThis(Context context) {
    Object thisObject = context.getThisObject();
    if (thisObject instanceof JsObject jo) return jo;
    if (thisObject instanceof Map<?, ?> map) return new JsObject((Map<String, Object>) map);
    return this;
}
```

**Covariant overrides:**

| Class | `fromThis()` returns | Also handles raw type |
|-------|---------------------|----------------------|
| JsObject | JsObject | Map |
| JsArray | JsArray | List |
| JsDate | JsDate | - |
| JsRegex | JsRegex | - |
| JsString | JsString | String |
| JsNumber | JsNumber | Number |
| JsUint8Array | JsUint8Array | byte[] |

This enables proper `.call()` support:
```javascript
Number.prototype.toFixed.call(5, 2)  // Works correctly
```

---

## The `toObjectLike()` Helper

Consolidates object wrapping for property access:

```java
static ObjectLike toObjectLike(Object o) {
    if (o instanceof ObjectLike ol) return ol;
    if (o instanceof List list) return new JsArray(list);
    JavaMirror mirror = toJavaMirror(o);
    return mirror instanceof ObjectLike ol ? ol : null;
}
```

---

## JsDate Implementation

Internal representation uses `long millis` with `java.time` for operations:

```java
public class JsDate implements JavaMirror, Callable, ObjectLike {
    private long millis;

    // Getters use ZonedDateTime
    public int getFullYear() {
        return toZonedDateTime().getYear();
    }

    // Setters use java.time for overflow handling
    public void setDate(int day) {
        ZonedDateTime zdt = toZonedDateTime().withDayOfMonth(day);
        this.millis = zdt.toInstant().toEpochMilli();
    }

    @Override
    public Object getJavaValue() { return new Date(millis); }

    @Override
    public Object getInternalValue() { return millis; }  // For numeric operations
}
```

**Benefits:**
- Thread-safe formatting (DateTimeFormatter)
- Modern API (java.time vs Calendar)
- Consistent internal representation

---

## Numeric Conversion Pattern

"Unwrap first, then switch on raw types":

```java
static Number objectToNumber(Object o) {
    if (o instanceof JavaMirror jm) {
        o = jm.getInternalValue();  // Unwrap first
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

---

## Usage Examples

### Basic Engine Usage

```java
Engine engine = new Engine();
Object result = engine.eval("1 + 2");
// result = 3
```

### Java Interop

```java
Map<String, Object> context = new HashMap<>();
context.put("greeting", "Hello");

Engine engine = new Engine();
engine.putAll(context);
Object result = engine.eval("greeting + ' World'");
// result = "Hello World"
```

### Date Handling

```java
engine.put("javaDate", new java.util.Date(1609459200000L));
assertEquals(1609459200000L, engine.eval("javaDate.getTime()"));
assertEquals(2021, engine.eval("javaDate.getFullYear()"));
```

---

## SimpleObject Pattern

`SimpleObject` is an interface for exposing Java objects to JavaScript with custom property access. It extends `ObjectLike` and provides default implementations.

### Required Methods

| Method | Purpose |
|--------|---------|
| `jsGet(String name)` | Property accessor - implement via switch expression |
| `keys()` | Return property names for serialization (override required) |

### How It Works

```java
public class ProcessHandle implements SimpleObject {

    // List of exposed properties - required for toMap()/toString
    private static final List<String> KEYS = List.of(
        "stdOut", "stdErr", "exitCode", "alive", "pid",
        "waitSync", "close", "signal"
    );

    @Override
    public Collection<String> keys() {
        return KEYS;  // Enables enumeration and JSON serialization
    }

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "stdOut" -> getStdOut();
            case "exitCode" -> getExitCode();
            case "waitSync" -> (JsCallable) (ctx, args) -> waitSync();
            // ... other properties
            default -> null;
        };
    }
}
```

### Key Behaviors

1. **`keys()` enables serialization** - `toMap()` iterates over `keys()` and calls `jsGet()` for each:
   ```java
   default Map<String, Object> toMap() {
       return toMap(keys(), this);  // Uses keys() to enumerate
   }
   ```

2. **Custom `toString` support** - If the object has a `toString` property returning `JsCallable`, it's used:
   ```java
   default JsCallable jsToString() {
       Object temp = jsGet("toString");
       if (temp instanceof JsCallable jsc) {
           return jsc;  // Use custom toString
       }
       return (context, args) -> toString(toMap());  // Fallback to JSON
   }
   ```

3. **`jsGet()` handles property access** - The switch expression is efficient and type-safe. Return `JsCallable` for methods.

### Why Both `keys()` and `jsGet()`?

- **`jsGet()`** - Handles individual property access (e.g., `proc.stdOut`)
- **`keys()`** - Enables enumeration for `toMap()`, JSON serialization, and `Object.keys()` in JS

Without `keys()`, the object works for property access but serializes to `{}`.

---

## Lazy Variables with Supplier

The engine supports lazy/computed variables via `java.util.function.Supplier`. When a variable's value is a `Supplier`, it is automatically invoked when accessed:

```java
// In CoreContext.get()
if (result instanceof Supplier<?> supplier) {
    return supplier.get();
}
```

### Usage

```java
Engine engine = new Engine();

// Static value - evaluated once at put time
engine.put("staticValue", someObject.getValue());

// Lazy value - evaluated each time it's accessed
engine.put("lazyValue", (Supplier<String>) () -> someObject.getValue());
```

### Use Cases

1. **Deferred computation** - Value is computed only when accessed
2. **Dynamic values** - Value can change between accesses
3. **Reduced per-call overhead** - Set up once, resolve on demand

### Example: Mock Server Request Variables

The mock server uses this pattern to avoid setting request variables on every HTTP request:

```java
// Set up once during initialization
engine.put("requestPath", (Supplier<String>) () ->
    currentRequest != null ? currentRequest.getPath() : null);
engine.put("requestMethod", (Supplier<String>) () ->
    currentRequest != null ? currentRequest.getMethod() : null);

// Per request, only update the reference
this.currentRequest = incomingRequest;

// When script accesses requestPath, Supplier.get() is called automatically
// * def path = requestPath  →  invokes the Supplier
```

This reduces per-request `engine.put()` calls from many to just one field assignment.

---

## File References

| Purpose | File |
|---------|------|
| Engine | `karate-js/src/main/java/io/karatelabs/js/Engine.java` |
| SimpleObject | `karate-js/src/main/java/io/karatelabs/js/SimpleObject.java` |
| JavaMirror | `karate-js/src/main/java/io/karatelabs/js/JavaMirror.java` |
| JsPrimitive | `karate-js/src/main/java/io/karatelabs/js/JsPrimitive.java` |
| Terms | `karate-js/src/main/java/io/karatelabs/js/Terms.java` |
| JsDate | `karate-js/src/main/java/io/karatelabs/js/JsDate.java` |
| CallInfo | `karate-js/src/main/java/io/karatelabs/js/CallInfo.java` |
| Tests | `karate-js/src/test/java/io/karatelabs/js/` |
