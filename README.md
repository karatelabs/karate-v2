# Karate v2

> Test Automation Made Simple

Karate v2 is a complete rewrite of [Karate](https://github.com/karatelabs/karate), the popular open-source test automation framework. It combines API testing, mocking, and performance testing into a single, unified tool. 

> Karate v1 supports web-browser automation, and this will be eventually added to v2 as well.

## Modules

| Module | Description |
|--------|-------------|
| [karate-js](./karate-js) | Lightweight JavaScript engine for the JVM with thread-safe concurrent execution |
| [karate-core](./karate-core) | Core testing framework with HTTP client/server, templating, and match assertions |

## Key Features

- **API Test Automation** - Write expressive tests in Gherkin syntax with powerful JSON/XML assertions
- **Mock Servers** - Built-in HTTP server for API mocking with dynamic responses
- **Performance Testing** - Scale from functional tests to load tests seamlessly
- **HTML Templating** - Thymeleaf-based templating with HTMX and AlpineJS support
- **Thread-Safe JavaScript** - Custom JS engine designed for parallel test execution
- **Java 21+** - Built on modern Java with virtual threads and latest language features

## Getting Started

### Requirements

- Java 21 or higher

### Maven Dependency

```xml
<dependency>
    <groupId>io.karatelabs</groupId>
    <artifactId>karate-core</artifactId>
    <version>2.0.0.RC1</version>
</dependency>
```

## Documentation

- [Design Principles](./docs/PRINCIPLES.md) - Core principles guiding v2 development
- [Roadmap](./docs/ROADMAP.md) - Development roadmap and task tracker
- [Capabilities](./docs/CAPABILITIES.md) - Full taxonomy of features
- [Contributing](./CONTRIBUTING.md) - How to contribute

Full documentation: [docs.karatelabs.io](https://docs.karatelabs.io)

## For Karate 1.x Users

Karate v2 maintains backwards compatibility with 1.x features. If you're migrating from 1.x, most of your existing tests should work with minimal changes. See the migration guide (coming soon) for details.

## Related Projects

- [Karate CLI](https://github.com/karatelabs/karate-cli) - Command-line interface for non-Java environments
- [Karate Xplorer](https://xplorer.karatelabs.io/) - Desktop IDE for Karate

## License

[MIT License](./LICENSE)

## About

Karate v2 is developed by [Karate Labs Inc.](https://www.karatelabs.io) with contributions from the community.

We invite enterprise users of Karate to review, influence the direction, and contribute to this project.
