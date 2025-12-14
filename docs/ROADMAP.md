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

The Gherkin parser lives in `karate-js` (reuses the JS lexer). The ScenarioEngine is ported from Karate 1.x.

- [~] Gherkin parser (Feature, Scenario, tags, steps implemented; ScenarioOutline, Background, Examples pending)
- [ ] Port ScenarioEngine (FeatureRuntime, ScenarioRuntime, StepRuntime)
- [ ] Step definitions with regex pattern matching
- [ ] Variable scoping (local, global, feature-level)
- [ ] `call` and `callonce` keywords for feature composition
- [ ] `retry until` keyword for polling
- [ ] Doc strings for multi-line values
- [ ] Data tables for parameterized steps
- [~] Tags parsing (implemented; tag expressions filtering pending)
- [ ] Parallel scenario execution (ExecutorService/CompletableFuture)
- [ ] Scenario hooks (Before, After, BeforeAll, AfterAll)
- [ ] JavaScript expression evaluation in steps
- [ ] String interpolation with variable substitution

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

- [ ] `karate-config.js` support for global configuration
- [ ] Environment-based config (`karate.env`)
- [ ] RuntimeHook interface (before/after suite/feature/scenario/step)
- [ ] Plugin system support

### Reporting

- [ ] Karate JSON report format
- [ ] JUnit XML report format
- [ ] Cucumber JSON report format
- [ ] Karate HTML report (interactive dashboard)
- [ ] Timeline view
- [ ] Tag-based analytics
- [ ] Step-by-step execution logs
- [ ] Summary statistics (pass/fail counts, durations)
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

- [ ] ANSI coloring for all console logs
- [x] Structured logging (SLF4J/Logback)
- [ ] File logging support
- [ ] Configurable verbosity levels
- [x] Request/response log prettification
- [ ] Print statements with expression evaluation
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

**ES Compatibility Enhancements:**
- [ ] `async`/`await` support (map to Java threads/virtual threads)
- [ ] `setTimeout()` and timer functions
- [ ] ES-compliant Promises (map to CompletableFuture)
- [ ] ES Modules (`import`/`export`) for JS reuse across tests

**Maintenance:**
- [ ] Keep engine stable and performant
- [ ] Monitor and address any compatibility issues
- [ ] Document supported JavaScript features
- [ ] Third-party integration documentation

> Full ES compatibility is a long-term goal if there is community interest.

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
2. **ROADMAP.md** (this file) - Understand current priorities and tasks
3. **CAPABILITIES.yaml** - Source of truth for capabilities (edit this, not the .md)
4. **Module READMEs** - Understand architecture (`karate-js/README.md`, `karate-core/README.md`)

**Important:** `CAPABILITIES.md` is auto-generated from `CAPABILITIES.yaml`. Never edit the .md file directly. After updating the YAML, run `./etc/generate-capabilities.sh` to regenerate.

Key decisions made:
- WebSocket support deprecated from open-source, moved to commercial
- Gherkin parser in `karate-js` (reuses JS lexer), ScenarioEngine in `karate-core`
- Milestone 1 focus: drop-in replacement for Karate 1.x API testing
- Desktop/mobile automation will use polyglot approach (Swift on macOS, .NET on Windows, etc.)
- Karate 1.x source is available for porting reference

Update this file as work progresses to maintain context across sessions.
