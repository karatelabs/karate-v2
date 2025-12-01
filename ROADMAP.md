# Karate v2 Roadmap

This document tracks the development roadmap for Karate v2. It serves as a persistent reference for contributors and LLM assistants working on the project.

> See also: [Design Principles](./PRINCIPLES.md) | [karate-js](./karate-js) | [karate-core](./karate-core)

> **Status Key:**
> - `[ ]` Not started
> - `[~]` In progress
> - `[x]` Complete

---

## Milestone 1: API Testing Release

> **Goal:** Drop-in replacement for Karate 1.x API testing. Existing tests should just work.

### Gherkin Parser & Scenario Engine

The Gherkin parser lives in `karate-js` (reuses the JS lexer). The ScenarioEngine is ported from Karate 1.x.

- [ ] Full Gherkin parser (Feature, Scenario, ScenarioOutline, Background, Examples)
- [ ] Port ScenarioEngine (FeatureRuntime, ScenarioRuntime, StepRuntime)
- [ ] Step definitions with regex pattern matching
- [ ] Variable scoping (local, global, feature-level)
- [ ] `call` and `callonce` keywords for feature composition
- [ ] `retry until` keyword for polling
- [ ] Doc strings for multi-line values
- [ ] Data tables for parameterized steps
- [ ] Tags and tag expressions filtering
- [ ] Parallel scenario execution (ExecutorService/CompletableFuture)
- [ ] Scenario hooks (Before, After, BeforeAll, AfterAll)
- [ ] JavaScript expression evaluation in steps
- [ ] String interpolation with variable substitution

### HTTP Client

- [ ] All HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)
- [ ] Request/response cycle management
- [ ] Cookie and session management
- [ ] Custom headers and header tracking
- [ ] Query parameters and path parameters
- [ ] SSL/TLS (custom certs, keystore/truststore, trust-all option)
- [ ] Proxy support with authentication
- [ ] Timeout configuration (connect, read)
- [ ] Follow redirects option
- [ ] Multipart and file uploads
- [ ] Form-encoded data
- [ ] Request/response filters
- [ ] HTTP/2 support
- [ ] GraphQL support
- [ ] SOAP/soapAction support
- [ ] NTLM authentication
- [ ] HttpLogModifier for sensitive data masking
- [ ] gRPC support (if external module exists)

### Match & Assertions

- [ ] Core operators: EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS
- [ ] CONTAINS_ONLY, CONTAINS_ANY, CONTAINS_DEEP variants
- [ ] EACH_* variants for array iteration
- [ ] JSONPath expressions
- [ ] XPath expressions (namespace-aware)
- [ ] Regular expression matching
- [ ] Schema validation
- [ ] Response status code validation
- [ ] Response header validation
- [ ] Response time validation
- [ ] `assert` keyword for JS expression assertions

### Data Formats

- [ ] JSON (parsing, serialization, pretty print, JSONPath)
- [ ] XML (parsing, serialization, XPath, namespaces)
- [ ] CSV (FastCSV integration)
- [ ] YAML (SnakeYAML)
- [ ] Binary/bytes data handling
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
- [ ] Structured logging (SLF4J/Logback)
- [ ] File logging support
- [ ] Configurable verbosity levels
- [ ] Request/response log prettification
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
- [~] HTTP mock server (Netty-based)
- [ ] Feature-based mock definitions
- [ ] Dynamic request/response handling
- [ ] Request matching and routing
- [ ] Header and body customization
- [ ] Status code control
- [ ] Delay/latency simulation
- [ ] Stateful mocks (session support)

### CLI Mock Options
- [ ] `-m, --mock` - Mock server feature files
- [ ] `-P, --prefix` - Path prefix/context-path
- [ ] `-p, --port` - Server port
- [ ] `-s, --ssl` - Enable HTTPS
- [ ] `-c, --cert` and `-k, --key` - SSL cert/key files
- [ ] `-W, --watch` - Hot-reload mock files
- [ ] `-S, --serve` - App server mode

### Advanced Features
- [ ] Mock recording and playback
- [ ] CORS support
- [ ] HTMX integration
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

### Project Cleanup
- [ ] Review and clean up code for public release
- [ ] Ensure consistent code style across modules
- [ ] Add license headers to all source files (if needed)
- [ ] Review and update .gitignore
- [ ] Remove any internal/sensitive references

### CI/CD Pipeline
- [ ] Set up GitHub Actions workflow for CI
- [ ] Configure automated testing on PR
- [ ] Configure build and publish workflow
- [ ] Add code coverage reporting
- [ ] Add dependency vulnerability scanning

### Repository Hygiene
- [ ] Archive [karatelabs/karate-js](https://github.com/karatelabs/karate-js) repository
- [ ] Add redirect notice to archived repo pointing to karate-v2
- [ ] Configure GitHub repository settings (branch protection, etc.)
- [ ] Set up issue templates
- [ ] Set up PR templates

---

## Announcements

### Blog Post: Karate v2 Public Release
- [ ] Draft announcement blog post
- [ ] Highlight key improvements over v1
- [ ] Invite enterprise users to review and contribute
- [ ] Publish at [Karate Labs blog](https://www.karatelabs.io/blog) or similar

---

## Future Milestones

### JavaScript Engine (karate-js)

**ES Compatibility Enhancements:**
- [ ] `async`/`await` support (map to Java threads/virtual threads)
- [ ] `setTimeout()` and timer functions
- [ ] ES-compliant Promises (map to CompletableFuture)
- [ ] ES Modules (`import`/`export`) for JS reuse across tests

**Known Issues / Backlog:**
- [ ] Elvis operator (`?.`) not working
- [ ] Define behavior for accessing empty array by index 0

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
3. **Module READMEs** - Understand architecture (`karate-js/README.md`, `karate-core/README.md`)

Key decisions made:
- WebSocket support deprecated from open-source, moved to commercial
- Gherkin parser in `karate-js` (reuses JS lexer), ScenarioEngine in `karate-core`
- Milestone 1 focus: drop-in replacement for Karate 1.x API testing
- Desktop/mobile automation will use polyglot approach (Swift on macOS, .NET on Windows, etc.)
- Karate 1.x source is available for porting reference

Update this file as work progresses to maintain context across sessions.
