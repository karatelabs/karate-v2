# Karate v2 Roadmap

This document tracks the development roadmap for Karate v2. It serves as a persistent reference for contributors and LLM assistants working on the project.

> See also: [Design Principles](./PRINCIPLES.md) | [Capabilities](./CAPABILITIES.md) | [karate-js](../karate-js) | [karate-core](../karate-core)

> **Status Key:**
> - `[ ]` Not started
> - `[~]` In progress
> - `[x]` Complete

---

## Milestone 1: API Testing Release

> **Goal:** Drop-in replacement for Karate 1.x API testing. Existing tests should just work.

### Gherkin Parser & Scenario Engine

The Gherkin parser lives in `karate-js` (reuses the JS lexer). The ScenarioEngine lives in `karate-core`.

- [x] Gherkin parser (Feature, Scenario, ScenarioOutline, Background, Examples, tags, steps)
- [x] ScenarioEngine (Suite, FeatureRuntime, ScenarioRuntime, StepExecutor)
- [x] Variable scoping (local, global, feature-level)
- [x] `call` and `callonce` keywords for feature composition
- [x] Doc strings for multi-line values
- [x] Data tables for parameterized steps
- [x] Tags parsing
- [x] Parallel scenario execution (virtual threads)
- [x] RuntimeHook interface (Before/After suite/feature/scenario)
- [x] ResultListener interface for result streaming
- [x] JavaScript expression evaluation in steps
- [x] String interpolation with variable substitution
- [ ] `retry until` keyword for polling
- [ ] Tag expressions filtering
- [ ] Step definitions with regex pattern matching

### HTTP Client

- [x] All HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)
- [x] Request/response cycle management
- [x] Cookie and session management
- [x] Custom headers and header tracking
- [x] Query parameters and path parameters
- [x] SSL/TLS (custom certs, keystore/truststore, trust-all option)
- [x] Proxy support with authentication
- [x] Timeout configuration (connect, read)
- [x] Follow redirects option
- [x] Multipart and file uploads
- [x] Form-encoded data
- [ ] Request/response filters
- [ ] HTTP/2 support
- [ ] GraphQL support
- [ ] SOAP/soapAction support
- [ ] NTLM authentication
- [x] HttpLogModifier for sensitive data masking
- [ ] gRPC support (if external module exists)

### Match & Assertions

- [x] Core operators: EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS
- [x] CONTAINS_ONLY, CONTAINS_ANY, CONTAINS_DEEP variants
- [x] EACH_* variants for array iteration
- [ ] JSONPath expressions
- [ ] XPath expressions (namespace-aware)
- [ ] Regular expression matching
- [ ] Schema validation
- [ ] Response status code validation
- [ ] Response header validation
- [ ] Response time validation
- [ ] `assert` keyword for JS expression assertions

### Data Formats

- [x] JSON (parsing, serialization, pretty print)
- [x] XML (parsing, serialization)
- [ ] CSV (FastCSV integration)
- [ ] YAML (SnakeYAML)
- [x] Binary/bytes data handling
- [ ] Template substitution

### Configuration

- [x] `karate-config.js` support for global configuration
- [x] Environment-based config (`karate.env`)
- [x] RuntimeHook interface (before/after suite/feature/scenario)
- [ ] Plugin system support
- [ ] `karate-base.js` (shared config from classpath JAR)

### Reporting

- [x] Karate JSON report format (`karate-summary.json`)
- [x] JUnit XML report format (`karate-junit.xml`)
- [x] Summary statistics (pass/fail counts, durations)
- [x] Console output with ANSI colors
- [ ] HTML report (interactive dashboard)
- [ ] Cucumber JSON report format
- [ ] Timeline view
- [ ] Tag-based analytics
- [ ] Result embedding in reports

### CLI Compatibility

Integration with [Karate CLI](https://github.com/karatelabs/karate-cli):

- [ ] Update Karate CLI to support karate-v2 as backend
- [ ] Feature file/directory paths
- [ ] `-t, --tags` - Tag filtering
- [ ] `-T, --threads` - Parallel thread count
- [ ] `-n, --name` - Run single scenario by name
- [ ] `-D, --dryrun` - Dry run mode
- [ ] `-o, --output` - Output directory
- [ ] `-f, --format` - Report formats (html, cucumber:json, junit:xml)
- [ ] `-e, --env` - Environment variable
- [ ] `-g, --configdir` - Config directory for karate-config.js
- [ ] `-C, --clean` - Clean output before run
- [ ] `-d, --debug` - Debug mode with port
- [ ] `-H, --hook` - Runtime hook classes
- [ ] ANSI colored console output
- [ ] Progress indicators
- [ ] Interactive mode for LLM sessions

### Console & Logging

- [x] ANSI coloring for all console logs
- [x] Structured logging (SLF4J/Logback)
- [x] Request/response log prettification
- [x] Print statements with expression evaluation
- [ ] File logging support
- [ ] Configurable verbosity levels
- [ ] LLM-friendly output mode (token-efficient)
- [ ] Stack traces with context

### Backwards Compatibility Testing

- [ ] Create compatibility test suite from karate-demo
- [ ] Test against real-world Karate 1.x projects
- [ ] Document any intentional breaking changes
- [ ] Migration guide for edge cases

---

## Milestone 2: API Mocks

> **Goal:** Full mock server capabilities with HTMX/AlpineJS support.
> **Status:** Partially implemented (helps test API client)

### Mock Server Core
- [x] HTTP mock server (Netty-based)
- [ ] Feature-based mock definitions
- [x] Dynamic request/response handling
- [x] Request matching and routing
- [x] Header and body customization
- [x] Status code control
- [ ] Delay/latency simulation
- [x] Stateful mocks (session support)

### CLI Mock Options
- [ ] `-m, --mock` - Mock server feature files
- [ ] `-P, --prefix` - Path prefix/context-path
- [x] `-p, --port` - Server port
- [ ] `-s, --ssl` - Enable HTTPS
- [ ] `-c, --cert` and `-k, --key` - SSL cert/key files
- [ ] `-W, --watch` - Hot-reload mock files
- [ ] `-S, --serve` - App server mode

### Advanced Features
- [ ] Mock recording and playback
- [x] CORS support
- [~] HTMX integration
- [ ] AlpineJS integration

---

## Milestone 3: API Performance Testing

> **Goal:** Scale from functional tests to load tests.

- [ ] Gatling-style load testing
- [ ] Distributed test execution
- [ ] Performance metrics and reporting
- [ ] Resource monitoring
- [ ] Throttling and rate limiting

---

## Release Preparation

### Documentation
- [x] Create PRINCIPLES.md
- [x] Create ROADMAP.md (this file)
- [x] Create root README.md
- [x] Create karate-js/README.md
- [x] Create karate-core/README.md
- [x] Create CONTRIBUTING.md
- [x] Create SECURITY.md

### CI/CD Pipeline
- [ ] Set up GitHub Actions workflow for CI
- [ ] Configure automated testing on PR
- [ ] Add code coverage reporting
- [ ] Add dependency vulnerability scanning
- [ ] Java version compatibility matrix (21, 22, 23, EA builds)

### One-Click Release Workflow
- [ ] GitHub Actions workflow for unified release process
- [ ] Automated version bumping and changelog generation
- [ ] Maven Central publish step (with GPG signing)
- [ ] Docker image build and push to registry
- [ ] GitHub Release creation with assets
- [ ] Release validation tests (smoke tests against published artifacts)

### Maven Artifact Publishing
- [ ] Configure Maven Central publishing (Sonatype OSSRH)
- [ ] Configure POM metadata (SCM, developers, licenses)
- [ ] Document release process

### Repository Hygiene
- [ ] Archive [karatelabs/karate-js](https://github.com/karatelabs/karate-js) repository
- [ ] Add redirect notice to archived repo pointing to karate-v2
- [ ] Configure GitHub repository settings (branch protection, etc.)
- [ ] Set up issue templates
- [ ] Set up PR templates

---

## Future Milestones

### JavaScript Engine (karate-js)

> See [JS_ENGINE.md](./JS_ENGINE.md) for detailed type system and design patterns.

**High Priority - Java Interop:**
- [ ] BigInt → `BigInteger` (large IDs, timestamps, financial identifiers)
- [ ] BigDecimal → `BigDecimal` (money/finance - floating point is dangerous)
- [ ] ArrayBuffer → `byte[]` (raw binary data container)
- [ ] JsRegex + JavaMirror (return `Pattern` from `getJavaValue()`)

**Medium Priority:**
- [ ] Set → `java.util.Set` (deduplication, membership checks)
- [ ] Map (proper JS Map) → `java.util.Map` (ordered keys, non-string keys)
- [ ] Iterator/for-of → `java.util.Iterator` (clean iteration over Java collections)

**ES Compatibility (Lower Priority):**
- [ ] `async`/`await` → `CompletableFuture` / virtual threads
- [ ] `setTimeout()` and timer functions
- [ ] ES Modules (`import`/`export`) for JS reuse across tests
- [ ] Generator/yield → Iterator with state
- [ ] Symbol → `JsSymbol` (unique identifiers)
- [ ] Proxy → Dynamic proxy (metaprogramming)
- [ ] WeakMap/WeakSet → `WeakHashMap`

**Utilities:**
- [ ] Engine State JSON Serialization (persist/restore engine bindings)

> Full ES compatibility is a long-term goal if there is community interest.

### Parser & IDE Support

> See [PARSER.md](./PARSER.md) for detailed parser architecture and APIs.

Error-tolerant parsing for IDE features (syntax coloring, code completion, formatting).

- [ ] Code Formatting (JSON-based options, token-based and AST-based strategies)
- [ ] Source Reconstitution (regenerate source from AST)
- [ ] Embedded Language Support (JS highlighting inside Gherkin steps)

### Runtime Advanced Features

- [ ] Lock System (`@lock=<name>`) for mutual exclusion in parallel execution
- [ ] Retry System (`@retry`) for flaky test handling with `rerun.txt`
- [ ] Multiple Suite Execution with environment isolation
- [ ] Telemetry (anonymous usage stats, once per day, opt-out via `KARATE_TELEMETRY=false`)
- [ ] Classpath scanning for feature files (`classpath:features/`)
- [ ] CLI JSON Configuration (`karate --config karate.json`)

### Templating & Markup
- [ ] Document Thymeleaf-based templating
- [ ] Document custom Karate dialect processors
- [ ] Native markdown parsing and rendering

### Cross-Language Support
- [ ] Continue [Karate CLI](https://github.com/karatelabs/karate-cli) development
- [ ] Platform binaries (macOS, Windows, Linux)
- [ ] .NET integration
- [ ] Python client library
- [ ] Go client library

### Platform Automation (Post-Milestone 3)
- [ ] Desktop automation (macOS, Windows, Linux)
- [ ] Mobile automation (iOS, Android)
- [ ] Web browser automation

### Extension Points
- [ ] Plugin system documentation
- [ ] Protocol handler extension
- [ ] Data format converters (Protobuf, Avro)

### Enterprise Features (Commercial)

> These features fund continued open-source innovation.

- [ ] WebSocket testing (deprecated from open-source, moved to commercial)
- [ ] Requirements management integration
- [ ] Advanced distributed testing
- [ ] Enhanced IDE support
- [ ] API governance tools
- [ ] [Karate Xplorer](https://xplorer.karatelabs.io/) desktop platform

---

## Principle Extensions

These themes emerged from brainstorming but aren't explicitly covered by the current principles in `PRINCIPLES.md`. They may warrant future principle additions or can inform implementation decisions.

### Soft Assertions & Error Handling Philosophy

Current Karate behavior is fail-fast on assertion failures. Consider:
- **Soft assertions mode:** Continue execution after match failures, aggregate all failures in report
- **User-defined assertion messages:** Allow custom failure messages for better diagnostics
- **onError hooks:** JavaScript hooks that fire on any error, enabling custom recovery or logging

### Telemetry & Version Awareness

Help users stay current and provide usage insights:
- **Version out-of-date banner:** Alert users when a newer Karate version is available
- **Usage telemetry:** Anonymous usage patterns to guide development priorities
- **Upsell touchpoints:** Tasteful awareness of commercial offerings (e.g., report footers)

### Testing AI & LLM Systems

Beyond being LLM-friendly, Karate can be a tool for testing AI systems:
- **Testing MCP servers:** Validate MCP server implementations
- **Mocking LLM APIs:** Deterministic responses for testing LLM-dependent code
- **Prompt injection testing:** Security testing for AI systems
- **LLM comparison:** A/B testing different models for given tasks
- **Similarity assertions:** Fuzzy matching using embeddings for AI-generated content

### Robustness & Execution Control

- **Re-run only failed tests:** Resume from failure point using report data
- **Random seed for test order:** Detect order-dependent tests
- **Tag execution ordering:** Some tags run on same thread, some after others
- **Multiple environments in parallel:** Run same tests against staging and prod simultaneously

### Large Data Handling

- **Streaming responses:** Memory-efficient handling of large file downloads
- **Operations on huge data:** Match/sort operations without loading everything into memory

---

## Notes for Contributors

This roadmap is a living document. When working on tasks:

1. Update the status checkbox when starting or completing work
2. Add new tasks as they're identified
3. Move completed sections to an archive if the list grows too long
4. Reference this document in PRs to maintain context

**For LLM assistants:**

Start each session by reading:
1. **PRINCIPLES.md** - Understand the "why" behind decisions
2. **ROADMAP.md** (this file) - Single source of truth for all pending work
3. **CAPABILITIES.yaml** - Source of truth for capabilities (edit this, not the .md)
4. **Module READMEs** - Understand architecture (`karate-js/README.md`, `karate-core/README.md`)
5. **RUNTIME.md** - Runtime architecture and implementation details
6. **JS_ENGINE.md** - JavaScript engine type system and Java interop patterns
7. **PARSER.md** - Parser infrastructure for IDE support and tooling

**Important:** `CAPABILITIES.md` is auto-generated from `CAPABILITIES.yaml`. Never edit the .md file directly. After updating the YAML, run `./etc/generate-capabilities.sh` to regenerate.

Key decisions made:
- WebSocket support deprecated from open-source, moved to commercial
- Gherkin parser in `karate-js` (reuses JS lexer), ScenarioEngine in `karate-core`
- Milestone 1 focus: drop-in replacement for Karate 1.x API testing
- Desktop/mobile automation will use polyglot approach (Swift on macOS, .NET on Windows, etc.)
- Karate 1.x source is available for porting reference

Update this file as work progresses to maintain context across sessions.
