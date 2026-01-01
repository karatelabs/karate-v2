# Agent Browser API

> **Mission:** Be the most effective, fastest, and token-efficient browser-use implementation for LLM agents.

## For Claude Code Users

Build and launch the agent:
```bash
cd karate-v2
mvn package -DskipTests -Pfatjar
java -jar karate-core/target/karate.jar agent --sidebar
```

Then use Python to control the browser:
```python
import subprocess, json

proc = subprocess.Popen(
    ["java", "-jar", "karate-core/target/karate.jar", "agent", "--sidebar"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    text=True, bufsize=1
)
proc.stdout.readline()  # wait for ready

def agent(js):
    proc.stdin.write(json.dumps({"command": "eval", "payload": js}) + "\n")
    proc.stdin.flush()
    return json.loads(proc.stdout.readline())

# Navigate and inspect
agent("agent.go('https://example.com')")
result = agent("agent.look()")
print(result["payload"]["tree"])  # ['link:Learn more[e1]']

# Interact with elements
agent("agent.act('e1', 'click')")

# Close when done
proc.stdin.close()
proc.wait()
```

**Key patterns:**
- `look()` returns a tree of `role:name[ref]` strings - use refs to act
- After navigation or page changes, call `look()` again to get fresh refs
- Use `--sidebar` during development to see commands visually

---

## Overview

### The Problem

The Driver interface has ~70 methods. For LLM agents:
- Large API surface wastes tokens in system prompts
- LLMs may call inappropriate methods
- No guidance on what actions make sense in current state

### The Solution

Expose 6 core actions via an `agent` object over a stdio JSON protocol:

```javascript
agent.look()                    // Get page state + available actions
agent.act(ref, op, value?)      // Perform action on element
agent.go(url)                   // Navigate
agent.wait(condition)           // Wait for element/state
agent.eval(js)                  // Escape hatch for edge cases
agent.handoff(message)          // Request user intervention, wait for Resume
```

### Architecture

```
LLM Orchestrator (Claude Code, etc.)
    │
    ├─ spawn: karate agent --headless
    │
    ├─ stdin:  {"command":"eval","payload":"agent.look()"}
    ├─ stdout: {"command":"result","payload":{...}}
    │
    └─ close stdin → browser terminates
```

---

## Quick Start

```bash
# Build fatjar
mvn package -DskipTests -Pfatjar

# Run the test script (navigates to example.com and httpbin form)
python3 etc/test-agent.py

# Or start interactive session with visible browser
java -jar karate-core/target/karate.jar agent --sidebar
```

---

## CLI Usage

```bash
karate agent [--headless] [--sidebar] [--browser <chrome|firefox>] [--log <file>]
```

| Flag | Description |
|------|-------------|
| `--headless` | Run browser in headless mode |
| `--sidebar` | Show command sidebar in browser (for debugging) |
| `--browser` | Browser type (default: chrome) |
| `--log` | Log file for debug output |

### Sidebar Mode

Use `--sidebar` to see a visual overlay showing commands as they execute:

```bash
karate agent --sidebar
```

This displays a floating panel with:
- Real-time command logging (`go()`, `look()`, `act()`)
- Element highlighting when actions are performed
- Timestamps for each command

**Sidebar controls:**
- **Drag** the header to reposition
- **Resize** by dragging the corner
- **`_` button** - collapse/expand the panel
- **`C` button** - clear the log

The sidebar is non-intrusive - it floats over the page without affecting layout or responsive breakpoints.

### Session Lifecycle

1. **Startup**: Browser launches immediately on `karate agent`
2. **Ready signal**: `{"command":"ready","payload":{}}` sent to stdout
3. **Request/Response**: Synchronous JSON over stdin/stdout
4. **Shutdown**: Close stdin to terminate (browser killed immediately)

---

## Protocol Specification

### Transport
- **stdio**: stdin for requests, stdout for responses
- **Encoding**: UTF-8
- **Framing**: Newline-delimited JSON (one JSON object per line)

### Request Format

```json
{"command":"eval","payload":"<javascript code>"}
```

The `payload` is JavaScript code that will be evaluated with an `agent` object in scope.

### Response Format

Success:
```json
{"command":"result","payload":{...}}
```

Error:
```json
{"command":"error","payload":{"message":"...","code":"STALE_REF"}}
```

### Error Codes

| Code | Description |
|------|-------------|
| `STALE_REF` | Element ref is stale, call `look()` to refresh |
| `INVALID_ARGS` | Missing or invalid arguments |
| `ACTION_FAILED` | Browser action failed |
| `TIMEOUT` | Wait condition timed out |
| `EVAL_FAILED` | JavaScript evaluation error |

---

## API Reference

### `agent.look(options?)`

Get current page state and available actions.

**Options:**
```javascript
look()                   // Default: viewport + interactable only
look({scope: '#main'})   // Scope to container
look({viewport: false})  // Include off-screen elements
```

**Returns:**
```json
{
  "url": "https://example.com/login",
  "title": "Login Page",
  "tree": ["textbox:Email[e1]", "textbox:Password[e2]", "button:Sign In[e3]"],
  "actions": {
    "e1": ["input", "clear", "focus"],
    "e2": ["input", "clear", "focus"],
    "e3": ["click"]
  }
}
```

**Tree Format:**
- JSON array of compact strings: `role:name[ref]`
- Interactable elements only
- Viewport-visible by default
- Minimal tokens, easy to parse

### `agent.act(ref, op, value?)`

Perform an action on an element.

**Parameters:**
- `ref` - Element reference from tree (e.g., `"e1"`)
- `op` - Operation name (e.g., `"click"`, `"input"`)
- `value` - Optional value for input/select operations

**Returns:**
```json
{"changed": true}
```

**Error (stale ref):**
```json
{"error": "ref e5 is stale, call look() to refresh", "code": "STALE_REF"}
```

### `agent.go(url)`

Navigate to a URL.

**Returns:**
```json
{"url": "https://example.com/page", "title": "Page Title"}
```

### `agent.wait(condition)`

Wait for an element or condition.

**Parameters:**
- `condition` - Either a ref (`"e1"`) or JS expression (`"document.title.includes('Done')"`)

**Returns:**
```json
{"elapsed": 1250}
```

### `agent.eval(js)`

Execute arbitrary JavaScript in the browser. Escape hatch for edge cases.

**Returns:**
```json
{"value": "return value from JS"}
```

### `agent.handoff(message)`

Request user intervention. Shows alert in sidebar and **blocks** until user clicks Resume.

**Usage:**
```javascript
agent.handoff("Please solve the CAPTCHA")
agent.handoff("Login required - please authenticate")
```

**Returns (after user clicks Resume):**
```json
{"resumed": true, "elapsed": 7500}
```

The sidebar displays:
- Prominent red alert with your message
- "Resume Agent" button
- Glowing border to attract attention

This enables human-in-the-loop workflows where the agent works autonomously but can pause for user assistance when stuck.

---

## Action Types by Role

| Role | Available Actions |
|------|-------------------|
| button | `click` |
| link | `click` |
| textbox | `input`, `clear`, `focus` |
| checkbox | `click`, `check`, `uncheck` |
| radio | `click` |
| combobox | `select`, `input`, `click` |
| listbox | `select` |
| generic | `click` |

---

## Element Refs

- Refs are generated on each `look()` call: `e1`, `e2`, `e3`, ...
- Refs map to DOM elements in browser memory
- Refs become **stale** after navigation or another `look()` call
- On stale ref, return error instructing to call `look()` again

**MVP stale handling:**
- Each `look()` clears and regenerates all refs
- If `act()` fails with stale ref, return error with `STALE_REF` code
- LLM should call `look()` to get fresh refs

---

## System Prompt

Minimal prompt for LLM (~100 tokens):

```
You control a browser via these methods:

agent.look()              - Get page state (tree of elements with refs)
agent.act(ref, op, value) - Perform action (click, input, etc.)
agent.go(url)             - Navigate to URL
agent.wait(ref or "js")   - Wait for element or JS condition
agent.eval(js)            - Run arbitrary JavaScript
agent.handoff(message)    - Ask user to intervene, wait for Resume

Tree format: ["button:Submit[e1]", "textbox:Email[e2]", ...]
Use refs from tree to act: agent.act('e1', 'click')
If ref is stale, call look() to refresh.
If stuck (CAPTCHA, login, complex UI), use handoff() to ask user for help.
```

---

## Example Session

```bash
$ karate agent --headless
{"command":"ready","payload":{}}
```

Orchestrator sends:
```json
{"command":"eval","payload":"agent.go('https://example.com/login')"}
```

Response:
```json
{"command":"result","payload":{"url":"https://example.com/login","title":"Login"}}
```

Orchestrator sends:
```json
{"command":"eval","payload":"agent.look()"}
```

Response:
```json
{"command":"result","payload":{"url":"https://example.com/login","title":"Login","tree":["textbox:Email[e1]","textbox:Password[e2]","button:Sign In[e3]"],"actions":{"e1":["input","clear","focus"],"e2":["input","clear","focus"],"e3":["click"]}}}
```

Orchestrator sends:
```json
{"command":"eval","payload":"agent.act('e1','input','admin@test.com')"}
```

Response:
```json
{"command":"result","payload":{"changed":true}}
```

Orchestrator sends:
```json
{"command":"eval","payload":"agent.act('e2','input','secret123')"}
```

Response:
```json
{"command":"result","payload":{"changed":true}}
```

Orchestrator sends:
```json
{"command":"eval","payload":"agent.act('e3','click')"}
```

Response:
```json
{"command":"result","payload":{"changed":true}}
```

Orchestrator sends:
```json
{"command":"eval","payload":"agent.look()"}
```

Response:
```json
{"command":"result","payload":{"url":"https://example.com/dashboard","title":"Dashboard","tree":["link:Home[e1]","link:Settings[e2]","button:Logout[e3]"],"actions":{"e1":["click"],"e2":["click"],"e3":["click"]}}}
```

Close stdin → browser terminates, CLI exits 0.

---

## Implementation Status

### Completed
- [x] Protocol design finalized
- [x] AgentDriver.java - 5-method facade with SimpleObject
- [x] aria-snapshot.js - external resource file for element discovery
- [x] AgentStdio.java - stdio protocol handler with ExternalBridge
- [x] AgentCommand.java - CLI entry point
- [x] ref: locator support in Locators.java
- [x] Register in Main.java
- [x] logback.xml - stderr logging for clean stdout JSON
- [x] End-to-end tested with subprocess
- [x] `--sidebar` flag with floating, draggable, collapsible panel

### Future Enhancements
- [ ] Shadow DOM traversal in aria-snapshot.js
- [ ] Screenshot on error
- [ ] Streaming mode for long operations
- [ ] MCP server mode
- [ ] JavaFX companion UI (side-by-side with browser)
  - Element highlighting and inspection
  - Step-back/replay functionality
  - Session recording
  - HTTP traffic capture and display

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Protocol | JSON over stdio | Simple, universal, works with any LLM orchestrator |
| Tree format | JSON array of compact strings | Minimal tokens, easy to parse |
| Ref scope | Reset per look() | Simple MVP, avoids complex tracking |
| Error handling | Return error, continue session | LLM can recover by calling look() |
| Browser process | In-process, blocking | Simpler architecture for MVP |
| Orchestration | External | Karate is a browser tool, not an LLM host |

---

## File Locations

| File | Purpose |
|------|---------|
| `karate-core/src/main/java/io/karatelabs/driver/AgentDriver.java` | 5-method facade |
| `karate-core/src/main/java/io/karatelabs/cli/AgentCommand.java` | CLI entry point |
| `karate-core/src/main/java/io/karatelabs/cli/AgentStdio.java` | Stdio protocol |
| `karate-core/src/main/resources/io/karatelabs/driver/aria-snapshot.js` | Element discovery |
| `karate-core/src/main/resources/logback.xml` | Stderr logging config |
| `etc/test-agent.py` | Python test script |
| `docs/DRIVER_AGENT.md` | This specification |
