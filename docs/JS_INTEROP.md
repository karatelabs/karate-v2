# JavaScript/Java Interop Design

This document describes the design for making `JsArray` and `JsObject` implement Java's `List` and `Map` interfaces respectively, enabling seamless interop with Java code.

## Current Implementation Status

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | Rename ObjectLike methods | ✅ Done |
| Phase 2 | Implement Map on JsObject | ✅ Done |
| Phase 3 | Implement List on JsArray | ✅ Done (composition) |
| Phase 4 | Update JsProperty check order | ✅ Done |
| Phase 5 | Return JsArray/JsObject for literals | ⚠️ Partial |
| Phase 6 | Remove unnecessary conversions | ❌ Not done |

**Key Change**: JsArray uses **composition** instead of inheritance to avoid the `Map.remove(Object)` vs `List.remove(Object)` return type conflict. JsArray has an internal `JsObject delegate` for named properties.

## Design Goals

1. **ES6 within JS** - JS code sees native values (`undefined`, prototype chain, etc.)
2. **Seamless Java interop** - `JsArray implements List<Object>`, `JsObject implements Map<String, Object>`
3. **Lazy auto-unwrap** - Java interface methods (`List.get()`, `Map.get()`) convert on access
4. **Dual access pattern** - JS internal uses raw access, Java uses unwrapped access
5. **No eager conversion** - Eliminates `toList()`/`toMap()` overhead

## Method Naming Convention

| Class | JS Internal (raw) | Java Interface (unwrapped) |
|-------|-------------------|---------------------------|
| JsObject | `getMember(String)` | `Map.get(Object)` |
| JsObject | `putMember(String, Object)` | `Map.put(String, Object)` |
| JsObject | `removeMember(String)` | `Map.remove(Object)` |
| JsArray | `getElement(int)` | `List.get(int)` |

## JsArray Composition Design

JsArray uses composition instead of inheritance to implement both `List<Object>` and `ObjectLike`:

```java
class JsArray implements List<Object>, ObjectLike, JsCallable {
    final List<Object> list;           // Internal storage
    private final JsObject delegate;   // For named properties (arr.foo = "bar")
    final JsArray _this;               // Self reference for prototype methods
    private Prototype prototype;

    // JS internal - raw values, UNDEFINED for out of bounds
    public Object getElement(int index) {
        if (index < 0 || index >= list.size()) {
            return Terms.UNDEFINED;
        }
        return list.get(index);
    }

    // List interface - delegates to internal list
    @Override
    public Object get(int index) {
        return list.get(index);  // Raw values (no auto-unwrap currently)
    }

    // ObjectLike - check prototype, then delegate
    @Override
    public Object getMember(String name) {
        Map<String, Object> delegateMap = delegate.toMap();
        if (delegateMap.containsKey(name)) {
            return delegateMap.get(name);
        }
        if ("prototype".equals(name)) {
            return getPrototype();
        }
        return getPrototype().getMember(name);
    }

    // JS iteration
    public Iterable<KeyValue> jsEntries() { ... }
}
```

## Remaining Work

### Phase 5: Change Interpreter to Return JsObject for Object Literals

Currently `evalLitObject` returns `LinkedHashMap`:

```java
// Interpreter.java ~line 461
result = new LinkedHashMap<>(last - 1);
// ...
return result;  // Should return JsObject instead
```

**TODO**: Change to return `JsObject` (similar to how `evalLitArray` now returns `JsArray`).

### Phase 6: Remove Unnecessary Conversions

Eliminate `toList()`/`toMap()` calls since JsArray IS a List and JsObject IS a Map:

| Location | Old | New |
|----------|-----|-----|
| `JsArray.java` concat/flatMap/flat | `((JsArray) arg).toList()` | `(List) arg` |
| `Interpreter.java` spread | `array.toList()` | `array` |
| `Terms.java` TO_STRING | `objectLike.toMap()` | Cast to Map |

### Future Improvement: StringUtils.formatJson

Now that JsArray/JsObject can contain raw JS values and get passed to `StringUtils.formatJson`, it should handle JS-specific types:

```java
// In StringUtils.formatRecurse(), add before the else branch:
} else if (o == Terms.UNDEFINED) {
    sb.append("null");  // JSON has no undefined
} else if (o instanceof JavaMirror jm) {
    // Unwrap JS wrapper types (JsDate, JsNumber, JsString, JsBoolean)
    formatRecurse(jm.getJavaValue(), pretty, lenient, sort, indent, sb, depth);
} else if (o instanceof SimpleObject so) {
    ...
```

**TODO**: Add tests in `StringUtilsTest.java` or `JsJsonTest.java`:
```java
// Test undefined in array becomes null
assertThat(formatJson(List.of(1, Terms.UNDEFINED, 3))).isEqualTo("[1, null, 3]");

// Test JsDate gets unwrapped
JsDate jsDate = new JsDate(0);
assertThat(formatJson(List.of(jsDate))).contains("1970");  // or appropriate date format

// Test JsNumber/JsString/JsBoolean get unwrapped
assertThat(formatJson(List.of(new JsNumber(42)))).isEqualTo("[42]");
assertThat(formatJson(List.of(new JsString("hello")))).isEqualTo("[\"hello\"]");
```

## Important Check Order Issues

### JsJson.stringify - List before JsCallable

JsArray implements both `List` AND `JsCallable`. Must check List first:

```java
// CORRECT order in JsJson.stringify():
if (replacer instanceof List) {           // JsArray matches here
    // Handle as replacer array
} else if (replacer instanceof JsCallable) {  // Functions only
    // Handle as replacer function
}
```

### JsProperty.get - JsArray/JsObject before List/Map

```java
// Check specific types BEFORE generic interfaces
if (object instanceof JsArray array) {
    return array.getElement(i);  // Raw value, JS semantics
} else if (object instanceof List<?> list) {
    // Plain Java List
}

if (object instanceof ObjectLike ol) {
    Object result = ol.getMember(name);
    if (result != null && result != Terms.UNDEFINED) {
        return result;
    }
    // Return undefined for JsObject AND JsArray
    if (object instanceof JsObject || object instanceof JsArray) {
        return Terms.UNDEFINED;
    }
} else if (object instanceof Map) {
    // Plain Java Map
}
```

### Terms.toIterable - JsArray before List

```java
static Iterable<KeyValue> toIterable(Object o) {
    // Check JsArray first - it implements List but has its own jsEntries
    if (o instanceof JsArray jsArray) {
        return jsArray.jsEntries();
    }
    if (o instanceof JsObject jsObject) {
        return jsObject.jsEntries();
    }
    if (o instanceof List) {
        return new JsArray((List<Object>) o).jsEntries();
    }
    // ...
}
```

## Files Modified

| File | Changes |
|------|---------|
| `ObjectLike.java` | Renamed methods to getMember/putMember/removeMember |
| `JsObject.java` | Implements Map<String, Object>, jsEntries() method |
| `JsArray.java` | **Refactored**: composition instead of inheritance, implements List<Object> |
| `JsUint8Array.java` | Overrides List methods for byte[] backing store |
| `JsProperty.java` | Check order: JsArray before List, added JsArray to undefined check |
| `JsJson.java` | Check order: List before JsCallable for replacer |
| `Terms.java` | Check order in toIterable: JsArray before List; TO_STRING uses StringUtils |
| `Interpreter.java` | evalLitArray returns JsArray |
| `Prototype.java` | Updated to use getMember |
| `JsString.java` | Renamed methods, jsEntries() |
| `JavaObject.java` | Renamed methods |
| `SimpleObject.java` | Renamed methods |

## Test Coverage

All 551 tests pass. Key test files:
- `JavaMirrorTest.java` - Dual access pattern tests
- `JsArrayTest.java` - Array operations
- `JsJsonTest.java` - JSON.stringify with array/function replacers

## Quick Start for Resuming

1. **Phase 5**: Modify `Interpreter.evalLitObject()` to return `JsObject` instead of `LinkedHashMap`
2. **Phase 6**: Search for `.toList()` and `.toMap()` calls and eliminate where JsArray/JsObject can be used directly
3. **StringUtils**: Add handling for `Terms.UNDEFINED` and `JavaMirror` types in `formatRecurse()`
4. Run tests: `mvn test -pl karate-js`
