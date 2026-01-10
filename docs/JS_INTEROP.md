# JavaScript/Java Interop Design

This document describes the design for making `JsArray` and `JsObject` implement Java's `List` and `Map` interfaces respectively, enabling seamless interop with Java code.

## Design Goals

1. **ES6 within JS** - JS code sees native values (`undefined`, prototype chain, etc.)
2. **Seamless Java interop** - `JsArray implements List<Object>`, `JsObject implements Map<String, Object>`
3. **Lazy auto-unwrap** - Java interface methods (`List.get()`, `Map.get()`) convert on access
4. **Dual access pattern** - JS internal uses raw access, Java uses unwrapped access
5. **No eager conversion** - Eliminates `toList()`/`toMap()` overhead

## Key Design Decision: Lazy Auto-Unwrap

When Java code accesses elements via `List.get(int)` or `Map.get(Object)`, values are automatically unwrapped:
- `Terms.UNDEFINED` → `null`
- `JsDate` → `java.util.Date`
- Other `JavaMirror` types → their `getJavaValue()`

JS internal code uses `getElement(int)` / `getMember(String)` to get raw values.

```java
Engine engine = new Engine();
Object result = engine.eval("[1, undefined, new Date(0)]");

// As List - Java consumer gets unwrapped values
List<Object> list = (List<Object>) result;
list.get(1);  // null (undefined unwrapped)
list.get(2);  // java.util.Date

// As JsArray - JS internal gets raw values
JsArray jsArray = (JsArray) result;
jsArray.getElement(1);  // Terms.UNDEFINED (raw)
jsArray.getElement(2);  // JsDate (raw)
```

## Current State (Before Implementation)

### Issue 1: Interpreter Returns Raw Java Collections

Currently `Interpreter.evalLitArray()` creates `ArrayList` and `evalLitObject()` creates `LinkedHashMap`:

```java
// Interpreter.java line ~402
case LIT_ARRAY -> {
    List<Object> list = new ArrayList<>();
    // ... populates list
    return list;  // Returns ArrayList, not JsArray!
}

// Interpreter.java line ~454
case LIT_OBJECT -> {
    Map<String, Object> map = new LinkedHashMap<>();
    // ... populates map
    return map;  // Returns LinkedHashMap, not JsObject!
}
```

**Fix:** Return `JsArray` and `JsObject` instead.

### Issue 2: ObjectLike.get() Conflicts with Map.get()

Current `ObjectLike` interface:
```java
public interface ObjectLike {
    Object get(String name);      // Conflicts with Map.get(Object)
    void put(String name, Object value);
    void remove(String name);
    Map<String, Object> toMap();
}
```

`ObjectLike.get(String)` has different semantics than `Map.get(Object)`:
- `ObjectLike.get()` traverses prototype chain, returns raw values
- `Map.get()` should return own properties only, auto-unwrapped

**Fix:** Rename to `getMember()`, `putMember()`, `removeMember()`.

### Issue 3: JsProperty Check Order

In `JsProperty.java`, the order of `instanceof` checks matters:

```java
// Current (problematic if JsObject implements Map):
if (object instanceof Map) {           // Would match JsObject!
    new JsObject(map).get(name);       // Creates NEW JsObject, loses prototype
}
```

**Fix:** Check `JsArray`/`JsObject` BEFORE `List`/`Map`.

### Issue 4: Arrow Function Bug (FIXED)

Single-param arrow functions like `x => x` were incorrectly converting `undefined` args to `null` due to body being evaluated as default value expression in `JsFunctionNode`.

**Fix:** Added check `argNode.type == NodeType.FN_DECL_ARG` before evaluating default values in `JsFunctionNode.java`. See `JsFunctionTest.testArrowFunctionUndefinedArg()` and `testArrowFunctionPassThroughUndefined()` for test coverage.

## Method Naming Convention

| Class | JS Internal (raw) | Java Interface (unwrapped) |
|-------|-------------------|---------------------------|
| JsObject | `getMember(String)` | `Map.get(Object)` |
| JsObject | `putMember(String, Object)` | `Map.put(String, Object)` |
| JsObject | `removeMember(String)` | `Map.remove(Object)` |
| JsArray | `getElement(int)` | `List.get(int)` |

## Implementation Plan

### Phase 1: Rename ObjectLike Methods

Rename to avoid conflict with Map interface:

| Old | New |
|-----|-----|
| `get(String)` | `getMember(String)` |
| `put(String, Object)` | `putMember(String, Object)` |
| `remove(String)` | `removeMember(String)` |

**Files to update:**
- `ObjectLike.java` - interface definition
- `JsObject.java` - implementation
- `JsArray.java` - inherits from JsObject
- `JsString.java` - implements ObjectLike
- `JsUint8Array.java` - extends JsObject
- `JavaObject.java` - implements ObjectLike
- `Prototype.java` - uses ObjectLike
- `JsProperty.java` - calls get/put/remove
- `Interpreter.java` - calls get/put
- `Terms.java` - uses ObjectLike
- `SimpleObject.java` - extends ObjectLike

### Phase 2: Implement Map Interface on JsObject

```java
class JsObject implements ObjectLike, Map<String, Object>, JsCallable, Iterable<KeyValue> {

    // JS internal - raw values, prototype chain
    @Override
    public Object getMember(String name) {
        if (_map != null && _map.containsKey(name)) {
            return _map.get(name);
        }
        return getPrototype().getMember(name);  // Prototype chain
    }

    // Map interface - auto-unwrap, own properties only
    @Override
    public Object get(Object key) {
        Object raw = _map != null ? _map.get(key.toString()) : null;
        return Engine.toJava(raw);  // undefined→null, JsDate→Date
    }

    @Override
    public Object put(String key, Object value) {
        // Standard Map.put, returns previous value (unwrapped)
    }

    // ... other Map methods delegate to _map with unwrapping
}
```

### Phase 3: Implement List Interface on JsArray

```java
class JsArray extends JsObject implements List<Object> {

    // JS internal - raw values
    public Object getElement(int index) {
        if (index < 0 || index >= list.size()) {
            return Terms.UNDEFINED;  // JS semantics
        }
        return list.get(index);  // Raw value
    }

    // List interface - auto-unwrap for Java consumers
    @Override
    public Object get(int index) {
        if (index < 0 || index >= list.size()) {
            throw new IndexOutOfBoundsException();  // List semantics
        }
        return Engine.toJava(list.get(index));  // Unwrapped
    }

    // ... other List methods delegate to internal list with unwrapping
}
```

### Phase 4: Update JsProperty Check Order

```java
// In JsProperty.get():
// Check specific types BEFORE generic interfaces
if (object instanceof JsArray array) {
    return array.getElement(i);  // Raw value, JS semantics
} else if (object instanceof List<?> list) {
    return list.get(i);   // Raw Java List (no unwrap needed)
}

if (object instanceof JsObject jsObject) {
    return jsObject.getMember(name);  // Prototype chain, raw
} else if (object instanceof Map) {
    return map.get(name);  // Raw Java Map
}
```

### Phase 5: Change Interpreter to Return JsArray/JsObject

```java
// In Interpreter.java
case LIT_ARRAY -> {
    JsArray array = new JsArray();
    // ... populate array
    return array;  // Return JsArray, not ArrayList
}

case LIT_OBJECT -> {
    JsObject object = new JsObject();
    // ... populate object
    return object;  // Return JsObject, not LinkedHashMap
}
```

### Phase 6: Remove Unnecessary Conversions

Eliminate `toList()`/`toMap()` calls since JsArray IS a List and JsObject IS a Map:

| Location | Old | New |
|----------|-----|-----|
| `JsArray.java` concat/flatMap/flat | `((JsArray) arg).toList()` | `(List) arg` |
| `Interpreter.java` spread | `array.toList()` | `array` |
| `Terms.java` TO_STRING | `objectLike.toMap()` | Cast to Map |

## Conversion Boundaries

Conversion happens at these specific points:

1. **`Engine.eval()` return** - Top-level value converted via `toJava()`
2. **`List.get()` / `Map.get()`** - Elements unwrapped lazily on access
3. **`Iterator`** - Values unwrapped during iteration
4. **SimpleObject/Invokable args** - Arguments converted before Java method call
5. **NOT within internal JS operations** - JS code sees raw values

## Test Coverage

See `JavaMirrorTest.java` for comprehensive tests:

### Dual Access Pattern Tests
- `testListGetUnwrapsUndefined` - List.get() returns null, getElement() returns UNDEFINED
- `testMapGetUnwrapsUndefined` - Map.get() returns null, getMember() returns UNDEFINED
- `testListGetUnwrapsJsDate` - List.get() returns Date, getElement() returns JsDate
- `testMapGetUnwrapsJsDate` - Map.get() returns Date, getMember() returns JsDate

### Boundary Conversion Tests
- `testUndefinedPassedToSimpleObjectMethodBecomesNull`
- `testJsDatePassedToSimpleObjectBecomesDate`
- `testTopLevelUndefinedBecomesNull`

### Array Method Tests
- `testArrayMapReturningUndefined` - explicit return undefined preserved
- `testArrayMapReturningNothing` - implicit return becomes null (known deviation)
- `testFunctionCallInArrayContext` - call/apply with undefined

### Wrapper Type Tests
- `testListWithJsWrapperTypes` - JsBoolean, JsNumber, JsString, JsDate in arrays
- `testMixedPrimitivesAndWrappers` - primitives vs wrapper objects

## Files to Modify

| File | Changes |
|------|---------|
| `ObjectLike.java` | Rename methods to getMember/putMember/removeMember |
| `JsObject.java` | Implement Map<String, Object>, add getMember/putMember/removeMember |
| `JsArray.java` | Implement List<Object>, add getElement(int) |
| `JsProperty.java` | Reorder instanceof checks, use getElement/getMember |
| `Interpreter.java` | Return JsArray/JsObject for literals, use getMember/putMember |
| `Terms.java` | Simplify toIterable/toObjectLike |
| `Engine.java` | toJava() already correct, no change needed |
| `Prototype.java` | Update to use getMember |
| `JsString.java` | Rename methods |
| `JsUint8Array.java` | Rename methods |
| `JavaObject.java` | Rename methods |
| `SimpleObject.java` | Rename methods |

## Known Issues / Deviations

1. **Implicit return becomes null** - Functions without explicit return produce `null` instead of `undefined` in results. This is a separate bug in function evaluation.

~~2. **Arrow function bug** - Single-param arrows `x => x` convert undefined to null incorrectly.~~ **FIXED** - see Issue 4 above.

## Quick Start for Resuming Implementation

1. Start with **Phase 1** - rename ObjectLike methods (`get` → `getMember`, etc.)
2. After each phase, run tests: `mvn test -pl karate-js`
3. **Important**: `JavaMirrorTest.java` has dual access pattern tests that are currently commented out (lines with `// PENDING IMPLEMENTATION`). Uncomment these as you complete each phase.
4. All 551 tests should pass after each phase

## Related Documentation

- `docs/JS_ENGINE.md` - Type system, JavaMirror, fromThis() pattern
- `karate-js/src/test/java/io/karatelabs/js/JavaMirrorTest.java` - Test cases (some tests commented out pending implementation)
