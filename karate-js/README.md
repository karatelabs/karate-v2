# karate-js

A lightweight JavaScript engine implemented in Java from scratch, designed to run on the JVM with full support for concurrent thread execution.

> See also: [Design Principles](../PRINCIPLES.md) | [Roadmap](../ROADMAP.md) | [karate-core](../karate-core)

> **Note:** This module was previously maintained as a separate project at [karatelabs/karate-js](https://github.com/karatelabs/karate-js). It has been folded into karate-v2 but remains reusable by third-party projects. A lot of improvements have been made, and extensive testing has been done against code written by LLMs.

## Why Another JavaScript Engine?

The JVM JavaScript landscape has a troubled history:

1. **Mozilla Rhino** - The original, now legacy
2. **Oracle Nashorn** - Included in JDK 8, deprecated and removed
3. **GraalJS** - Current Oracle offering, but with a critical limitation.

We need a JavaScript engine we completely control, ensuring that engine changes never impact our users. The decision to write one from scratch was not made lightly, and is explained [here](https://github.com/karatelabs/karate-js). 

## Features

- **Thread-safe** - Full support for concurrent execution across threads
- **Minimal** - adds very little to the JAR size compared to e.g. Graal. SLF4J and JSON-smart are the only dependencies.
- **Java Interop** - Optimized for bridging Java and JS code in the simplest way possible. Allows full control, behavior customization and introspection from Java.
- **Simple** - Concise and understandable codebase, easy to maintain. Plenty of unit-tests.
- **Fast** - The parser is written by hand and not generated like ANTLR based approaches. Conversions between Java and JS types are minimized. Early benchmarks suggest a 10x performance over other Java based engines

## Architecture

| Package | Description |
|---------|-------------|
| `io.karatelabs.js` | Core JavaScript engine (parser, interpreter, runtime) |
| `io.karatelabs.gherkin` | Gherkin syntax parser for test specifications (reuses the JS lexer) |
| `io.karatelabs.common` | Shared utilities (file, OS, string operations) |

> **Note:** The Gherkin parser lives in this module because it reuses the JS lexer infrastructure. The ScenarioEngine and test execution runtime live in `karate-core`.

### JavaScript Engine Components

- **Lexer** - JFlex-generated tokenizer
- **Parser** - Hand-written recursive descent parser
- **Interpreter** - Tree-walking interpreter
- **Java Interop** - Reflection-based Java object access
- **Built-in Types** - JsObject, JsArray, JsString, JsNumber, JsBoolean, JsDate, JsRegex, JsFunction

## Planned Features

These features are on the roadmap:

- `async`/`await` - Will map to Java virtual threads
- `setTimeout()` and timer functions
- ES-compliant Promises - Will map to CompletableFuture
- ES Modules (`import`/`export`) - Enable JS reuse across tests

## Deliberately Unsupported Features

We prioritize practical functionality over full ECMAScript compliance. These features are intentionally excluded:

- `Symbol`
- Generators
- `BigInt`
- Node.js APIs
- `class` keyword (use functions and prototypes)

This keeps the engine small, fast, and maintainable. Full ES compatibility is a long-term goal if there is community interest.

## Usage

```java
import io.karatelabs.js.Engine;

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

## Third-Party Usage

This module can be used independently of karate-core. If you're integrating karate-js into your own project:

```xml
<dependency>
    <groupId>io.karatelabs</groupId>
    <artifactId>karate-js</artifactId>
    <version>2.0.0.RC1</version>
</dependency>
```

Contributions that improve decoupling for third-party usage are welcome.

## License

[MIT License](../LICENSE)
