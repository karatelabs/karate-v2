# karate-demo v1 to v2 Migration Status

## Context
- **karate-demo**: `/Users/peter/dev/zcode/karate/karate-demo` (branch: `v2_diff`)
- **karate-v2**: `/Users/peter/dev/zcode/karate-v2` (branch: `main`)
- Goal: Create a minimal diff showing migration from v1.5.2 to v2

## Completed

### 1. pom.xml Changes
- Removed parent POM, made standalone
- Changed `karate-junit5` to `karate-core` v2.0.0.RC1
- Added explicit JUnit 5 dependency
- Java 21 requirement
- Spring Boot 2 → 3 (javax → jakarta)

### 2. Package Rename
All imports updated: `com.intuit.karate.*` → `io.karatelabs.core.*`

### 3. API Changes Applied
| v1 | v2 |
|----|-----|
| `Results` | `SuiteResult` |
| `results.getFailCount()` | `result.getScenarioFailedCount()` |
| `results.getErrorMessages()` | `String.join("\n", result.getErrors())` |
| `results.getReportDir()` | `"target/karate-reports"` (hardcoded) |
| `MockServer.arg("key", val).http(0).build()` | `MockServer.arg(Map.of("key", val)).start()` |
| `MockServer.https(0).build()` | `MockServer.ssl(true).start()` |
| `server.stop()` | `server.stopAndWait()` |
| `certFile(File)` / `keyFile(File)` | `certPath(String)` / `keyPath(String)` |

### 4. Deleted Files
- `DemoRunner.java` (@Karate.Test not supported)
- `DemoLogModifier.java` (HttpLogModifier v1 only)
- `WebSocketClientRunner.java` (v1 Java API)
- `driver/**/*.java` (v1 driver Java APIs)

### 5. Lexer Fix (karate-v2)
Fixed docstring parser failing when closing `"""` has trailing spaces.

### 6. V1 Compatibility Fixes (karate-v2)
- **Match header expression**: `match header Content-Type contains '...'` now works
  - Hyphenated identifiers supported in match expressions
  - Case-insensitive header lookup
- **Cookie map value**: `cookie foo = { value: 'bar', domain: '.abc.com' }` extracts value correctly
- **Multipart fields**: `multipart fields { json: { value: {...} } }` merges map values instead of nesting
- **Java interop static getters**: `Base64.encoder` works (calls `getEncoder()`)
- **Null params**: `params { name: 'foo', country: null }` skips null values instead of NPE
- **JsonPath deep-scan**: `response..username` deep-scan syntax now works in match expressions
- **Multipart files map**: `multipart files { key1: {...}, key2: {...} }` map syntax works (keys become part names)
- **Scenario outline empty cells**: Empty cells in Examples table with type hints (e.g., `active!`) now become `null`
- **Configure afterFeature**: `configure afterFeature = function() {...}` now supported
- **Configure afterScenarioOutline**: `configure afterScenarioOutline = function() {...}` now supported
- **Binary file handling**: `read('file.pdf')` returns `byte[]` for binary files; `response` for binary content-type also returns `byte[]`
- **Configure cookies JS function**: `configure cookies = read('cookies.js')` now works with JS functions (was only supporting Maps)
- **Cookie auto-send**: `responseCookies` from previous requests are now automatically sent on subsequent requests within the same scenario

## Pending

### 1. Spring Boot 3 Fixes (karate-demo, not committed)
The following fixes have been applied locally but NOT committed (to preserve clean diff for study):

**pom.xml**:
- Add `maven-compiler-plugin` with `<parameters>true</parameters>` - required for Spring Boot 3 to resolve `@PathVariable`/`@RequestParam` names at runtime

**SearchController.java**:
- Strip leading dot from cookie domain for RFC 6265 compliance:
  ```java
  String normalizedDomain = domain.startsWith(".") ? domain.substring(1) : domain;
  cookie.setDomain(normalizedDomain);
  ```

**cookies.feature**:
- Update test expectations: `domain: '.abc.com'` → `domain: 'abc.com'`

### 2. Remaining Test Failures
Current status after fixes: **173/201 scenarios passing (86%)**
(Improved from 163/201 after cookie auto-send and configure cookies JS function fixes)

Remaining issues (28 scenarios):
- Some upload tests - minor issues
- callonce/calltable tests - possible `callSingle` issues
- Parser edge cases (e.g., `call fun { first: 'dummy', ...}` syntax)

### 3. API Improvements to Consider
Make migration easier by adding backwards-compat methods:
- `SuiteResult.getFailCount()` → alias for `getScenarioFailedCount()`
- `SuiteResult.getErrorMessages()` → returns joined string
- `MockServer.arg(String, Object)` → single key-value overload
- `MockServer.http(port).build()` → alias for `port(port).start()`

### 3. Documentation
- Update migration guide with final API
- Document Spring Boot 3 requirements

## Next Session Instructions

1. Run `git diff` in karate-demo to see all changes:
   ```bash
   cd /Users/peter/dev/zcode/karate/karate-demo
   git diff main
   ```

2. Run tests to see current failures:
   ```bash
   mvn test -Dtest=DemoTestParallel
   ```

3. Consider adding backwards-compat methods to `SuiteResult` and `MockServer` in karate-v2 to simplify migration.
