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

// Internal interface - base for all callable objects
interface JsCallable {
    Object call(Context context, Object... args);
    default boolean isExternal() { return false; }  // JS-native by default
}

// Public interface for Java code to implement callables
public interface JavaCallable extends JsCallable {
    @Override
    default boolean isExternal() { return true; }  // External Java code
}

// Convenience interface that ignores context
public interface JavaInvokable extends JavaCallable {
    Object invoke(Object... args);

    default Object call(Context context, Object... args) {
        return invoke(args);
    }
}
```

**The `isExternal()` pattern:** Determines whether arguments should be converted at the JS/Java boundary:
- `true` (default for `JavaCallable`): External Java code - convert `undefined`→`null`, `JsDate`→`Date`
- `false` (default for `JsCallable`): Internal JS functions - preserve JS semantics

`JsFunction` implements `JavaCallable` (for sharing functions with Java code) but overrides `isExternal()` to `false` to preserve `undefined` semantics internally.

**Boundary conversion:** When `callable.isExternal()` is true, arguments are converted:
- `undefined` → `null`
- `JsDate` → `java.util.Date`
- Other `JavaMirror` types → unwrapped via `getJavaValue()`

### Type Mapping

| JS Type | Java Wrapper | `getJavaValue()` | Implements |
|---------|--------------|------------------|------------|
| Number | JsNumber | Number | JsPrimitive |
| String | JsString | String | JsPrimitive |
| Boolean | JsBoolean | Boolean | JsPrimitive |
| Date | JsDate | Date | JavaMirror |
| RegExp | JsRegex | Pattern | JavaMirror |
| Array | JsArray | List | **List\<Object\>** |
| Object | JsObject | Map | **Map\<String, Object\>** |
| Uint8Array | JsUint8Array | byte[] | JavaMirror |

### Boxed Primitives

JS constructors behave differently with vs without `new`:

```javascript
Number(5)      // → primitive 5
new Number(5)  // → boxed Number object

String("x")    // → primitive "x"
new String("x") // → boxed String object

Date()         // → string of current time (ES6: ignores arguments)
new Date()     // → Date object
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

## JsArray and JsObject as List and Map

### Design Goals

1. **ES6 within JS** - JS code sees native values (`undefined`, prototype methods, etc.)
2. **Seamless Java interop** - `JsArray` implements `List`, `JsObject` implements `Map`
3. **Lazy auto-unwrap** - Java interface methods convert on access, not construction
4. **No eager conversion** - Eliminates `toList()`/`toMap()` overhead

### Dual Access Pattern

Collections have two access modes:

| Access Mode | Method | Returns | Use Case |
|-------------|--------|---------|----------|
| **Java interface** | `List.get(int)` / `Map.get(Object)` | Unwrapped (null, Date) | Java consumers |
| **JS internal** | `getElement(int)` / `getMember(String)` | Raw (undefined, JsDate) | JS engine internals |

```java
// JsArray uses composition (has-a JsObject) to avoid method signature conflicts
class JsArray implements List<Object>, ObjectLike, JsCallable {
    final List<Object> list;           // Internal storage
    private final JsObject delegate;   // For named properties (arr.foo = "bar")

    // JS internal - raw values, ES6 semantics
    public Object getElement(int index) {
        if (index < 0 || index >= list.size()) {
            return Terms.UNDEFINED;  // Out of bounds returns undefined
        }
        return list.get(index);  // Returns Terms.UNDEFINED, JsDate, etc.
    }

    // Java interface - auto-unwrap for Java consumers
    @Override
    public Object get(int index) {
        return Engine.toJava(list.get(index));  // undefined→null, JsDate→Date
    }
}

// JsObject implements Map<String, Object>
class JsObject implements Map<String, Object>, ObjectLike {

    // JS internal - raw values, prototype chain
    public Object getMember(String name) {
        // Own property first, then prototype chain
        if (_map != null && _map.containsKey(name)) {
            return _map.get(name);
        }
        return getPrototype().getMember(name);
    }

    // Java interface - auto-unwrap, own properties only
    @Override
    public Object get(Object key) {
        Object raw = _map != null ? _map.get(key.toString()) : null;
        return Engine.toJava(raw);  // undefined→null, JsDate→Date
    }
}
```

### ObjectLike Method Naming

To avoid collision with `Map.get(Object)`, ObjectLike uses distinct method names:

| Old Name | New Name | Purpose |
|----------|----------|---------|
| `get(String)` | `getMember(String)` | JS property access with prototype chain |
| `put(String, Object)` | `putMember(String, Object)` | JS property assignment |
| `remove(String)` | `removeMember(String)` | JS property deletion |

### Conversion at Boundaries

Conversion happens at specific boundaries:

1. **`Engine.eval()` return** - Top-level value converted via `toJava()`
2. **`List.get()` / `Map.get()`** - Elements unwrapped lazily on access
3. **JavaCallable args** - Arguments converted before external Java method call
4. **Iteration** - Iterator unwraps values lazily

### Example: Dual Access

```java
Engine engine = new Engine();
Object result = engine.eval("[1, undefined, new Date(0)]");

// As List - Java consumer gets unwrapped values
List<Object> list = (List<Object>) result;
list.get(0);  // 1
list.get(1);  // null (undefined unwrapped)
list.get(2);  // java.util.Date

// As JsArray - JS internal gets raw values
JsArray jsArray = (JsArray) result;
jsArray.getElement(0);  // 1
jsArray.getElement(1);  // Terms.UNDEFINED (raw)
jsArray.getElement(2);  // JsDate (raw)
```

### Why Lazy Unwrap?

1. **Performance** - No upfront traversal of nested structures
2. **Memory** - No duplicate converted collections
3. **Semantics** - JS code sees raw values, Java sees converted values
4. **Simplicity** - Single conversion point in `Engine.toJava()`

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
class JsDate extends JsObject implements JavaMirror {
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
| `jsKeys()` | Return property names for serialization (override required) |

### How It Works

```java
public class ProcessHandle implements SimpleObject {

    // List of exposed properties - required for toMap()/toString
    private static final List<String> KEYS = List.of(
        "stdOut", "stdErr", "exitCode", "alive", "pid",
        "waitSync", "close", "signal"
    );

    @Override
    public Collection<String> jsKeys() {
        return KEYS;  // Enables enumeration and JSON serialization
    }

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "stdOut" -> getStdOut();
            case "exitCode" -> getExitCode();
            case "waitSync" -> (JavaCallable) (ctx, args) -> waitSync();
            // ... other properties
            default -> null;
        };
    }
}
```

### Key Behaviors

1. **`jsKeys()` enables serialization** - `toMap()` iterates over `jsKeys()` and calls `jsGet()` for each:
   ```java
   default Map<String, Object> toMap() {
       return toMap(jsKeys(), this);  // Uses jsKeys() to enumerate
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

3. **`jsGet()` handles property access** - The switch expression is efficient and type-safe. Return `JavaCallable` or `JavaInvokable` for methods.

4. **`jsGet()` is inherently lazy** - Called on every property access, so values are computed fresh each time. No need for `Supplier` pattern.

### Why Both `jsKeys()` and `jsGet()`?

- **`jsGet()`** - Handles individual property access (e.g., `proc.stdOut`)
- **`jsKeys()`** - Enables enumeration for `toMap()`, JSON serialization, and `Object.keys()` in JS

Without `jsKeys()`, the object works for property access but serializes to `{}`.

### Property Presence Detection

For `JsObject`, property presence is detected using `Map.containsKey()` before calling `getMember()`. This allows distinguishing between:
- Property exists with value `null` → returns `null`
- Property doesn't exist → continues up the prototype chain

For `SimpleObject`, there is no `hasMember()` API. When `jsGet()` returns `null`, it's treated as "property not found". This simplifies implementation for Java interop classes that don't need to declare all keys upfront - they only need `jsKeys()` for serialization and `jsGet()` for access. If a property genuinely needs to hold `null`, consider using a sentinel value or implementing the full `JsObject` interface instead.

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

## Hidden Root Bindings

`putRootBinding()` creates variables that are accessible in scripts but hidden from `getBindings()`:

```java
Engine engine = new Engine();
engine.putRootBinding("magic", "secret");
engine.put("normal", "visible");

engine.eval("magic");              // "secret" - accessible
engine.eval("normal");             // "visible" - accessible

engine.getBindings().containsKey("magic");   // false - hidden!
engine.getBindings().containsKey("normal");  // true - visible
```

### Use Cases

1. **Internal/system variables** - Variables scripts can use but shouldn't enumerate
2. **Fallback values** - Suite-level resources that feature scripts can access
3. **Magic variables** - Built-in helpers that shouldn't pollute user namespace

### With Lazy Evaluation

Root bindings also support `Supplier` for lazy/dynamic values:

```java
String[] suiteDriver = { null };

engine.putRootBinding("driver", (Supplier<String>) () -> suiteDriver[0]);

engine.eval("driver");  // null initially
suiteDriver[0] = "suite-driver";
engine.eval("driver");  // "suite-driver" - lazily resolved

engine.getBindings().containsKey("driver");  // false - still hidden
```

---

## Variable Scoping and Isolation

The engine provides multiple patterns for controlling variable scope across script executions.

### The Problem: `const`/`let` Redeclaration

When reusing an engine across multiple `eval()` calls, `const` and `let` declarations persist:

```java
Engine engine = new Engine();
engine.eval("const a = 1");
engine.eval("const a = 2");  // ERROR: identifier 'a' has already been declared
```

This matches ES6 behavior where top-level `const`/`let` cannot be redeclared in the same scope.

### Solution 1: `evalWith()` for Complete Isolation

`evalWith()` creates a fully isolated scope. Variables declared inside don't leak out:

```java
Engine engine = new Engine();
engine.put("shared", new HashMap<>());

Map<String, Object> vars1 = new HashMap<>();
engine.evalWith("const a = 1; shared.x = a;", vars1);
// vars1.get("a") = 1

Map<String, Object> vars2 = new HashMap<>();
engine.evalWith("const a = 2; shared.y = a;", vars2);  // No conflict!
// vars2.get("a") = 2

// Engine bindings unaffected
engine.getBindings().containsKey("a");  // false
```

**Key behaviors of `evalWith()`:**
- `const`/`let`/`var` declarations stay in the vars map
- Implicit globals (`foo = 42`) also stay in the vars map (don't leak)
- Can read engine bindings (e.g., `shared` above)
- Can mutate objects in engine bindings

### Solution 2: IIFE Wrapping for Partial Isolation

Wrap scripts in an Immediately Invoked Function Expression (IIFE) to isolate `const`/`let` while allowing implicit globals to persist:

```java
Engine engine = new Engine();
engine.put("shared", new HashMap<>());

// Wrap script in IIFE
engine.eval("(function(){ const json = {a: 1}; shared.first = json.a; })()");
engine.eval("(function(){ const json = {b: 2}; shared.second = json.b; })()");  // No conflict!

// Implicit globals persist to engine scope
engine.eval("(function(){ persistedVar = 42; })()");
engine.get("persistedVar");  // 42
```

This pattern is used by Postman's sandbox for script execution.

### Comparison Table

| Behavior | `eval()` | `evalWith()` | IIFE via `eval()` |
|----------|----------|--------------|-------------------|
| `const`/`let` isolation | No (persists) | Yes (in vars map) | Yes (function-scoped) |
| `var` isolation | No (persists) | Yes (in vars map) | Yes (function-scoped) |
| Implicit globals | Persists to engine | Isolated (in vars map) | **Persists to engine** |
| Access engine bindings | Yes | Yes | Yes |
| Mutate shared objects | Yes | Yes | Yes |

### Implicit Global Assignment (ES6 Non-Strict)

Assigning to an undeclared variable creates a global (ES6 non-strict mode behavior):

```java
Engine engine = new Engine();
engine.eval("function foo() { implicitGlobal = 42; }");
engine.eval("foo()");
engine.get("implicitGlobal");  // 42 - created at global scope
```

This also works inside IIFEs, making them useful for script runners that need `const`/`let` isolation while allowing intentional global state sharing.

### Use Case: Script Runner (e.g., Postman-like)

For running multiple user scripts that may declare same-named variables:

```java
public void runScript(String script) {
    // Wrap in IIFE to isolate const/let but allow global mutations
    engine.eval("(function(){" + script + "})()");
}

// User scripts can use const/let freely
runScript("const json = response.json(); pm.test('ok', () => {});");
runScript("const json = response.json(); pm.test('ok', () => {});");  // No conflict!
```

---

## File References

| Purpose | File |
|---------|------|
| Engine | `karate-js/src/main/java/io/karatelabs/js/Engine.java` |
| CoreContext | `karate-js/src/main/java/io/karatelabs/js/CoreContext.java` |
| SimpleObject | `karate-js/src/main/java/io/karatelabs/js/SimpleObject.java` |
| JavaMirror | `karate-js/src/main/java/io/karatelabs/js/JavaMirror.java` |
| JsPrimitive | `karate-js/src/main/java/io/karatelabs/js/JsPrimitive.java` |
| JsCallable | `karate-js/src/main/java/io/karatelabs/js/JsCallable.java` |
| JavaCallable | `karate-js/src/main/java/io/karatelabs/js/JavaCallable.java` |
| Terms | `karate-js/src/main/java/io/karatelabs/js/Terms.java` |
| JsDate | `karate-js/src/main/java/io/karatelabs/js/JsDate.java` |
| CallInfo | `karate-js/src/main/java/io/karatelabs/js/CallInfo.java` |
| Parser infrastructure | `karate-js/src/main/java/io/karatelabs/parser/` |
| Gherkin parser | `karate-core/src/main/java/io/karatelabs/gherkin/` |
| Tests | `karate-js/src/test/java/io/karatelabs/js/` |

---

## Future Improvements (Swift Engine Comparison)

This section documents potential improvements identified by comparing the Java engine with a Swift-based JavaScript engine implementation. The Swift engine is smaller (~8 files vs 50+) because it implements fewer features (no prototype chain, no regex, simpler scoping). The Java engine's complexity is justified by its requirements: full ES6 scoping, prototype chain, Java interop, IDE tooling support, and event/debugging system.

**Overall Assessment:** The Java engine is reasonably well-designed given its feature requirements. The areas below represent opportunities for modernization and cleanup rather than fundamental architectural issues.

### 1. NodeType-based Evaluation Dispatch

**Current:** The `Interpreter.java` has a 1000+ line `eval()` method with a massive switch statement dispatching to 35+ separate eval methods.

**Swift Pattern:** Uses pattern matching on AST node enum cases, which is more compact.

**Proposed:** Move evaluation logic into the `NodeType` enum itself using Java 21+ features:

```java
public enum NodeType {
    IF_STMT {
        @Override
        public Object evaluate(Node node, CoreContext ctx) {
            // If statement evaluation logic
        }
    },
    FOR_STMT {
        @Override
        public Object evaluate(Node node, CoreContext ctx) {
            // For statement evaluation logic
        }
    },
    // ...other node types
    ;

    public abstract Object evaluate(Node node, CoreContext ctx);
}
```

**Benefits:**
- Each node type owns its evaluation logic
- Compiler enforces exhaustive handling when adding new node types
- Cleaner code organization
- Preserves AST node reusability (no methods added to `Node` class)

**Trade-offs:**
- Large enum file, but organized by node type
- May need helper methods in a companion class for shared logic

---

### 2. Sealed Interface for Value Types (Java 21+)

**Current:** Value types use `instanceof` chains scattered throughout `Terms.java` and other classes. The compiler cannot verify exhaustive handling.

**Swift Pattern:** Uses `enum JsValue` with exhaustive switch expressions - the compiler ensures all cases are handled.

**Proposed:** Introduce a sealed interface hierarchy for core JS values:

```java
public sealed interface JsValue permits
    JsUndefined, JsNull, JsBoolean, JsNumber, JsString,
    JsArray, JsObject, JsFunction, JsNativeFunction {

    // Common operations
    boolean isTruthy();
    String typeOf();
    Object toJava();
}

// Pattern matching in Terms.java becomes exhaustive:
static Number objectToNumber(JsValue value) {
    return switch (value) {
        case JsUndefined _ -> Double.NaN;
        case JsNull _ -> 0;
        case JsBoolean(var b) -> b ? 1 : 0;
        case JsNumber(var n) -> n;
        case JsString(var s) -> parseNumber(s);
        case JsArray _, JsObject _, JsFunction _, JsNativeFunction _ -> Double.NaN;
    };
}
```

**Benefits:**
- Compiler-enforced exhaustive handling
- Cleaner type coercion in `Terms.java`
- Safer refactoring (adding new value types forces updates)
- Better IDE support for "find usages" of value types

**Trade-offs:**
- Significant refactor touching many files
- Need to handle Java interop values that aren't JsValue (raw Maps, Lists, etc.)
- May need adapter layer at boundaries

---

### 3. PropertyAccessor Strategy Pattern

**Current:** `JsProperty.java` is 330+ lines handling property access with many branches for: optional chaining (`?.`), different object types (JsObject, Map, List, JavaObject), numeric vs string indexing, and Java interop.

**Swift Pattern:** Property access is simpler because it handles fewer object types.

**Proposed:** Extract into a strategy pattern with typed accessors:

```java
public interface PropertyAccessor {
    Object get(Object target, String key, boolean optional);
    void set(Object target, String key, Object value);
    boolean has(Object target, String key);
}

// Implementations
class JsObjectAccessor implements PropertyAccessor { ... }
class MapAccessor implements PropertyAccessor { ... }
class ListAccessor implements PropertyAccessor { ... }
class JavaBeanAccessor implements PropertyAccessor { ... }

// Dispatch once at the start
PropertyAccessor accessor = PropertyAccessor.forTarget(target);
return accessor.get(target, key, isOptional);
```

**Benefits:**
- Cleaner separation of concerns
- Each accessor handles one object type
- Easier to add new object types
- Testable in isolation

**Trade-offs:**
- Slight dispatch overhead (mitigated by inlining hot paths)
- More classes to maintain

**Status:** TODO - good cleanup opportunity for future work.

---

### 4. JavaScript Stack Traces for Errors

**Current:** Error messages are basic - no JavaScript call stack capture.

**Swift Pattern:** Also has basic errors (no stack traces).

**Proposed:** Capture and report a JavaScript call stack when exceptions occur:

```java
public class JsError extends JsObject {
    private List<StackFrame> jsStack;

    public record StackFrame(String functionName, String file, int line, int column) {}

    public void captureStack(CoreContext context) {
        this.jsStack = context.captureCallStack();
    }

    public String getStackTrace() {
        // Format like:
        // Error: Something went wrong
        //     at myFunction (script.js:10:5)
        //     at onClick (script.js:25:3)
        //     at <anonymous> (script.js:1:1)
    }
}
```

**Implementation requirements:**
- Track function entry/exit in `Interpreter.evalFnCall()`
- Store function name, source location in a stack structure on `CoreContext`
- Capture stack snapshot when `JsError` is created or thrown
- Format stack trace matching JavaScript conventions

**Benefits:**
- Much better debugging experience for Karate users
- Easier to diagnose script errors
- Matches standard JavaScript error behavior

---

### 5. Async/Await Architecture Extensibility

**Current:** The engine is purely synchronous. This is sufficient because async operations are handled at the Karate DSL level.

**Future requirement:** Async/await support is planned.

**Proposed:** Keep current architecture but note these extension points:

1. **EvalResult pattern** - If adopting later, would enable natural async propagation
2. **Context-based execution** - Could track pending promises per context
3. **Event loop abstraction** - Would need to be pluggable (different for CLI vs server)

**No immediate action required** - current design doesn't preclude async support. The context-based stateful return approach can be extended to track promise state.

---

### Summary

| Area | Priority | Effort | Impact |
|------|----------|--------|--------|
| NodeType-based Evaluation | Medium | Medium | Code organization |
| Sealed Interface for Values | Low | High | Type safety, refactoring confidence |
| PropertyAccessor Strategy | Low | Medium | Code cleanliness |
| Stack Traces for Errors | High | Medium | User experience |
| Async Architecture | Future | N/A | Extensibility |

The Java engine is well-suited to its requirements. The Swift engine's simplicity comes from implementing fewer features. These improvements would modernize the codebase using Java 21+ features without changing fundamental behavior.
