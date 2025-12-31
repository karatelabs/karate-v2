# Agent Browser API

> **Mission:** Be the most effective, fastest, and token-efficient browser-use implementation.

> HATEOAS-style minimal API for LLM browser agents.
> Built on top of [Driver](./DRIVER.md) and [ariaTree](./DRIVER_ARIA.md).

## Overview

### Problem

The Driver interface has ~70 methods. For LLM agents:
- Large API surface wastes tokens in system prompts
- LLMs may call inappropriate methods
- No guidance on what actions make sense in current state

### Solution

Expose 5 core actions via an `agent` object. Each response includes available next actions (HATEOAS-style).

```javascript
agent.look()                    // Get page state + available actions
agent.act(ref, op, value?)      // Perform action on element
agent.go(url)                   // Navigate
agent.wait(condition)           // Wait for element/state
agent.eval(js)                  // Escape hatch for edge cases
```

### Goals

1. **General Browser Automation** - LLM agents can browse the web, fill forms, click buttons, and complete multi-step workflows autonomously.

2. **Derive Driver Scripts** - Agents explore web apps and discover the correct sequence of actions. These steps can then be converted to standard `Driver` API calls for reliable, repeatable automation without LLM overhead.

3. **Derive API Tests** - While the agent interacts with the UI, we can capture HTTP traffic (requests/responses) and automatically generate API tests. The agent provides the "happy path" exploration; the traffic capture provides the contract.

---

## API Reference

### `agent.look()`

Get current page state and available actions.

**Returns:**
```json
{
  "url": "https://example.com/login",
  "title": "Login Page",
  "tree": "- textbox \"Email\" [ref=e1]\n- textbox \"Password\" [ref=e2]\n- button \"Sign In\" [ref=e3]",
  "actions": {
    "e1": ["input", "focus", "clear"],
    "e2": ["input", "focus", "clear"],
    "e3": ["click"],
    "_page": ["go", "wait", "back", "refresh", "screenshot"]
  }
}
```

### `agent.act(ref, op, value?)`

Perform an action on an element.

**Parameters:**
- `ref` - Element reference from tree (e.g., `"e1"`)
- `op` - Operation name (e.g., `"click"`, `"input"`)
- `value` - Optional value for input/select operations

**Returns:**
```json
{
  "ok": true,
  "changed": true,
  "actions": {
    "e1": ["clear", "input"],
    "e2": ["input", "focus"],
    "e3": ["click"],
    "_page": ["look", "wait"]
  },
  "hint": "e1 filled, consider e2 next"
}
```

**Error response:**
```json
{
  "ok": false,
  "error": "ref:e5 is stale, call look() to refresh"
}
```

### `agent.go(url)`

Navigate to a URL.

**Returns:**
```json
{
  "ok": true,
  "url": "https://example.com/page",
  "title": "Page Title",
  "actions": { "_page": ["look", "wait", "back"] }
}
```

### `agent.wait(condition)`

Wait for an element or condition.

**Parameters:**
- `condition` - Either a ref (`"e1"`) or JS expression (`"document.title.includes('Done')"`)

**Returns:**
```json
{
  "ok": true,
  "elapsed": 1250,
  "actions": { "_page": ["look"] }
}
```

### `agent.eval(js)`

Execute arbitrary JavaScript in the browser. Escape hatch for edge cases.

**Returns:**
```json
{
  "ok": true,
  "result": "return value from JS",
  "actions": { "_page": ["look"] }
}
```

---

## Action Types by Role

| Role | Available Actions |
|------|-------------------|
| button | `click` |
| link | `click` |
| textbox | `input`, `clear`, `focus` |
| checkbox | `click`, `check`, `uncheck` |
| radio | `click` |
| combobox | `select`, `click`, `input` |
| listbox | `select` |
| slider | `set` |
| generic clickable | `click` |

---

## System Prompt

Minimal prompt for LLM (~60 tokens):

```
You control a browser with these actions:

agent.look()              - See page state and available actions
agent.act(ref, op, value) - Perform action (click, input, etc.)
agent.go(url)             - Navigate to URL
agent.wait(ref or "js")   - Wait for element or JS condition
agent.eval(js)            - Run arbitrary JavaScript

Each response shows what actions are possible next.
Follow the "actions" object to know what you can do.
```

---

## Example Conversation

```
User: Log in with admin@test.com / secret123

LLM: agent.look()
→ {
    "url": "https://example.com/login",
    "title": "Login",
    "tree": "- textbox \"Email\" [ref=e1]\n- textbox \"Password\" [ref=e2]\n- button \"Sign In\" [ref=e3]",
    "actions": { "e1": ["input"], "e2": ["input"], "e3": ["click"] }
  }

LLM: agent.act("e1", "input", "admin@test.com")
→ { "ok": true, "actions": { "e1": ["clear"], "e2": ["input"], "e3": ["click"] } }

LLM: agent.act("e2", "input", "secret123")
→ { "ok": true, "actions": { "e2": ["clear"], "e3": ["click"] } }

LLM: agent.act("e3", "click")
→ { "ok": true, "changed": true, "hint": "navigation detected" }

LLM: agent.look()
→ { "url": "/dashboard", "title": "Dashboard", "tree": "..." }

LLM: Done - successfully logged in.
```

---

## Implementation

### Phase 1: Core Classes

#### `AgentDriver.java`

```java
package io.karatelabs.driver;

import java.util.*;

public class AgentDriver {

    private final Driver driver;
    private Map<String, List<String>> lastActions;

    public AgentDriver(Driver driver) {
        this.driver = driver;
    }

    public Map<String, Object> look() {
        String tree = getAriaTree();
        lastActions = computeActionsFromTree(tree);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", driver.getUrl());
        result.put("title", driver.getTitle());
        result.put("tree", tree);
        result.put("actions", lastActions);
        return result;
    }

    public Map<String, Object> act(String ref, String op, Object value) {
        try {
            Element el = driver.locate("ref:" + ref);
            boolean changed = performAction(el, op, value);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("changed", changed);
            result.put("actions", computeActionsFromTree(getAriaTree()));
            return result;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> go(String url) {
        driver.setUrl(url);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("url", driver.getUrl());
        result.put("title", driver.getTitle());
        result.put("actions", Map.of("_page", List.of("look", "wait", "back")));
        return result;
    }

    public Map<String, Object> wait(String condition) {
        long start = System.currentTimeMillis();
        if (condition.startsWith("e")) {
            // Wait for element ref
            driver.waitFor("ref:" + condition);
        } else {
            // Wait for JS condition
            driver.waitUntil(condition);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("elapsed", System.currentTimeMillis() - start);
        result.put("actions", Map.of("_page", List.of("look")));
        return result;
    }

    public Map<String, Object> eval(String js) {
        Object result = driver.script(js);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("result", result);
        response.put("actions", Map.of("_page", List.of("look")));
        return response;
    }

    private boolean performAction(Element el, String op, Object value) {
        return switch (op) {
            case "click" -> { el.click(); yield true; }
            case "input" -> { el.input(value.toString()); yield true; }
            case "clear" -> { el.clear(); yield true; }
            case "focus" -> { el.focus(); yield false; }
            case "select" -> { el.select(value.toString()); yield true; }
            case "check" -> { if (!el.isChecked()) el.click(); yield true; }
            case "uncheck" -> { if (el.isChecked()) el.click(); yield true; }
            default -> throw new DriverException("Unknown action: " + op);
        };
    }

    private String getAriaTree() {
        // Delegate to ariaTree implementation
        return driver.ariaTree();
    }

    private Map<String, List<String>> computeActionsFromTree(String tree) {
        Map<String, List<String>> actions = new LinkedHashMap<>();

        // Parse tree and extract refs with their roles
        // For each ref, determine available actions based on role
        for (String line : tree.split("\n")) {
            String ref = extractRef(line);
            if (ref != null) {
                String role = extractRole(line);
                actions.put(ref, getActionsForRole(role));
            }
        }

        // Page-level actions
        actions.put("_page", List.of("go", "wait", "back", "refresh", "screenshot"));

        return actions;
    }

    private List<String> getActionsForRole(String role) {
        return switch (role) {
            case "button", "link" -> List.of("click");
            case "textbox" -> List.of("input", "clear", "focus");
            case "checkbox" -> List.of("click", "check", "uncheck");
            case "radio" -> List.of("click");
            case "combobox" -> List.of("select", "input", "click");
            case "listbox" -> List.of("select");
            case "slider" -> List.of("set");
            default -> List.of("click");
        };
    }

    private String extractRef(String line) {
        int start = line.indexOf("[ref=");
        if (start < 0) return null;
        int end = line.indexOf("]", start);
        return line.substring(start + 5, end);
    }

    private String extractRole(String line) {
        // Extract role from "- role \"name\" [ref=e1]"
        String trimmed = line.trim();
        if (trimmed.startsWith("- ")) {
            String rest = trimmed.substring(2);
            int space = rest.indexOf(" ");
            int colon = rest.indexOf(":");
            int end = Math.min(
                space > 0 ? space : rest.length(),
                colon > 0 ? colon : rest.length()
            );
            return rest.substring(0, end);
        }
        return "generic";
    }
}
```

### Phase 2: Karate Integration

#### JS Binding

```java
// In KarateJs or ScenarioRuntime
public Object ai(Map<String, Object> config) {
    Driver driver = createDriver(config);
    return new AgentDriver(driver);
}
```

#### Usage in Gherkin

```gherkin
* def agent = karate.agent({ type: 'chrome', headless: true })
* def state = agent.look()
* print state.tree
* def result = agent.act('e1', 'input', 'hello')
* print result
```

---

## Implementation Tasks

### Task 1: ariaTree() Foundation
- [ ] Implement `ariaTree()` in Driver interface
- [ ] Create `aria-snapshot.js` browser script (see DRIVER_ARIA.md)
- [ ] Add ref resolution for `ref:xxx` locators
- [ ] Test with simple pages

### Task 2: AgentDriver Core
- [ ] Create `AgentDriver.java` class
- [ ] Implement `look()` with tree + actions
- [ ] Implement `act()` with action dispatch
- [ ] Implement `go()`, `wait()`, `eval()`
- [ ] Add error handling with clear messages

### Task 3: Action Computation
- [ ] Parse ariaTree output to extract refs and roles
- [ ] Map roles to available actions
- [ ] Include element state in action list (e.g., no "clear" if empty)
- [ ] Add `_page` level actions

### Task 4: Karate Binding
- [ ] Add `karate.agent(config)` factory method
- [ ] Bind AgentDriver methods to JS engine
- [ ] Test from Gherkin feature files

### Task 5: Local Testing
- [ ] Create test HTML pages for various scenarios
- [ ] Write E2E tests for Agent API
- [ ] Test with mock LLM responses

---

## Local Testing

### Test Setup

```java
@Test
void testAgentDriver() {
    // Start browser
    CdpDriver driver = CdpDriver.start(CdpDriverOptions.headless());
    AgentDriver agent = new AgentDriver(driver);

    // Navigate to test page
    agent.go("http://localhost:8080/login");

    // Look at page
    Map<String, Object> state = agent.look();
    System.out.println("Tree:\n" + state.get("tree"));
    System.out.println("Actions: " + state.get("actions"));

    // Perform actions
    agent.act("e1", "input", "test@example.com");
    agent.act("e2", "input", "password123");
    Map<String, Object> result = agent.act("e3", "click");

    // Verify navigation
    state = agent.look();
    assertTrue(state.get("url").toString().contains("dashboard"));

    driver.quit();
}
```

### Test HTML Page

Create `test-login.html`:
```html
<!DOCTYPE html>
<html>
<head><title>Login</title></head>
<body>
  <h1>Login</h1>
  <form id="login-form">
    <label for="email">Email</label>
    <input type="email" id="email" name="email" placeholder="Enter email">

    <label for="password">Password</label>
    <input type="password" id="password" name="password" placeholder="Enter password">

    <button type="submit">Sign In</button>
  </form>

  <script>
    document.getElementById('login-form').onsubmit = (e) => {
      e.preventDefault();
      window.location.href = '/dashboard';
    };
  </script>
</body>
</html>
```

### Interactive REPL Testing

```java
public class AgentDriverRepl {
    public static void main(String[] args) {
        CdpDriver driver = CdpDriver.start(CdpDriverOptions.builder()
            .headless(false)  // Show browser
            .build());
        AgentDriver agent = new AgentDriver(driver);

        Scanner scanner = new Scanner(System.in);
        System.out.println("Agent Driver REPL. Commands: look, act <ref> <op> [value], go <url>, quit");

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();

            if (line.equals("quit")) break;

            if (line.equals("look")) {
                Map<String, Object> state = agent.look();
                System.out.println("URL: " + state.get("url"));
                System.out.println("Title: " + state.get("title"));
                System.out.println("Tree:\n" + state.get("tree"));
                System.out.println("Actions: " + state.get("actions"));
            }
            else if (line.startsWith("go ")) {
                String url = line.substring(3);
                System.out.println(agent.go(url));
            }
            else if (line.startsWith("act ")) {
                String[] parts = line.substring(4).split(" ", 3);
                String ref = parts[0];
                String op = parts[1];
                Object value = parts.length > 2 ? parts[2] : null;
                System.out.println(agent.act(ref, op, value));
            }
            else {
                System.out.println("Unknown command");
            }
        }

        driver.quit();
    }
}
```

### Gherkin E2E Test

```gherkin
Feature: Agent Driver API

Background:
  * def agent = karate.agent({ type: 'chrome', headless: true })

Scenario: Login flow with Agent API
  * def state = agent.go(serverUrl + '/login')
  * match state.ok == true

  * def state = agent.look()
  * match state.tree contains 'textbox'
  * match state.actions.e1 contains 'input'

  * def result = agent.act('e1', 'input', 'test@example.com')
  * match result.ok == true

  * def result = agent.act('e2', 'input', 'password')
  * match result.ok == true

  * def result = agent.act('e3', 'click')
  * match result.ok == true

  * def state = agent.look()
  * match state.url contains 'dashboard'
```

---

## LLM Integration Testing

### Mock LLM for Testing

```java
public class MockLlm {
    public String generate(String task, Map<String, Object> state) {
        String tree = (String) state.get("tree");

        if (task.contains("login") && tree.contains("textbox")) {
            // Generate login actions
            return """
                agent.act('e1', 'input', 'test@example.com');
                agent.act('e2', 'input', 'password123');
                agent.act('e3', 'click');
                """;
        }

        return "agent.look();";
    }
}
```

### Full Loop Test

```java
@Test
void testWithMockLlm() {
    AgentDriver agent = new AgentDriver(driver);
    MockLlm llm = new MockLlm();

    agent.go("http://localhost:8080/login");

    // LLM loop
    Map<String, Object> state = agent.look();
    String code = llm.generate("login with test credentials", state);

    // Execute LLM-generated code
    ScriptEngine engine = new ScriptEngineManager().getEngineByName("js");
    engine.put("agent", agent);
    engine.eval(code);

    // Verify
    state = agent.look();
    assertTrue(state.get("url").toString().contains("dashboard"));
}
```

---

## Future Enhancements

- [ ] **Streaming mode** - Stream actions as they're performed
- [ ] **Screenshot on error** - Include base64 screenshot when action fails
- [ ] **History** - Track action history for debugging
- [ ] **Undo** - Support back() after navigation actions
- [ ] **Batch actions** - `agent.batch([{act: 'e1', op: 'input', value: 'x'}, ...])`
- [ ] **Assertions** - `agent.assert('e1', 'text', 'expected')` for validation
