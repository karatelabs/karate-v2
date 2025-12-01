# Karate v2 Design Principles

Lessons learned from real-world usage since 2017, distilled into the principles guiding this rewrite.

> See also: [Roadmap](./ROADMAP.md) | [karate-js](./karate-js) | [karate-core](./karate-core)

---

## 1. Backwards Compatible

All Karate 1.x features should work. Migration should be straightforward.

## 2. Clear Priorities

Most users depend on API Testing, so the first few releases of Karate v2 will focus on this first, enabling users to validate compatibility.

1. API Testing
2. API Mocks
3. API Performance Testing
4. Web Browser Automation (low priority)

Cross-platform desktop automation and mobile automation will be looked at only after the top priorities are proven stable.

## 3. Fresh Start, Latest Stack

A ground-up rewrite that references 1.x code where useful. No legacy constraints. Make the most of the latest Java syntax and features such as virtual threads.

## 4. Zero-Friction Installation

Non-Java teams should use Karate without wrestling with JVM setup. See [Karate CLI](https://github.com/karatelabs/karate-cli).

## 5. Polyglot by Design

First-class interoperability with non-Java code (.NET, Python, Go, Rust). This foundation enables platform-specific automation: desktop (macOS, Windows, Linux) and mobile (iOS, Android).

## 6. Unified JavaScript API

All capabilities can be scripted using JavaScript. JS becomes a first-class way to write Karate tests alongside traditional `*.feature` files. A key goal is that testing any protocol—HTTP, GraphQL, gRPC, Kafka, or UI—feels the same. One syntax, one learning curve, skills that transfer. No reinventing the wheel for each new integration. This also enables LLMs to control Karate in interactive REPL sessions.

## 7. Embedded HTTP server and HTML engine

Thymeleaf-based templating powered by our high-performance JS engine enables reporting, [Karate Xplorer](https://xplorer.karatelabs.io/), micro-UIs for LLM sessions, and scaling from mock apps to enterprise applications. It also allows teams to customize reports. With LLM assistance, test results can be aligned to the business domain of the system under test, enabling an experience that BDD (Behavior Driven Development) was expected to deliver in the past.

## 8. Human and AI Friendly

Karate tests have a reputation for being concise, focusing on the task or business context, and readable by humans and LLMs. An explorable command-line interface is key for LLMs to surface capabilities and enable tool-use while conserving tokens.

## 9. Logging, Reporting and Observability

One thing that differentiates a testing framework from a normal language runtime is the detailed reports you get after running a test. This is important for debugging and as an audit trail, especially in compliance-heavy business contexts. The built-in HTML templating has been in Karate from the start, and we will continue to support industry standards such as the JUnit XML and Cucumber JSON report data-formats. We will also collaborate closely with other testing and dev-tool projects to ensure enterprises have the best developer experience in local environments and CI/CD. Karate v2 will also improve observability, with more hook and callback options. LLMs will be able to pull logs and stack traces interactively via the CLI or a JavaScript API sandbox.

## 10. Extensibility and Sustainable Open-Core

Some things like the JS engine are now core to Karate and locked in. But it should be possible to support custom protocols or integrations with minimal glue-code. We plan to build a specification mapping layer into Karate v2 in the future. Think of it as a "better BDD approach." This will allow test scenarios to be viewed and edited in a business-friendly way and enable workflows such as test-coverage reporting and impact analysis.

The core is powerful and fully open-source. Commercial extensions (WebSocket testing, distributed testing, enhanced IDE support, API governance, requirements management) fund continued innovation—a virtuous cycle where both tracks strengthen each other. See [ROADMAP.md](./ROADMAP.md) for the full task list.

