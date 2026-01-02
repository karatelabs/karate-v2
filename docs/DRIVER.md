# Browser Driver Design

> **Internal implementation document** for browser automation in karate-v2.
> Prerequisite: [WEBSOCKET.md](./WEBSOCKET.md) (WebSocket infrastructure)

## Progress Tracking

| Phase | Description | Status |
|-------|-------------|--------|
| **1-8** | CDP Driver (WebSocket + launch + elements + frames + intercept) | ✅ Complete |
| **9a** | Gherkin/DSL Integration | ✅ Complete |
| **9b** | Gherkin E2E Tests | ✅ Complete |
| **9c** | PooledDriverProvider (browser reuse) | ✅ Complete |
| **10** | Playwright Backend | ⬜ Not started |
| **11** | WebDriver Backend (Legacy) | ⬜ Not started |
| **12** | WebDriver BiDi (Future) | ⬜ Not started |
| **13** | Cloud Provider Integration | ⬜ Not started |

**Deferred:** Capabilities query API, Video recording (→ commercial app), karate-robot

---

## Package Structure

```
io.karatelabs.driver/
├── Driver, Element, Locators, Finder    # Backend-agnostic API
├── DriverOptions, DriverException       # Configuration and errors
├── Mouse, Keys, Dialog                   # Input interfaces
├── DialogHandler, InterceptHandler       # Functional interfaces
├── InterceptRequest, InterceptResponse   # Data classes
├── DriverProvider, PooledDriverProvider  # Lifecycle management
├── PageLoadStrategy                      # Enum
└── cdp/                                  # CDP implementation
    ├── CdpDriver, CdpMouse, CdpKeys, CdpDialog
    ├── CdpClient, CdpMessage, CdpEvent, CdpResponse
    └── CdpInspector, CdpLauncher, CdpDriverOptions
```

---

## V1 Compatibility

| Aspect | V2 Approach |
|--------|-------------|
| Gherkin DSL | **Drop-in compatible** - same keywords (`click()`, `html()`, etc.) |
| Java API | **Clean break** - redesigned, not constrained by v1 quirks |
| `Target` interface | Replaced by `DriverProvider` (simpler, more flexible) |
| `@AutoDef`, Plugin | Removed |
| `getDialogText()` polling | Replaced by `onDialog(handler)` callback |

**Gherkin Syntax (unchanged from v1):**
```gherkin
* configure driver = { type: 'chrome', headless: true }
* driver serverUrl + '/login'
* input('#username', 'admin')
* click('button[type=submit]')
* waitFor('#dashboard')
* match driver.title == 'Welcome'
```

### V1 API Compatibility Table

*Navigation:*
| V1 Gherkin | V2 Status |
|------------|-----------|
| `driver 'url'` | ✅ Working |
| `driver.url` | ✅ Working |
| `driver.title` | ✅ Working |
| `refresh()` | ✅ Working |
| `reload()` | ✅ Working |
| `back()` | ✅ Working |
| `forward()` | ✅ Working |

*Element Actions:*
| V1 Gherkin | V2 Status |
|------------|-----------|
| `click(locator)` | ✅ Working |
| `input(locator, value)` | ✅ Working |
| `input(locator, ['a', Key.ENTER])` | ✅ Working |
| `focus(locator)` | ✅ Working |
| `clear(locator)` | ✅ Working |
| `value(locator, value)` | ✅ Working |
| `select(locator, text)` | ✅ Working |
| `scroll(locator)` | ✅ Working |
| `highlight(locator)` | ✅ Working |

*Element State:*
| V1 Gherkin | V2 Status |
|------------|-----------|
| `html(locator)` | ✅ Working |
| `text(locator)` | ✅ Working |
| `value(locator)` | ✅ Working |
| `attribute(locator, name)` | ✅ Working |
| `enabled(locator)` | ✅ Working |
| `exists(locator)` | ✅ Working |
| `position(locator)` | ✅ Working |

*Wait Methods:*
| V1 Gherkin | V2 Status |
|------------|-----------|
| `waitFor(locator)` | ✅ Working |
| `waitForAny(loc1, loc2)` | ✅ Working |
| `waitForUrl('path')` | ✅ Working |
| `waitForText(loc, text)` | ✅ Working |
| `waitForEnabled(loc)` | ✅ Working |
| `waitForResultCount(loc, n)` | ✅ Working |
| `waitUntil('js')` | ✅ Working |
| `waitUntil(loc, 'js')` | ✅ Working |

*Frames/Dialogs/Cookies:*
| V1 Gherkin | V2 Status |
|------------|-----------|
| `switchFrame(index)` | ✅ Working |
| `switchFrame(locator)` | ✅ Working |
| `switchFrame(null)` | ✅ Working |
| `dialog(accept)` | ✅ Working |
| `cookie(name)` | ✅ Working |
| `clearCookies()` | ✅ Working |

*Mouse/Keys:*
| V1 Gherkin | V2 Status |
|------------|-----------|
| `mouse()` | ✅ Working |
| `mouse(locator)` | ✅ Working |
| `keys()` | ✅ Working |
| `Key.ENTER`, `Key.TAB` | ✅ Working |

---

## Driver Interface

### Navigation
```java
void setUrl(String url)                          // Navigate and wait
String getUrl()                                  // Get current URL
String getTitle()                                // Get page title
void waitForPageLoad(PageLoadStrategy strategy)  // Wait for load
void waitForPageLoad(PageLoadStrategy, Duration) // With timeout
void refresh()                                   // Soft reload
void reload()                                    // Hard reload
void back()                                      // Navigate back
void forward()                                   // Navigate forward
```

### JavaScript Evaluation
```java
Object script(String expression)                 // Execute JS
Object script(String locator, String expression) // JS on element (_ = element)
List<Object> scriptAll(String locator, String expression)
```

### Screenshot
```java
byte[] screenshot()                              // PNG bytes
byte[] screenshot(boolean embed)                 // Optional embed in report
```

### Dialog Handling
```java
void onDialog(DialogHandler handler)             // Register callback
String getDialogText()                           // Get message
void dialog(boolean accept)                      // Accept/dismiss
void dialog(boolean accept, String input)        // With prompt input
```

### Frame Switching
```java
void switchFrame(int index)                      // By index
void switchFrame(String locator)                 // By locator (null = main)
Map<String, Object> getCurrentFrame()            // Get frame info
```

### Element Operations
```java
Element click(String locator)
Element focus(String locator)
Element clear(String locator)
Element input(String locator, String value)
Element value(String locator, String value)      // Set value
Element select(String locator, String text)
Element select(String locator, int index)
Element scroll(String locator)
Element highlight(String locator)
```

**Select Matching Behavior:**

| Syntax | Behavior |
|--------|----------|
| `select(loc, 'us')` | Match by value first, then fall back to text |
| `select(loc, 'United States')` | Falls back to text match if value not found |
| `select(loc, '{}United States')` | Match by exact text only |
| `select(loc, '{^}Unit')` | Match by text contains |
| `select(loc, 1)` | Match by index (0-based) |

Events dispatched: `input` then `change`, both with `{bubbles: true}` for React/Vue compatibility.

### Element State
```java
String text(String locator)
String html(String locator)
String value(String locator)                     // Get value
String attribute(String locator, String name)
Object property(String locator, String name)
boolean enabled(String locator)
boolean exists(String locator)
Map<String, Object> position(String locator)
Map<String, Object> position(String locator, boolean relative)
```

### Locators
```java
Element locate(String locator)
List<Element> locateAll(String locator)
Element optional(String locator)                 // No throw if missing
```

### Wait Methods
```java
Element waitFor(String locator)
Element waitFor(String locator, Duration timeout)
Element waitForAny(String locator1, String locator2)
Element waitForAny(String[] locators)
Element waitForAny(String[] locators, Duration timeout)
Element waitForText(String locator, String expected)
Element waitForText(String locator, String expected, Duration timeout)
Element waitForEnabled(String locator)
Element waitForEnabled(String locator, Duration timeout)
String waitForUrl(String expected)
String waitForUrl(String expected, Duration timeout)
Element waitUntil(String locator, String expression)
Element waitUntil(String locator, String expression, Duration timeout)
boolean waitUntil(String expression)
boolean waitUntil(String expression, Duration timeout)
Object waitUntil(Supplier<Object> condition)
Object waitUntil(Supplier<Object> condition, Duration timeout)
List<Element> waitForResultCount(String locator, int count)
List<Element> waitForResultCount(String locator, int count, Duration timeout)
```

### Cookies
```java
Map<String, Object> cookie(String name)          // Get cookie
void cookie(Map<String, Object> cookie)          // Set cookie
void deleteCookie(String name)
void clearCookies()
List<Map<String, Object>> getCookies()
```

### Window Management
```java
void maximize()
void minimize()
void fullscreen()
Map<String, Object> getDimensions()
void setDimensions(Map<String, Object> dimensions)
void activate()                                  // Bring to front
```

### PDF Generation
```java
byte[] pdf(Map<String, Object> options)
byte[] pdf()
```

### Mouse and Keyboard
```java
Mouse mouse()                                    // At (0, 0)
Mouse mouse(String locator)                      // At element center
Mouse mouse(Number x, Number y)                  // At coordinates
Keys keys()
```

**Keyboard Implementation Notes (CdpKeys):**
- Uses v1-compatible 3-event sequence: `rawKeyDown` → `char` → `keyUp`
- Enter key sends `text: "\r"` (required for form submission)
- Punctuation uses proper `windowsVirtualKeyCode` (e.g., `.` = 190, `,` = 188)
- Special keys (Tab, Enter, Backspace) handled separately from printable chars

### Pages/Tabs
```java
List<String> getPages()
void switchPage(String titleOrUrl)
void switchPage(int index)
```

### Positional Locators
```java
Finder rightOf(String locator)
Finder leftOf(String locator)
Finder above(String locator)
Finder below(String locator)
Finder near(String locator)
```

### Request Interception
```java
void intercept(List<String> patterns, InterceptHandler handler)
void intercept(InterceptHandler handler)         // All requests
void stopIntercept()
```

### Lifecycle
```java
void quit()
void close()                                     // Alias for quit()
boolean isTerminated()
DriverOptions getOptions()
```

---

## JS API

### Binding: `karate.driver(config)`

The driver is exposed to JavaScript through `karate.driver(config)`:

```javascript
// Create driver instance
var driver = karate.driver({ type: 'chrome', headless: true })

// Navigate
driver.setUrl('http://localhost:8080/login')

// Interact
driver.input('#username', 'admin')
driver.click('button[type=submit]')
driver.waitFor('#dashboard')

// Read state
var title = driver.title      // Property access via ObjectLike
var url = driver.url
var cookies = driver.cookies

// Cleanup
driver.quit()
```

### ObjectLike Property Access

Driver implements `ObjectLike` for JS property access:

```java
default Object get(String name) {
    return switch (name) {
        case "url" -> getUrl();
        case "title" -> getTitle();
        case "cookies" -> getCookies();
        default -> null;
    };
}
```

**Accessible properties:**
- `driver.url` → `getUrl()`
- `driver.title` → `getTitle()`
- `driver.cookies` → `getCookies()`

### Gherkin Keyword Mapping

| Gherkin | JS Equivalent |
|---------|---------------|
| `* driver 'url'` | `driver.setUrl('url')` |
| `* click('#id')` | `driver.click('#id')` |
| `* input('#id', 'val')` | `driver.input('#id', 'val')` |
| `* waitFor('#id')` | `driver.waitFor('#id')` |
| `* match driver.title == 'x'` | `driver.title` |

### Root Bindings

Driver methods are bound as globals for Gherkin compatibility:

```java
engine.putRootBinding("click", (ctx, args) -> driver.click(args[0].toString()));
engine.putRootBinding("input", (ctx, args) -> driver.input(args[0].toString(), args[1].toString()));
engine.putRootBinding("Key", new JavaType(Keys.class));
// ... all driver methods
```

**Hidden from `getAllVariables()`** - keeps reports clean.

---

## Wait System

### Philosophy: Auto-Wait by Default

V2 waits for actionability before actions (Playwright-style):
- **Visible** - not `display:none` or `visibility:hidden`
- **Enabled** - not `disabled` attribute
- **Stable** - not animating
- **Receives pointer events** - not covered by other elements

### Override with Explicit Waits

```gherkin
# Default: auto-wait before click
* click('#button')

# Explicit: custom wait before action
* waitFor('#button').click()
* waitForEnabled('#button').click()

# Extended timeout
* retry(5, 10000).click('#button')
```

### Retry Configuration

Fixed interval polling (v1-style):

```java
driver.timeout(Duration.ofSeconds(30))     // Default timeout
driver.retryInterval(Duration.ofMillis(500)) // Poll interval
```

### Wait Methods

| Method | Behavior |
|--------|----------|
| `waitFor(locator)` | Wait until element exists |
| `waitForAny(locators)` | Wait for any match |
| `waitForText(loc, text)` | Wait for text content |
| `waitForEnabled(loc)` | Wait until not disabled |
| `waitForUrl(substring)` | Wait for URL to contain |
| `waitUntil(expression)` | Wait for JS truthy |
| `waitUntil(loc, expr)` | Wait for element + JS |
| `waitForResultCount(loc, n)` | Wait for element count |

### JS Syntax for waitUntil

Arrow function syntax in JS API (not underscore shorthand):

```javascript
// JS API - arrow function
driver.waitUntil('#btn', el => !el.disabled)
driver.waitUntil('#btn', el => el.textContent.includes('Ready'))

// Gherkin - underscore shorthand (v1 compat)
* waitUntil('#btn', '!_.disabled')
```

---

## LLM Automation (ariaTree)

### Purpose

Provide a compact representation of the page for LLM-based automation agents.

### `ariaTree()` Method

Returns YAML representation of the ARIA accessibility tree:

```yaml
- navigation:
  - list:
    - listitem:
      - link "Home" [ref=e1]
    - listitem:
      - link "About" [ref=e2]
- form:
  - textbox "Email" [ref=e3]
  - textbox "Password" [ref=e4]
  - button "Sign In" [ref=e5]
```

### Ref-Based Locators

```javascript
// Use refs from ariaTree output
driver.click('ref:e5')
driver.input('ref:e3', 'user@example.com')
```

**Ref format:** Simple numeric: `e1`, `e2`, `e3`, ...

### Ref Lifecycle

- Refs are generated fresh per `ariaTree()` call
- Refs are invalidated when DOM changes
- LLM must call `ariaTree()` after actions that mutate DOM
- Stale ref throws: `"ref:e5 is stale, call ariaTree() to refresh"`

### One-Shot Workflow Pattern

```gherkin
Feature: LLM Browser Automation

Scenario: Login with LLM agent
  * driver 'https://example.com/login'
  * def tree = driver.ariaTree()
  * def context = { url: driver.url, title: driver.title, tree: tree }
  * def code = llm.generateCode('Log in with admin/password', context)
  * eval code
  * match driver.url contains 'dashboard'
```

### Implementation

CDP-only for now using browser-neutral JS injection:

```java
public String ariaTree() {
    ensureAriaScriptInjected();
    return (String) delegate.script("window.__karate.getAriaTree()");
}
```

Injected script uses standard DOM APIs:
- `element.getAttribute('aria-label')`
- `element.role` or computed role
- `getComputedStyle()` for visibility
- `element.getBoundingClientRect()` for interactability

### Agent API

For a token-efficient LLM API with HATEOAS-style responses, see **[DRIVER_AGENT.md](./DRIVER_AGENT.md)**.

The Agent API provides just 5 methods (`agent.look()`, `agent.act()`, `agent.go()`, `agent.wait()`, `agent.eval()`) with each response including available next actions - reducing system prompt size from 500+ tokens to ~60.

---

## DriverProvider (Browser Reuse)

### Interface

```java
public interface DriverProvider {
    Driver acquire(ScenarioRuntime runtime, Map<String, Object> config);
    void release(ScenarioRuntime runtime, Driver driver);
    void shutdown();
}
```

### PooledDriverProvider

Built-in implementation for parallel execution:

```java
Runner.path("features/")
    .driverProvider(new PooledDriverProvider())
    .parallel(4);  // Creates pool of 4 drivers
```

**Features:**
- Pool size auto-detected from `Runner.parallel(N)`
- Works correctly with virtual threads
- Resets state between scenarios (`about:blank`, clear cookies)

### Custom Provider (Testcontainers)

```java
public class ContainerDriverProvider extends PooledDriverProvider {
    private final ChromeContainer container;

    public ContainerDriverProvider(ChromeContainer container) {
        super();
        this.container = container;
    }

    @Override
    protected Driver createDriver(Map<String, Object> config) {
        return CdpDriver.connect(container.getCdpUrl(), CdpDriverOptions.fromMap(config));
    }
}
```

### Cloud Provider Pattern

See [Cloud Provider Integration](#cloud-provider-integration) for SauceLabs, BrowserStack examples.

---

## Multi-Backend Architecture

### Backend Selection

`type` implies backend (v1 style):

| Config | Backend |
|--------|---------|
| `type: 'chrome'` | CDP |
| `type: 'playwright'` | Playwright |
| `type: 'chromedriver'` | WebDriver |

### Feature Matrix

| Feature | CDP | Playwright | WebDriver |
|---------|-----|------------|-----------|
| Navigation | ✅ | ✅ | ✅ |
| Element actions | ✅ | ✅ | ✅ |
| Wait methods | ✅ | ✅ | ✅ |
| Screenshots | ✅ | ✅ | ✅ |
| Frames | ✅ Explicit | ✅ Auto | ✅ Explicit |
| Dialogs | ✅ Callback | ✅ Callback | ⚠️ Limited |
| Request interception | ✅ | ✅ | ❌ |
| PDF generation | ✅ | ✅ | ❌ |
| `ariaTree()` | ✅ | ❌ (future) | ❌ |
| Raw protocol access | ✅ `cdp()` | ❌ | ❌ |

### Unsupported Operations

Throw `UnsupportedOperationException` at runtime:

```java
public byte[] pdf() {
    throw new UnsupportedOperationException(
        "PDF generation not supported on WebDriver backend");
}
```

### CDP Backend (Current)

- Custom `CdpClient` WebSocket implementation
- Chrome DevTools Protocol
- Full feature set

### Playwright Backend (Phase 10)

**Architecture:**
- Node subprocess (not Java SDK)
- Custom `PlaywrightClient` for wire protocol
- Goal: better than Playwright Java library

**Frame handling:**
- Auto-frame detection by default
- Explicit `switchFrame()` as fallback for cross-backend tests

**MVP Definition:**
- All Gherkin E2E tests must pass
- All JS API E2E tests must pass

### WebDriver Backend (Phase 11)

- Legacy support, lower priority
- Sync REST/HTTP to chromedriver, geckodriver
- W3C WebDriver spec compliance

### WebDriver BiDi (Phase 12)

- Add when spec matures (2025+)
- Combines WebDriver compatibility with CDP-like streaming
- May be obtained "for free" via Playwright if they adopt BiDi

---

## Cloud Provider Integration

### DriverProvider Pattern

Cloud providers use WebDriver `se:cdp` capability:

```java
public class SauceLabsDriverProvider implements DriverProvider {
    @Override
    public Driver acquire(ScenarioRuntime runtime, Map<String, Object> config) {
        // 1. POST to SauceLabs /session with capabilities
        String sessionUrl = "https://ondemand.saucelabs.com/wd/hub/session";
        Map<String, Object> caps = buildCapabilities(config);
        JsonNode response = httpClient.post(sessionUrl, caps);

        // 2. Extract se:cdp WebSocket URL from response
        String cdpUrl = response.at("/value/capabilities/se:cdp").asText();

        // 3. Return CdpDriver.connect()
        return CdpDriver.connect(cdpUrl, CdpDriverOptions.fromMap(config));
    }

    @Override
    public void release(ScenarioRuntime runtime, Driver driver) {
        reportTestStatus(runtime);  // PUT pass/fail to cloud API
        driver.quit();
    }
}
```

### Provider CDP Support

| Provider | CDP Support | Method |
|----------|-------------|--------|
| SauceLabs | ✅ | `se:cdp` via WebDriver |
| BrowserStack | ✅ | `se:cdp` via WebDriver |
| LambdaTest | ✅ | `se:cdp` via WebDriver |

---

## Phase Implementation Notes

### Phases 1-8 Summary

| Phase | Summary |
|-------|---------|
| **1** | WebSocket client, CDP message protocol |
| **2** | Browser launch, minimal driver |
| **3** | Testcontainers + ChromeContainer + TestPageServer |
| **4** | CdpInspector (screenshots, DOM, console, network) |
| **5** | Locators, Element class, wait methods, v1 bug fixes |
| **6** | Dialog handling (callback), frame switching |
| **7** | Intercept, cookies, window, PDF, Mouse, Keys, Finder |
| **8** | Package restructuring: `Driver` interface + `cdp/` subpackage |

### Phase 9 Notes

**9a: Gherkin/DSL Integration**
- Added `configure driver` support to KarateConfig
- Added `driver` keyword to StepExecutor
- Driver interface extends `ObjectLike` for JS property access
- Bound driver methods as root bindings

**9b: Gherkin E2E Tests**
- `DriverFeatureTest.java` - JUnit runner with Testcontainers
- All v1 features validated in v2

**9c: PooledDriverProvider**
- Replaced ThreadLocalDriverProvider
- Auto-detects pool size from `Runner.parallel(N)`
- Works with virtual threads

### Phase 10 Notes (Playwright - Planned)

**Architecture:**
- Spawn Playwright Node server
- Custom `PlaywrightClient` for WebSocket protocol
- Same `Driver` interface, different implementation

**Goals:**
- Validate multi-backend abstraction works
- All E2E tests pass (Gherkin and JS)
- Same test syntax works on both backends

**Frame handling:**
- Playwright auto-detects frames
- CDP requires explicit `switchFrame()`
- Tests using explicit `switchFrame()` work on both

---

## Test Strategy

### Directory Structure

```
e2e/
├── feature/           # Gherkin E2E tests
│   ├── karate-config.js
│   ├── navigation.feature
│   ├── element.feature
│   ├── cookie.feature
│   ├── mouse.feature
│   ├── keys.feature
│   ├── frame.feature
│   └── dialog.feature
│
└── js/                # JS API E2E tests
    ├── navigation.js
    ├── element.js
    └── ...
```

### Test Suites

| Suite | Purpose |
|-------|---------|
| Gherkin E2E | V1 syntax compatibility, DSL coverage |
| JS API E2E | Pure JS API coverage, LLM workflow validation |

### Backend Compatibility

Both test suites must pass when switching backends:
- Run with `type: 'chrome'` (CDP)
- Run with `type: 'playwright'` (when implemented)

### LLM/ariaTree Tests

- ARIA tree generation
- Ref-based locators
- Stale ref handling
- One-shot workflow pattern

---

## Architecture Decisions

### Decision Log

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Interface name | `Driver` | V1 familiarity |
| CDP-only APIs | Graceful degradation | Returns null/no-op on WebDriver |
| Async events | Separate `CdpInspector` | Driver stays pure sync |
| Error handling | Always verbose | AI-agent friendly |
| Docker | Testcontainers + `chromedp/headless-shell` | ~200MB, fast |
| Wait model | Auto-wait + override | Playwright-style, v1 compat |
| Retry | Fixed interval | Simple, predictable |
| JS API | Unified flat | All methods on driver object |
| Backend selection | `type` implies backend | v1 style, familiar |
| Cloud providers | Extension points only | DriverProvider pattern |
| ariaTree impl | CDP-only initially | Simpler scope |

### Timeout Taxonomy

```java
driver.sessionTimeout(Duration)   // Overall session
driver.pageLoadTimeout(Duration)  // Navigation
driver.elementTimeout(Duration)   // Element waits
driver.scriptTimeout(Duration)    // JS execution
```

### Error Philosophy

All errors include:
- Selector used
- Page URL
- Timing information
- Suggested fixes

AI-agent friendly: detailed context aids debugging.

---

## Deferred Features

### Commercial JavaFX Application
- Real-time browser visualization (CDP Screencast)
- LLM agent debugging integration
- Record & replay, API test derivation
- Network/console viewers

### karate-robot
- Cross-platform desktop automation
- Coordinate transform from viewport to screen-absolute
- Computer-use agent scenarios

### Video Recording
- CDP `Page.startScreencast` streams frames
- Stitch with ffmpeg for mp4 output
- Add to `CdpInspector` when needed
