# Browser Driver Design

> **This is the primary design document** for browser automation in karate-v2.
> Prerequisite: [WEBSOCKET.md](./WEBSOCKET.md) (WebSocket infrastructure)

## Progress Tracking

| Phase | Description | Status |
|-------|-------------|--------|
| **1** | WebSocket + CDP Client | ✅ Complete |
| **2** | Browser Launch + Minimal Driver | ✅ Complete |
| **3** | Testcontainers + E2E Infrastructure | ✅ Complete |
| **4** | Visibility Layer (CdpInspector) | ✅ Complete |
| **5** | Element Operations | ✅ Complete |
| **6** | Frame & Dialog Support | ✅ Complete |
| **7** | Advanced Features | ✅ Complete |
| **8** | Package Restructuring + Driver Interface | ✅ Complete |
| **9** | Gherkin/DSL Integration | 🟡 In progress |
| **9b** | Gherkin E2E Tests (mirror Java E2E) | 🟡 In progress |
| **9c** | Test Optimization (browser reuse) | ⬜ Not started |
| **10** | Playwright Backend | ⬜ Not started |
| **11** | WebDriver Backend (Legacy) | ⬜ Not started |
| **12** | WebDriver BiDi (Future) | ⬜ Not started |

**Legend:** ⬜ Not started | 🟡 In progress | ✅ Complete

**Phase Strategy:**
- Phases 1-7 complete CDP driver to full v1 parity
- Phase 8 restructures packages (`driver/cdp/`) and extracts `Driver` interface
- Phase 9 integrates driver with Gherkin DSL (v1 syntax) and adds modern JS API
- Phase 9b creates Gherkin E2E tests mirroring Java E2E structure (reuse test infra)
- Phase 9c optimizes tests for browser reuse (speed improvement)
- Phase 10 adds Playwright backend (validates multi-backend abstraction)
- Phase 11 adds WebDriver (legacy, lower priority)
- Phase 12 adds BiDi when spec matures

**Deferred Features:**
- Capabilities query API (`driver.supports(Capability.X)`)
- Video recording (→ commercial JavaFX app)
- karate-robot (cross-platform desktop automation)

---

## V2 Architecture Decisions (Interview Summary)

This section captures architectural decisions from detailed design interviews.

### Unified Driver Interface

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Interface name | `Driver` | V1 familiarity, established Karate terminology |
| CDP-only APIs (intercept, screencast) | Base interface with graceful degradation | Methods return null/no-op on WebDriver. Simple API, cross-browser portable |
| Strict mode | Configurable + logging | Log warnings by default, `driver.strict(true)` throws on unsupported ops |
| Async events | Separate Inspector class | `CdpInspector` handles async. Driver stays pure sync. Current v2 approach |
| Inspector binding | Always tied to Driver | `new CdpInspector(driver)`. Lifecycle managed together |

### V1 Compatibility Strategy

| Aspect | Approach |
|--------|----------|
| Gherkin/DSL | **Drop-in compatible** - users see same keywords (`click()`, `html()`, etc.) |
| StepExecutor | Handles translation - internal Driver interface can differ |
| Java API | **Clean break** - redesigned, not constrained by v1 quirks |
| Feature-file mocks | **Both modes** - feature-file mocks for Karate users, callback for Java API |
| Entry points | **Parallel** - both DSL and Java API as first-class citizens |

### Timeout Taxonomy

Unified at Driver level with specific timeout types:
```java
driver.sessionTimeout(Duration)   // Overall session timeout
driver.connectTimeout(Duration)   // Connection establishment
driver.pageLoadTimeout(Duration)  // Page navigation
driver.elementTimeout(Duration)   // Element wait operations
driver.scriptTimeout(Duration)    // JavaScript execution
```

### Error Handling

| Aspect | Decision |
|--------|----------|
| Verbosity | **Always verbose** - include selector, page URL, timing, nearby elements |
| Rationale | AI-agent friendly. Detailed context aids debugging |
| Exception type | `DriverException` with programmatic access to context |

### AI Agent Integration

**API Design for AI Agents (Claude Code, etc.):**

```java
// Tiered API: summary + drill-down
inspector.getSnapshot()           // Light summary
inspector.getElementDetails(sel)  // Deep dive on specific element

// AI-optimized views
inspector.getAllClickables()      // Returns List<Element> with coords
inspector.getAllInputFields()     // Returns List<Element> with coords
inspector.getInteractableElements() // Combined view
inspector.getErrorState()         // Current errors/warnings
```

**Element Objects:**
- Return full `Element` objects (not DTOs)
- Implement `SimpleObject` interface for Karate JS engine integration
- Seamless flow between DOM and Karate in both directions
- `element.click()` works directly

**Coordinate System:**
- Viewport-relative by default
- Suitable for CDP `Input.dispatchMouseEvent`
- Desktop (karate-robot) handles screen-absolute transform

**Tool Use Abstraction:**
- AI agents access via constrained tools, not direct API
- Tools: `click_element`, `get_page_state`, `fill_input`, etc.
- Auditable, controllable actions

### JavaScript API Vision

**Three Layers:**
1. **Browser-side JS** - Enhanced helpers injected into page via `script()`
2. **Karate-side JS** - GraalJS scripts that orchestrate Driver methods
3. **Interactive REPL** - Live browser inspection for authoring/debugging

**Design Principles:**
- Avoid writing raw DOM JS - Locators generates and injects it
- V2 allows tests in pure JS (not just Gherkin)
- Simpler than async/await - sync waits with retry

### Wait Model Enhancements

**Observe Mode (V2 Launch):**
```java
// MutationObserver-based waits
driver.waitFor("#element", WaitOptions.observe())  // Waits for DOM stability
element.waitUntil("_.textContent.length > 0", WaitOptions.observe())
```

**Promise Support (V2 Launch):**
- Even without full JS engine Promise support
- Callback pattern: `driver.waitUntil(() -> condition, callback)`
- Enables more reactive patterns

### Multi-Backend Architecture

**Phase 8: Driver Interface + Playwright Backend**
- Extract `Driver` interface from `CdpDriver`
- Add `PlaywrightDriver` using Playwright wire protocol (WebSocket + JSON)
- Validates abstraction works across backends
- Same tests run with config switch: `Driver.builder().backend(Backend.PLAYWRIGHT).build()`
- Docker: `mcr.microsoft.com/playwright` or `browserless/chrome`

**Phase 9: WebDriver Backend (Legacy)**
- Lower priority - WebDriver is legacy, BiDi is the future
- Reuse Karate HTTP client for consistency
- Sync REST/HTTP to chromedriver, geckodriver, safaridriver, msedgedriver
- W3C WebDriver spec compliance

**Phase 10: WebDriver BiDi (Future)**
- BiDi spec still evolving
- Add when spec matures (2025+)
- Combines WebDriver compatibility with CDP-like event streaming

### Wait/Retry Model Alignment

**Design Goal:** Same test syntax works across all backends.

**Karate v1 Model (Explicit Wait/Retry):**
```java
click("#id")                      // Immediate - fails fast if not found
retry().click("#id")              // Opt-in to retry behavior
retry(5, 10000).click("#id")      // Custom retry count and interval
waitFor("#id").click()            // Explicit wait, then action
waitForText("#id", "hello")       // Wait for text content
```

**Playwright Model (Auto-Wait):**
- Actions auto-wait by default (visible, stable, enabled)
- Built-in actionability checks before each action
- Configurable timeouts per action

**Alignment Strategy:**
| Karate API | CDP Backend | Playwright Backend |
|------------|-------------|-------------------|
| `click("#id")` | Immediate, fail fast | Leverage Playwright auto-wait |
| `retry().click("#id")` | Poll until found, then click | Extend Playwright timeout |
| `retry(5, 10000).click("#id")` | Custom retry settings | Map to Playwright timeout |
| `waitFor("#id")` | Poll until exists | `locator.waitFor()` |
| `waitForText("#id", "text")` | Poll until text matches | `locator.filter({hasText})` |
| `waitForEnabled("#id")` | Poll until enabled | `locator.waitFor({state: 'enabled'})` |

**Key Insight:** Playwright's auto-wait is equivalent to wrapping every action in `waitFor()`.
For Karate users who want fast-fail behavior on Playwright, we can add `{ force: true }` option.

**Configuration:**
```java
Driver driver = Driver.builder()
    .backend(Backend.CDP)         // or PLAYWRIGHT, WEBDRIVER
    .autoWait(false)              // Karate v1 behavior: fail fast
    .autoWait(true)               // Playwright behavior: auto-wait
    .timeout(Duration.ofSeconds(30))
    .retryInterval(Duration.ofMillis(500))
    .build();
```

### Browser Provisioning

| V1 | V2 |
|----|-----|
| Target interface (DockerTarget, custom) | **Testcontainers only** |
| Custom provisioners | Simplified - use Testcontainers patterns |

### Commercial JavaFX Application (Deferred)

**Product Type:** Standalone product using karate-core as library

**Primary Use Cases:**
1. **LLM Agent Debugging** - Provide visibility into browser state for AI coding agents (Claude Code, etc.)
2. **Record & Replay** - Capture browser interactions for test authoring
3. **API Test Derivation** - Generate API tests from observed browser network traffic

**Features to Explore:**
- Real-time browser visualization with CDP Screencast
- LLM-based coding agent integration for test authoring/debugging
- Step-through execution with breakpoints
- DOM inspection and element highlighting
- Network request/response viewer
- Console log streaming
- AI-assisted locator suggestions
- Video recording (premium feature)

**API Test Derivation from Browser Interactions:**
- Record network requests during browser sessions via CDP `Network.*` events
- Capture request/response pairs with headers, body, timing
- HAR (HTTP Archive) file generation for portability
- Smart filtering (ignore static assets, focus on API calls)
- Generate Karate API test scenarios from recordings:
  ```gherkin
  # Auto-generated from browser session recording
  Scenario: Create user flow
    Given url 'https://api.example.com'
    And path '/users'
    And request { name: 'John', email: 'john@example.com' }
    When method POST
    Then status 201
  ```
- LLM-assisted refinement (parameterize values, add assertions, handle auth)
- Correlation detection (extract tokens from responses, use in subsequent requests)

**Architecture:**
- CdpInspector as observability foundation (CDP-specific, not available for WebDriver)
- Event-driven updates via CDP subscriptions
- Separation of driver logic from UI concerns
- Tool use abstraction for AI agent access

### karate-robot (Deferred)

**Separate module** for cross-platform desktop automation:
- Mac, Windows, Linux support
- Robot-like capabilities for "browser use" flows
- Coordinate transform from viewport to screen-absolute
- Powerful for computer-use agent scenarios

### Phase 3 Notes

**Completed:**
- Added Testcontainers dependencies (v1.21.3)
- Created `ChromeContainer` wrapper for `chromedp/headless-shell:latest`
- Created `TestPageServer` using existing HttpServer infrastructure
- Created `DriverTestBase` abstract class for E2E tests
- Created test HTML pages (index, navigation, wait, input, iframe)
- Basic E2E tests passing: script execution, object return, screenshot

**Known Issues (Resolved):**
- Docker 29.x requires `api.version=1.44` workaround (see [testcontainers-java#11212](https://github.com/testcontainers/testcontainers-java/issues/11212))
- ~~Page navigation (`setUrl()`) times out~~ - Fixed: page load event detection working correctly
- The chromedp/headless-shell image starts with no page targets; ChromeContainer creates one via `/json/new`

### Phase 4 Notes

**Completed:**
- Fixed container-to-host networking using `Testcontainers.exposeHostPorts()` with fixed port (18080)
- Page load event detection verified working for all navigation tests
- Implemented `CdpInspector` class for full observability:
  - Screenshot capture (PNG, JPEG, WebP formats)
  - DOM queries (getOuterHtml, getInnerHtml, getText, getAttributes, querySelectorAll)
  - Console message capture and streaming
  - Network request/response event handlers
  - Page load event handlers
  - Error/exception handlers
  - Debug snapshot (url, title, console, screenshot)
- Added 17 E2E tests for CdpInspector
- Added 5 navigation E2E tests using setUrl()
- All 25 E2E tests passing

### Phase 5 Notes

**Completed:**
- Created `Locators` class with full selector transformation:
  - CSS selectors, XPath, wildcard locators (`{div}text`, `{^div}partial`, `{tag:2}text`)
  - Proper quote escaping (fixed v1 bugs with single/double quotes)
  - JS string escaping for special characters
  - Script execution helpers (`scriptSelector`, `scriptAllSelector`)
  - UI helper functions (`highlight`, `optionSelector`, `getPositionJs`, etc.)
- Created `Element` class abstraction:
  - Wrapper around DOM elements with fluent API
  - Action methods: `click()`, `input()`, `clear()`, `focus()`, `scroll()`, `highlight()`
  - State methods: `text()`, `html()`, `value()`, `attribute()`, `property()`, `enabled()`
  - Wait methods: `waitFor()`, `waitForText()`, `waitForEnabled()`, `waitUntil()`
- Added element operations to `CdpDriver`:
  - Full element operations: `click`, `input`, `clear`, `focus`, `scroll`, `highlight`, `select`, `value`
  - Element state: `text`, `html`, `value`, `attribute`, `property`, `enabled`, `exists`, `position`
  - Locator methods: `locate`, `locateAll`, `optional`
  - Script on elements: `script(locator, expression)`, `scriptAll(locator, expression)`
- Added wait methods to `CdpDriver`:
  - `waitFor`, `waitForAny`, `waitForText`, `waitForEnabled`, `waitForUrl`
  - `waitUntil(locator, expression)`, `waitUntil(expression)`, `waitUntil(supplier)`
  - `waitForResultCount(locator, count)`
- Created `DriverException` for driver-specific errors
- 72 unit tests for Locators class
- 37 E2E tests for element operations
- All 182 driver tests passing

**Bug Fixes from v1:**
1. XPath quote escaping - v1 breaks on `{div}It's working`
2. JS string escaping for quotes/backslashes/newlines
3. getPositionJs dimension bug (v1 incorrectly adds scroll offset to width/height)
4. toFunction empty string handling (returns identity function)
5. Input validation (early failure with clear messages)

### Phase 6 Notes

**Completed:**
- Created `DialogHandler` functional interface for dialog callbacks
- Created `Dialog` class with accept/dismiss methods
- Added dialog handling to `CdpDriver`:
  - `onDialog(handler)` - register callback for dialogs
  - `dialog(accept)`, `dialog(accept, input)` - manual dialog control
  - `getDialogText()` - get current dialog message
  - Event handler for `Page.javascriptDialogOpening`
- Added frame tracking infrastructure:
  - `Frame` inner class with id, url, name
  - `frameContexts` map - Maps frameId to execution context ID
  - `Runtime.executionContextCreated` event handler
  - Modified `eval()` to use frame context
- Implemented `switchFrame()` methods:
  - `switchFrame(int index)` - by frame tree index
  - `switchFrame(String locator)` - by CSS/XPath locator
  - `switchFrame(null)` - return to main frame
  - `getCurrentFrame()` - get current frame info
  - `ensureFrameContext()` - create isolated world if needed
- Created `dialog.html` test page with alert, confirm, prompt buttons
- Created `DialogE2ETest` (10 tests):
  - Alert with handler, Confirm accept/dismiss, Prompt accept/dismiss/default/empty
- Created `FrameE2ETest` (19 tests):
  - Main frame content/variables/buttons
  - Switch by index and by locator
  - Invalid frame handling
  - Frame operations (click, script, element state)
  - Multiple frame switches
- All 211 driver tests passing

**Design Notes:**
- Same-origin iframes work via `Page.getFrameTree` and `Page.createIsolatedWorld`
- Cross-origin iframes (Phase 7) will require `Target.attachToTarget` for separate sessions
- Dialog handler auto-dismisses if user doesn't call accept/dismiss
- Frame contexts are tracked via `Runtime.executionContextCreated` events

### Phase 7 Notes

**Completed:**
- Implemented request interception with callback-based API:
  - `InterceptHandler` functional interface for handling requests
  - `InterceptRequest` class with url, method, headers, postData, resourceType
  - `InterceptResponse` class with factory methods (json, html, text, status, redirect)
  - `intercept(patterns, handler)` and `intercept(handler)` methods
  - `stopIntercept()` to disable interception
  - Uses CDP `Fetch.enable`, `Fetch.requestPaused`, `Fetch.fulfillRequest`, `Fetch.continueRequest`
- Implemented cookie management:
  - `cookie(name)` - get single cookie by name
  - `cookie(map)` - set cookie with properties
  - `deleteCookie(name)` - delete cookie by name
  - `clearCookies()` - clear all cookies
  - `getCookies()` - get all cookies as list
  - Uses CDP `Network.getCookies`, `Network.setCookie`, `Network.deleteCookies`, `Network.clearBrowserCookies`
- Implemented window management:
  - `maximize()`, `minimize()`, `fullscreen()` - window state control
  - `getDimensions()` - get window bounds (x, y, width, height, windowState)
  - `setDimensions(map)` - set window bounds
  - Uses CDP `Browser.getWindowForTarget`, `Browser.setWindowBounds`, `Browser.getWindowBounds`
- Implemented PDF generation:
  - `pdf()` and `pdf(options)` - generate PDF with optional settings
  - Options: scale, displayHeaderFooter, headerTemplate, footerTemplate, printBackground, landscape, pageRanges, paperWidth, paperHeight, margins
  - Uses CDP `Page.printToPDF`
- Implemented navigation extras:
  - `refresh()` - reload page
  - `reload()` - reload ignoring cache
  - `back()` - navigate back in history
  - `forward()` - navigate forward in history
  - `activate()` - bring window to front
- Implemented Mouse class:
  - `mouse()`, `mouse(locator)`, `mouse(x, y)` - create Mouse at position
  - `move(x, y)`, `offset(dx, dy)` - movement
  - `click()`, `doubleClick()`, `rightClick()` - click actions
  - `down()`, `up()` - mouse button control
  - `wheel(deltaX, deltaY)`, `scrollDown/Up/Left/Right(amount)` - scrolling
  - `dragTo(x, y)` - drag operation
  - Uses CDP `Input.dispatchMouseEvent`
- Implemented Keys class:
  - `keys()` - create Keys object
  - `type(text)` - type character sequence
  - `press(key)` - press and release key
  - `down(key)`, `up(key)` - modifier control
  - `combo(modifiers, key)` - key combinations
  - `ctrl(key)`, `alt(key)`, `shift(key)`, `meta(key)` - modifier shortcuts
  - Key constants: ENTER, TAB, BACKSPACE, ESCAPE, arrows, F-keys, etc.
  - Uses CDP `Input.dispatchKeyEvent`
- Implemented pages/tabs management:
  - `getPages()` - list all page targets
  - `switchPage(titleOrUrl)` - switch by title or URL match
  - `switchPage(index)` - switch by index
  - Session management via CDP `Target.getTargets`, `Target.activateTarget`, `Target.attachToTarget`
- Implemented positional locators:
  - `Finder` class for relative element location
  - `rightOf(locator)`, `leftOf(locator)`, `above(locator)`, `below(locator)`, `near(locator)`
  - `finder.find(locator)` - find first matching element
  - `finder.findAll(locator)` - find all matching elements sorted by distance
  - `finder.within(pixels)` - set tolerance for matching
  - Uses position comparison with tolerance for overlap detection
- Added 30 new E2E tests:
  - `CookieE2eTest` (5 tests)
  - `MouseE2eTest` (9 tests)
  - `KeysE2eTest` (8 tests)
  - `InterceptE2eTest` (8 tests)
- All 241 driver tests passing

**New Classes:**
- `Mouse.java` - CDP-based mouse actions
- `Keys.java` - CDP-based keyboard actions with key constants
- `Finder.java` - positional element finder
- `InterceptHandler.java` - functional interface for request interception
- `InterceptRequest.java` - intercepted request details
- `InterceptResponse.java` - mock response builder

### Phase 8 Notes

**Package Restructuring + Driver Interface**

**Completed:**
- Created `Driver` interface (extracted from CdpDriver)
- Created `DriverOptions` interface for base configuration
- Created `Mouse` interface (with key constants retained)
- Created `Keys` interface (with key constants retained)
- Created `Dialog` interface for dialog handling
- Created `cdp/` subpackage for CDP-specific implementations
- Moved CDP classes to cdp/ subpackage:
  - CdpDriver (implements Driver)
  - CdpDriverOptions (implements DriverOptions)
  - CdpMouse (implements Mouse, renamed from Mouse)
  - CdpKeys (implements Keys, renamed from Keys)
  - CdpDialog (implements Dialog, renamed from Dialog)
  - CdpLauncher (renamed from BrowserLauncher)
  - CdpClient, CdpMessage, CdpEvent, CdpResponse, CdpInspector
- Kept backend-agnostic classes in parent package:
  - InterceptRequest, InterceptResponse (pure data classes)
  - Element, Locators, Finder
  - DialogHandler, InterceptHandler (functional interfaces)
  - DriverException, PageLoadStrategy
- Updated all test imports
- Moved CDP-specific unit tests to `cdp/` test package:
  - CdpMessageTest, CdpResponseTest, CdpEventTest, CdpLauncherTest, CdpDriverOptionsTest
- Kept `LocatorsTest` in parent package (tests backend-agnostic class)
- E2E tests remain in `e2e/` package (test Driver interface behavior)
- All 241 driver tests passing

**Package Structure:**
```
io.karatelabs.driver/
├── Driver.java              # Interface (extracted from CdpDriver)
├── DriverOptions.java       # Base options interface
├── Element.java             # Backend-agnostic
├── Locators.java            # Backend-agnostic JS generation
├── Finder.java              # Positional element finder
├── Mouse.java               # Interface for mouse actions
├── Keys.java                # Interface for keyboard actions
├── Dialog.java              # Interface for dialog handling
├── DriverException.java
├── PageLoadStrategy.java
├── DialogHandler.java
├── InterceptHandler.java
├── InterceptRequest.java    # Data class (backend-agnostic)
├── InterceptResponse.java   # Data class (backend-agnostic)
│
└── cdp/                     # CDP-specific implementation
    ├── CdpDriver.java       # implements Driver
    ├── CdpDriverOptions.java # implements DriverOptions
    ├── CdpClient.java
    ├── CdpMessage.java
    ├── CdpEvent.java
    ├── CdpResponse.java
    ├── CdpInspector.java
    ├── CdpLauncher.java     # renamed from BrowserLauncher
    ├── CdpMouse.java        # implements Mouse
    ├── CdpKeys.java         # implements Keys
    └── CdpDialog.java       # implements Dialog
```

**Classification:**
| Stay in `driver/` | Move to `driver/cdp/` |
|-------------------|----------------------|
| Element.java | CdpDriver.java |
| Locators.java | CdpClient.java |
| Finder.java | CdpMessage.java |
| DriverException.java | CdpEvent.java |
| PageLoadStrategy.java | CdpResponse.java |
| DialogHandler.java | CdpDriverOptions.java |
| InterceptHandler.java | CdpInspector.java |
| InterceptRequest.java | CdpLauncher.java |
| InterceptResponse.java | CdpMouse.java |
| Driver.java (NEW) | CdpKeys.java |
| DriverOptions.java (NEW) | CdpDialog.java |
| Mouse.java (→ interface) | |
| Keys.java (→ interface) | |
| Dialog.java (NEW) | |

### Phase 9 Notes

**Gherkin/DSL Integration**

**Goals:**
1. Add `configure driver` support to KarateConfig
2. Add `driver` keyword to StepExecutor
3. Bind driver to JS engine for method calls
4. Support v1 Gherkin syntax exactly
5. Add modern JS API for `.karate.js` scripts
6. Validate with v1 feature file tests

**Two API Modes:**

1. **Gherkin DSL (v1 compatible)** - For `.feature` files:
```gherkin
* configure driver = { type: 'chrome', headless: true }
* driver serverUrl + '/login'
* input('#username', 'admin')
* click('button[type=submit]')
* waitFor('#dashboard')
* def title = driver.title
```

2. **Modern JS API** - For `.karate.js` scripts:
```javascript
var driver = karate.driver({ type: 'chrome', headless: true })
driver.open('http://localhost:8080/login')
driver.input('#username', 'admin')
driver.click('button[type=submit]')
driver.waitFor('#dashboard')
var title = driver.title
driver.quit()
```

**Implementation Components:**

1. **KarateConfig** - Add `driverConfig` field and configure handler
2. **ScenarioRuntime** - Add `getDriver()` with lazy initialization
3. **KarateJs** - Expose `karate.driver()` factory for JS API
4. **StepExecutor** - Add `driver` keyword handler for Gherkin
5. **Driver interface** - Implement `SimpleObject` for JS property access

**Hidden Variables (Root Bindings):**

Use `engine.putRootBinding()` for driver-related variables that should be accessible in JS but excluded from `getAllVariables()`. This matches V1's `setHiddenVariable()` pattern:

- `driver` - Browser driver instance
- `Key` - Keyboard key constants (ENTER, TAB, ESCAPE, etc.)
- Magic action methods exposed as globals: `click()`, `input()`, `html()`, `waitFor()`, etc.

These will be accessible during Gherkin execution (e.g., `* click('#button')`) but won't pollute the scenario's exported variables. See RUNTIME.md for `putRootBinding()` documentation.

Key features to validate:
- `configure driver = { type: 'chrome' }`
- Navigation: `driver serverUrl + '/path'`
- Actions: `click('#id')`, `input('#id', 'value')`, `clear('#id')`
- Waits: `waitFor('#id')`, `waitForText('#id', 'text')`, `waitForEnabled('#id')`
- Scripts: `script('...')`, `scriptAll('...')`
- Frames: `switchFrame('#iframe')`, `switchFrame(0)`, `switchFrame(null)`
- Cookies: `cookie('name')`, `clearCookies()`
- Chaining: `waitFor('#id').click()`
- Properties: `driver.url`, `driver.title`, `driver.cookies`

**Completed:**
- Added `driverConfig` field to KarateConfig with `configure driver = { ... }` handler
- Added `getDriver()` and `initDriver()` to ScenarioRuntime with lazy initialization
- Added `driver` keyword to StepExecutor for URL navigation (`* driver 'http://...'`)
- Driver interface extends `ObjectLike` for JS property access (`driver.url`, `driver.title`, `driver.cookies`)
- Bound driver action methods as root bindings for V1 compatibility:
  - Element actions: `click`, `input`, `clear`, `focus`, `scroll`, `highlight`, `select`
  - Element state: `text`, `html`, `value`, `attribute`, `exists`, `enabled`, `position`
  - Wait methods: `waitFor`, `waitForText`, `waitForEnabled`, `waitForUrl`, `waitUntil`
  - Locators: `locate`, `locateAll`, `optional`
  - Frame switching: `switchFrame`
  - Scripts: `script`, `scriptAll`
  - Navigation: `refresh`, `back`, `forward`
  - Screenshots: `screenshot`
  - Cookies: `cookie`, `deleteCookie`, `clearCookies`
  - Dialog: `dialog`
  - Mouse/Keys: `mouse`, `keys`
  - Key constants: `Key` class bound for `Key.ENTER`, `Key.TAB`, etc.
- Added driver cleanup in ScenarioRuntime's `call()` finally block
- Added tests for driver configuration in `StepConfigureTest`

**Remaining:**
- `karate.driver()` factory for JS API (Phase 9 stretch goal)
- Gherkin E2E tests (moved to Phase 9b)
- Validation with v1 feature files

### Phase 9b Notes

**Gherkin E2E Tests (mirror Java E2E structure)**

**Goals:**
1. Create Gherkin feature files that mirror the existing Java E2E tests
2. Reuse existing test infrastructure (TestPageServer, test HTML pages)
3. Well-structured test organization matching v2 Java E2E test structure
4. NOT a direct port of v1 tests - fresh structure using v2 patterns

**Completed:**
- Created `DriverFeatureTest.java` - JUnit runner with Testcontainers + TestPageServer
- Created `karate-config.js` for driver features (reads webSocketUrl/serverUrl from system properties)
- Created `navigation.feature` with 3 passing scenarios:
  - Navigate and verify title (`driver url`, `driver.title`)
  - Script execution (`script('1 + 1')`)
  - Get page content via script (`script('window.testValue')`)

**Working v2 Gherkin Syntax:**
```gherkin
* configure driver = driverConfig
* driver serverUrl + '/'
* match driver.title == 'Karate Driver Test'
* def result = script('1 + 1')
```

**Remaining:**
- Research v1 README (`karate/karate-core/README.md`) for complete API coverage
- Create remaining feature files (element, mouse, keys, cookie, frame, dialog)
- Issues to investigate:
  - `Key.TAB` etc. not working (static field access on interface in JS)
  - Dialog callback pattern needs different Gherkin approach
  - Frame switching with null needs driver initialized first

**Test Structure:**
```
karate-core/src/test/resources/io/karatelabs/driver/
├── pages/                    # Existing test HTML pages (reuse)
│   ├── index.html
│   ├── navigation.html
│   ├── wait.html
│   ├── input.html
│   ├── iframe.html
│   └── dialog.html
│
└── features/                 # Gherkin E2E tests
    ├── karate-config.js      # Driver config (reads system properties)
    ├── navigation.feature    # ✅ 3 scenarios passing
    ├── element.feature.skip  # Pending
    ├── mouse.feature.skip    # Pending
    ├── keys.feature.skip     # Pending (Key constants issue)
    ├── cookie.feature.skip   # Pending
    ├── frame.feature.skip    # Pending (switchFrame(null) issue)
    └── dialog.feature.skip   # Pending (callback pattern issue)
```

**Infrastructure Reuse:**
- `TestPageServer` - Reuse for serving test HTML pages
- `ChromeContainer` - Reuse Testcontainers Chrome setup
- `DriverFeatureTest.java` - Gherkin test runner (created)
- Test HTML pages - Reuse existing pages in `pages/` directory

**V1 Reference:** `/Users/peter/dev/zcode/karate/karate-core/README.md`
- Use v1 README as API reference for Gherkin syntax
- v2 tests should be cleaner, better organized, using Testcontainers

### Phase 9c Notes

**Test Optimization (browser reuse)**

**Goals:**
1. Most tests reuse the same browser instance for speed
2. Only tests that require fresh browser state get isolated instances
3. Proper cleanup between tests (cookies, localStorage, navigation)

**Strategy:**
```java
// Shared browser for most tests
@TestInstance(Lifecycle.PER_CLASS)
class SharedBrowserFeatureTest {
    static ChromeContainer chrome;  // Started once for all tests
    static CdpDriver driver;        // Reused across tests

    @BeforeEach
    void resetBrowserState() {
        driver.clearCookies();
        driver.script("localStorage.clear()");
        driver.setUrl("about:blank");
    }
}

// Isolated browser for specific tests (dialogs, intercept, etc.)
@TestInstance(Lifecycle.PER_METHOD)
class IsolatedBrowserFeatureTest {
    // Fresh browser per test
}
```

**Test Categories:**
| Category | Browser Mode | Reason |
|----------|--------------|--------|
| Navigation | Shared | Simple state reset |
| Element | Shared | Simple state reset |
| Mouse/Keys | Shared | Simple state reset |
| Cookies | Shared | Clear between tests |
| Frames | Shared | Navigate away resets |
| Dialogs | Isolated | Dialog handlers persist |
| Intercept | Isolated | Intercept state persists |

**Expected Speedup:**
- Current: ~20s for 241 tests (browser start per test class)
- Target: ~10s (single browser, parallel test classes where safe)

### Future Enhancements (TODO)

**Video Recording via CDP Screencast:**
- CDP has `Page.startScreencast` which streams frames as events - no container changes needed
- Could add to `CdpInspector`:
  ```java
  inspector.startRecording();
  // ... test actions ...
  byte[] video = inspector.stopRecording(); // returns mp4 via ffmpeg
  ```
- Uses `Page.screencastFrame` events with `Page.screencastFrameAck` for flow control
- Frames can be stitched with ffmpeg: `ffmpeg -framerate 2 -i '*.png' -c:v libx264 output.mp4`
- Useful for debugging complex test failures
- Not critical for v2 launch

**JavaFX Commercial Application (TODO: Brainstorm):**
- Build a JavaFX desktop application for visual debugging and orchestration
- Features to explore:
  - Real-time browser visualization with CDP Screencast
  - LLM-based coding agent integration for test authoring/debugging
  - Step-through execution with breakpoints
  - DOM inspection and element highlighting
  - Network request/response viewer
  - Console log streaming
  - AI-assisted locator suggestions
- Architecture considerations:
  - CdpInspector as the observability foundation
  - Event-driven updates via CDP subscriptions
  - Separation of driver logic from UI concerns

---

This document defines the design for browser automation in karate-v2 using Chrome DevTools Protocol (CDP).
The implementation will be in the `io.karatelabs.driver` package, building on the WebSocket infrastructure defined in [WEBSOCKET.md](./WEBSOCKET.md).

## Design Decisions Summary

| Decision | Choice |
|----------|--------|
| Driver interface | Keep v1 API exactly (remove @AutoDef) |
| Wait model | Implicit waits (sync API, CompletableFuture internal) |
| Page ready detection | Default: DOM + frames (v1 style), configurable |
| Timeout behavior | Throw RuntimeException |
| Frame support | Full complexity (cross-origin via Target sessions) |
| Request interception | Both: built-in mock handler AND callback-based |
| Configuration | Hybrid: Builder pattern + Map for v1 compatibility |
| CdpDriver ownership | Owns CdpClient internally |
| Dialog handling | Callback registration: onDialog(handler) |
| Browser process | Separate BrowserLauncher class |
| Raw CDP access | Expose via driver.getCdpClient() |
| Docker container | Testcontainers + chromedp/headless-shell (~200MB) |
| Observability | CdpInspector: screenshots + DOM queries + event stream |

---

## Class Overview

```
io.karatelabs.driver
├── Driver                # Interface (retained from v1)
├── Element               # Element abstraction
├── CdpDriver             # CDP implementation of Driver
├── CdpDriverOptions      # Configuration builder
├── BrowserLauncher       # Chrome process management
├── PageLoadStrategy      # Enum: DOMCONTENT, LOAD, NETWORKIDLE
├── DialogHandler         # Functional interface for dialog callbacks
├── InterceptHandler      # Functional interface for request interception
└── DriverException       # Runtime exception for driver errors

(from io.karatelabs.http - see WEBSOCKET.md)
├── CdpClient             # CDP protocol client
├── CdpMessage            # Fluent message builder
├── CdpResponse           # CDP response wrapper
└── CdpEvent              # CDP event wrapper
```

---

## Driver Interface

Retained from v1 with minor adaptations. The interface defines the full browser automation API.

```java
public interface Driver {

    // Lifecycle
    void activate();
    void close();
    void quit();
    boolean isTerminated();

    // Navigation
    void setUrl(String url);           // blocks until page ready
    String getUrl();
    void refresh();
    void reload();                      // ignore cache
    void back();
    void forward();
    String getTitle();

    // Window management
    void maximize();
    void minimize();
    void fullscreen();
    Map<String, Object> getDimensions();
    void setDimensions(Map<String, Object> map);

    // Element operations
    Element focus(String locator);
    Element clear(String locator);
    Element click(String locator);
    Element input(String locator, String value);
    Element select(String locator, String text);
    Element select(String locator, int index);
    Element value(String locator, String value);
    Element scroll(String locator);
    Element highlight(String locator);

    // Element state
    String text(String locator);
    String html(String locator);
    String value(String locator);
    String attribute(String locator, String name);
    String property(String locator, String name);
    boolean enabled(String locator);
    boolean exists(String locator);
    Map<String, Object> position(String locator);
    Map<String, Object> position(String locator, boolean relative);

    // Locators
    Element locate(String locator);
    List<Element> locateAll(String locator);
    Element optional(String locator);
    Object elementId(String locator);
    List elementIds(String locator);

    // Wait methods
    Element waitFor(String locator);
    Element waitForAny(String locator1, String locator2);
    Element waitForAny(String[] locators);
    Element waitForText(String locator, String expected);
    Element waitForEnabled(String locator);
    String waitForUrl(String expected);
    Element waitUntil(String locator, String expression);
    boolean waitUntil(String expression);
    Object waitUntil(Supplier<Object> condition);
    List<Element> waitForResultCount(String locator, int count);

    // Page load strategy (NEW - v2)
    void waitForPageLoad(PageLoadStrategy strategy);
    void waitForPageLoad(PageLoadStrategy strategy, Duration timeout);

    // Scripts
    Object script(String expression);
    Object script(String locator, String expression);
    List scriptAll(String locator, String expression);

    // Frames
    void switchFrame(int index);
    void switchFrame(String locator);

    // Pages/Tabs
    List<String> getPages();
    void switchPage(String titleOrUrl);
    void switchPage(int index);

    // Dialogs (callback-based in v2)
    void onDialog(DialogHandler handler);
    String getDialogText();
    void dialog(boolean accept);
    void dialog(boolean accept, String input);

    // Cookies
    Map<String, Object> cookie(String name);
    void cookie(Map<String, Object> cookie);
    void deleteCookie(String name);
    void clearCookies();
    List<Map> getCookies();

    // Screenshots
    byte[] screenshot();
    byte[] screenshot(boolean embed);
    byte[] screenshot(String locator);
    byte[] screenshot(String locator, boolean embed);
    byte[] pdf(Map<String, Object> options);

    // Mouse and keyboard
    Mouse mouse();
    Mouse mouse(String locator);
    Mouse mouse(Number x, Number y);
    Keys keys();
    void actions(List<Map<String, Object>> actions);

    // Positional locators
    Finder rightOf(String locator);
    Finder leftOf(String locator);
    Finder above(String locator);
    Finder below(String locator);
    Finder near(String locator);

    // Retry/timeout control
    Driver retry();
    Driver retry(int count);
    Driver retry(Integer count, Integer interval);
    Driver delay(int millis);
    Driver timeout(Integer millis);
    Driver timeout();
    Driver submit();

    // Request interception (v2: both modes)
    void intercept(List<String> patterns, InterceptHandler handler);
    void intercept(Map<String, Object> config);  // v1 mock handler style
    void stopIntercept();

    // Raw CDP access (v2)
    CdpClient getCdpClient();

    // Configuration
    CdpDriverOptions getOptions();
}
```

---

## CdpDriverOptions

Hybrid configuration: Builder pattern for type-safety, plus Map constructor for v1 compatibility.

```java
public class CdpDriverOptions {

    public static Builder builder() {
        return new Builder();
    }

    // V1 compatibility constructor
    public static CdpDriverOptions fromMap(Map<String, Object> options) {
        return new CdpDriverOptions(options);
    }

    public static class Builder {
        private int timeout = 30000;
        private int retryCount = 3;
        private int retryInterval = 500;
        private boolean headless = false;
        private String host = "localhost";
        private int port = 0;                    // 0 = auto-assign
        private String executable;               // chrome path
        private String userDataDir;
        private String userAgent;
        private boolean screenshotOnFailure = true;
        private boolean highlight = false;
        private int highlightDuration = 3000;
        private PageLoadStrategy pageLoadStrategy = PageLoadStrategy.DOMCONTENT_AND_FRAMES;
        private List<String> addOptions;         // extra chrome args
        private String webSocketUrl;             // connect to existing browser
        private Logger logger;

        public Builder timeout(int millis);
        public Builder timeout(Duration duration);
        public Builder retryCount(int count);
        public Builder retryInterval(int millis);
        public Builder headless(boolean headless);
        public Builder host(String host);
        public Builder port(int port);
        public Builder executable(String path);
        public Builder userDataDir(String path);
        public Builder userAgent(String userAgent);
        public Builder screenshotOnFailure(boolean enabled);
        public Builder highlight(boolean enabled);
        public Builder highlightDuration(int millis);
        public Builder pageLoadStrategy(PageLoadStrategy strategy);
        public Builder addOptions(List<String> options);
        public Builder webSocketUrl(String url);  // skip browser launch
        public Builder logger(Logger logger);

        public CdpDriverOptions build();
    }

    // Accessors
    public int getTimeout();
    public int getRetryCount();
    public int getRetryInterval();
    public boolean isHeadless();
    public String getHost();
    public int getPort();
    public String getExecutable();
    public String getUserDataDir();
    public String getUserAgent();
    public boolean isScreenshotOnFailure();
    public boolean isHighlight();
    public int getHighlightDuration();
    public PageLoadStrategy getPageLoadStrategy();
    public List<String> getAddOptions();
    public String getWebSocketUrl();
    public Logger getLogger();
}
```

---

## PageLoadStrategy

```java
public enum PageLoadStrategy {
    DOMCONTENT,            // Wait for DOMContentLoaded only
    DOMCONTENT_AND_FRAMES, // Wait for DOM + all frames stopped loading (v1 default)
    LOAD,                  // Wait for Page.loadEventFired
    NETWORKIDLE            // Wait for no network activity for 500ms
}
```

---

## BrowserLauncher

Separate class for browser process management. Returns WebSocket URL for CdpDriver.

```java
public class BrowserLauncher {

    private static final Set<BrowserLauncher> ACTIVE = ConcurrentHashMap.newKeySet();

    public static void closeAll() {
        ACTIVE.forEach(BrowserLauncher::close);
    }

    // Launch Chrome and return WebSocket debugger URL
    public static BrowserLauncher start(CdpDriverOptions options);

    // Connect to existing browser at host:port, get WebSocket URL
    public static String getWebSocketUrl(String host, int port);

    public String getWebSocketUrl();
    public int getPort();
    public boolean isRunning();

    public void close();
    public void closeAndWait();
}
```

### Chrome Launch Flow

```
1. Find Chrome executable (options.executable or auto-detect)
2. Create user data directory if needed
3. Build command line args:
   - --remote-debugging-port={port}
   - --user-data-dir={dir}
   - --headless=new (if headless)
   - --disable-gpu, --no-sandbox, etc.
   - + addOptions from config
4. Start process
5. Poll http://host:port/json/version until ready
6. Extract webSocketDebuggerUrl from response
```

---

## CdpDriver

Main implementation. Owns CdpClient and manages browser automation state.

```java
public class CdpDriver implements Driver {

    // Factory method - launches browser
    public static CdpDriver start(CdpDriverOptions options) {
        BrowserLauncher launcher = BrowserLauncher.start(options);
        return new CdpDriver(launcher, options);
    }

    // Connect to existing browser
    public static CdpDriver connect(String webSocketUrl) {
        return connect(webSocketUrl, CdpDriverOptions.builder().build());
    }

    public static CdpDriver connect(String webSocketUrl, CdpDriverOptions options) {
        return new CdpDriver(webSocketUrl, options);
    }

    // Internal state
    private final CdpClient cdp;
    private final CdpDriverOptions options;
    private final BrowserLauncher launcher;  // null if connected to existing
    private final String rootFrameId;
    private String mainFrameId;
    private String sessionId;
    private boolean terminated;

    // Page load state
    private volatile boolean domContentEventFired;
    private final Set<String> framesStillLoading = ConcurrentHashMap.newKeySet();

    // Frame management
    private Frame currentFrame;
    private final Map<String, Integer> frameContexts = new ConcurrentHashMap<>();
    private final Map<String, String> frameSessions = new ConcurrentHashMap<>();

    // Dialog handling
    private volatile String currentDialogText;
    private volatile DialogHandler dialogHandler;

    // Request interception
    private InterceptHandler interceptHandler;
    private MockHandler mockHandler;

    // Retry state
    private boolean retryEnabled;
    private Integer retryCount;
    private Integer retryInterval;

    // Implementation details follow...
}
```

---

## Wait Infrastructure (CompletableFuture-based)

The key improvement over v1: replace synchronized/wait/notify with CompletableFuture.

### PageLoadWaiter

Internal class that manages page load waiting.

```java
class PageLoadWaiter {

    private final CdpDriver driver;
    private final PageLoadStrategy strategy;
    private final Duration timeout;
    private CompletableFuture<Void> future;

    PageLoadWaiter(CdpDriver driver, PageLoadStrategy strategy, Duration timeout) {
        this.driver = driver;
        this.strategy = strategy;
        this.timeout = timeout;
    }

    // Start waiting (called before navigation)
    void start() {
        future = new CompletableFuture<>();
        driver.resetPageState();
    }

    // Called by event handlers
    void onDomContentEventFired() {
        driver.domContentEventFired = true;
        checkComplete();
    }

    void onFrameStartedLoading(String frameId) {
        if (frameId.equals(driver.rootFrameId)) {
            driver.domContentEventFired = false;
            driver.framesStillLoading.clear();
        } else {
            driver.framesStillLoading.add(frameId);
        }
    }

    void onFrameStoppedLoading(String frameId) {
        driver.framesStillLoading.remove(frameId);
        checkComplete();
    }

    void onLoadEventFired() {
        if (strategy == PageLoadStrategy.LOAD) {
            future.complete(null);
        }
    }

    void onNetworkIdle() {
        if (strategy == PageLoadStrategy.NETWORKIDLE) {
            future.complete(null);
        }
    }

    private void checkComplete() {
        if (strategy == PageLoadStrategy.DOMCONTENT) {
            if (driver.domContentEventFired) {
                future.complete(null);
            }
        } else if (strategy == PageLoadStrategy.DOMCONTENT_AND_FRAMES) {
            if (driver.domContentEventFired && driver.framesStillLoading.isEmpty()) {
                future.complete(null);
            }
        }
    }

    // Block until complete or timeout
    void await() {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("page load timeout after " + timeout.toMillis() + "ms");
        } catch (Exception e) {
            throw new RuntimeException("page load failed: " + e.getMessage(), e);
        }
    }
}
```

### CDP Response Waiting

For request/response correlation:

```java
// In CdpClient (from WEBSOCKET.md)
public CompletableFuture<CdpResponse> sendAsync(CdpMessage message) {
    CompletableFuture<CdpResponse> future = new CompletableFuture<>();
    pending.put(message.getId(), future);
    ws.send(message.toJson());

    // Timeout handling
    Duration timeout = message.getTimeout() != null
        ? message.getTimeout()
        : defaultTimeout;

    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
}

// Blocking send (used by most Driver methods)
public CdpResponse send(CdpMessage message) {
    try {
        return sendAsync(message).join();
    } catch (CompletionException e) {
        if (e.getCause() instanceof TimeoutException) {
            throw new RuntimeException("CDP timeout for: " + message.getMethod());
        }
        throw new RuntimeException("CDP error: " + e.getMessage(), e.getCause());
    }
}
```

---

## Event Handling

CdpDriver subscribes to CDP events and routes them appropriately.

```java
private void setupEventHandlers() {
    cdp.on("Page.domContentEventFired", this::onDomContentEventFired);
    cdp.on("Page.loadEventFired", this::onLoadEventFired);
    cdp.on("Page.frameStartedLoading", this::onFrameStartedLoading);
    cdp.on("Page.frameStoppedLoading", this::onFrameStoppedLoading);
    cdp.on("Page.frameNavigated", this::onFrameNavigated);
    cdp.on("Page.javascriptDialogOpening", this::onDialogOpening);
    cdp.on("Runtime.executionContextCreated", this::onExecutionContextCreated);
    cdp.on("Runtime.executionContextsCleared", this::onExecutionContextsCleared);
    cdp.on("Target.attachedToTarget", this::onAttachedToTarget);
    cdp.on("Target.targetInfoChanged", this::onTargetInfoChanged);
    cdp.on("Fetch.requestPaused", this::onRequestPaused);
}

private void onDialogOpening(CdpEvent event) {
    currentDialogText = event.get("message");
    if (dialogHandler != null) {
        Dialog dialog = new Dialog(currentDialogText, event.get("type"));
        dialogHandler.handle(dialog);
    }
}
```

---

## Dialog Handling

Callback-based approach:

```java
@FunctionalInterface
public interface DialogHandler {
    void handle(Dialog dialog);
}

public class Dialog {
    private final CdpDriver driver;
    private final String message;
    private final String type;  // "alert", "confirm", "prompt", "beforeunload"

    public String getMessage();
    public String getType();
    public void accept();
    public void accept(String promptText);
    public void dismiss();
}

// Usage in CdpDriver
@Override
public void onDialog(DialogHandler handler) {
    this.dialogHandler = handler;
}

// Example usage:
driver.onDialog(dialog -> {
    if (dialog.getType().equals("confirm")) {
        dialog.accept();
    } else {
        dialog.dismiss();
    }
});
```

---

## Frame Management

Full frame support including cross-origin frames via Target sessions.

```java
private static class Frame {
    final String id;
    final String url;
    final String name;

    Frame(String id, String url, String name) {
        this.id = id;
        this.url = url;
        this.name = name;
    }
}

@Override
public void switchFrame(String locator) {
    if (locator == null) {
        // Switch back to main frame
        currentFrame = null;
        sessionId = frameSessions.get(mainFrameId);
        return;
    }

    retryIfEnabled(locator);
    String objectId = evalForObjectId(selector(locator));

    // Get frame ID from DOM node
    CdpResponse response = cdp.method("DOM.describeNode")
        .param("objectId", objectId)
        .param("depth", 0)
        .send();

    String frameId = response.getResult("node.frameId");
    if (frameId == null) {
        throw new RuntimeException("locator is not a frame: " + locator);
    }

    // Try to find frame in frame tree
    Frame frame = findFrameInTree(frameId);

    // If not found, it's likely a cross-origin iframe - need Target attachment
    if (frame == null) {
        attachToFrameTarget(frameId);
    }

    currentFrame = frame;

    // Ensure we have an execution context
    if (frameContexts.get(frameId) == null) {
        CdpResponse isolated = cdp.method("Page.createIsolatedWorld")
            .param("frameId", frameId)
            .send();
        frameContexts.put(frameId, isolated.getResult("executionContextId"));
    }
}

private void attachToFrameTarget(String frameId) {
    // Attach to the frame as a separate target (for cross-origin)
    CdpResponse response = cdp.method("Target.attachToTarget")
        .param("targetId", frameId)
        .param("flatten", true)
        .send();

    String newSessionId = response.getResult("sessionId");
    frameSessions.put(frameId, newSessionId);
    sessionId = newSessionId;

    cdp.method("Target.activateTarget")
        .param("targetId", frameId)
        .send();
}
```

---

## Request Interception

Both callback-based and mock handler modes.

```java
@FunctionalInterface
public interface InterceptHandler {
    Response handle(Request request);
}

// Callback-based interception
@Override
public void intercept(List<String> patterns, InterceptHandler handler) {
    this.interceptHandler = handler;
    this.mockHandler = null;
    enableFetchDomain(patterns);
}

// V1-style mock handler
@Override
public void intercept(Map<String, Object> config) {
    List<String> patterns = (List<String>) config.get("patterns");
    String mockFeature = (String) config.get("mock");

    // Load mock feature file
    Feature feature = Feature.read(mockFeature);
    this.mockHandler = new MockHandler(feature);
    this.interceptHandler = null;
    enableFetchDomain(patterns);
}

private void enableFetchDomain(List<String> patterns) {
    List<Map<String, Object>> requestPatterns = patterns.stream()
        .map(p -> Map.of("urlPattern", p))
        .collect(Collectors.toList());

    cdp.method("Fetch.enable")
        .param("patterns", requestPatterns)
        .send();
}

private void onRequestPaused(CdpEvent event) {
    String requestId = event.get("requestId");
    Request request = buildRequest(event);

    Response response = null;
    if (interceptHandler != null) {
        response = interceptHandler.handle(request);
    } else if (mockHandler != null) {
        response = mockHandler.handle(request);
    }

    if (response != null) {
        fulfillRequest(requestId, response);
    } else {
        continueRequest(requestId);
    }
}

@Override
public void stopIntercept() {
    cdp.method("Fetch.disable").send();
    interceptHandler = null;
    mockHandler = null;
}
```

---

## JavaScript Evaluation

```java
private CdpResponse eval(String expression) {
    return evalInternal(expression, false, true);
}

private CdpResponse evalQuickly(String expression) {
    return evalInternal(expression, true, true);
}

private CdpResponse evalInternal(String expression, boolean quickly, boolean returnByValue) {
    CdpMessage message = cdp.method("Runtime.evaluate")
        .param("expression", expression)
        .param("returnByValue", returnByValue);

    Integer contextId = getFrameContext();
    if (contextId != null) {
        message.param("contextId", contextId);
    }

    if (quickly) {
        message.timeout(Duration.ofMillis(options.getRetryInterval()));
    }

    CdpResponse response = message.send();

    if (response.isError()) {
        // Retry once after short delay
        sleep(options.getRetryInterval());
        response = message.send();
        if (response.isError()) {
            throw new RuntimeException("JS eval failed: " + expression +
                ", error: " + response.getError());
        }
    }

    return response;
}

private Integer getFrameContext() {
    if (currentFrame == null) {
        return null;
    }
    return frameContexts.get(currentFrame.id);
}
```

---

## Locators Class

Retain battle-tested v1 patterns from `DriverOptions.java` with improvements.

### Design Principles

1. **Retain v1 JS injection patterns** - These are battle-tested and work across all browsers
2. **Use v2 JsParser** - For advanced JS manipulation and validation when needed
3. **Fix known issues** - XPath bracket escaping, multiple node handling

### Core Methods (from v1)

```java
public class Locators {

    private static final String DOCUMENT = "document";

    // Reusable JS functions (retained from v1)
    public static final String SCROLL_JS_FUNCTION =
        "function(e){ var d = window.getComputedStyle(e).display;" +
        " while(d == 'none'){ e = e.parentElement; d = window.getComputedStyle(e).display }" +
        " e.scrollIntoView({block: 'center'}) }";

    public static final String KARATE_REF_GENERATOR =
        "function(e){" +
        " if (!document._karate) document._karate = { seq: (new Date()).getTime() };" +
        " var ref = 'ref' + document._karate.seq++; document._karate[ref] = e; return ref }";

    // Main selector transformation (retained from v1)
    public static String selector(String locator) {
        return selector(locator, DOCUMENT);
    }

    public static String selector(String locator, String contextNode) {
        // Pure JS expression - pass through
        if (locator.startsWith("(") && !locator.startsWith("(//")) {
            return locator;
        }
        // Wildcard: {div}text or {^div}partial
        if (locator.startsWith("{")) {
            locator = expandWildcard(locator);
        }
        // XPath
        if (isXpath(locator)) {
            return xpathSelector(locator, contextNode);
        }
        // CSS selector
        return contextNode + ".querySelector(\"" + escapeQuotes(locator) + "\")";
    }

    public static boolean isXpath(String locator) {
        return locator.startsWith("/") || locator.startsWith("./") ||
               locator.startsWith("../") || locator.startsWith("(//");
    }

    // IIFE wrapper (retained from v1)
    public static String wrapInFunctionInvoke(String js) {
        return "(function(){ " + js + " })()";
    }

    // Shorthand expression to function (retained from v1)
    public static String toFunction(String expression) {
        if (expression.isEmpty()) return expression;
        char first = expression.charAt(0);
        if (first == '_' && !expression.contains("=>")) {
            return "function(_){ return " + expression + " }";
        }
        if (first == '!') {
            return "function(_){ return " + expression + " }";
        }
        return expression;
    }
}
```

### Wildcard Locator Expansion (retained from v1)

```java
// {tag}text     -> //tag[normalize-space(text())='text']
// {^tag}text    -> //tag[contains(normalize-space(text()),'text')]
// {tag:2}text   -> (//tag[normalize-space(text())='text'])[2]
// {:2}text      -> (//*[normalize-space(text())='text'])[2]
public static String expandWildcard(String locator) {
    int pos = locator.indexOf('}');
    if (pos == -1) {
        throw new DriverException("bad locator prefix: " + locator);
    }
    boolean contains = locator.charAt(1) == '^';
    String prefix = locator.substring(contains ? 2 : 1, pos);
    String text = locator.substring(pos + 1);

    // Parse tag:index
    String tag = "*";
    int index = 0;
    int colonPos = prefix.indexOf(':');
    if (colonPos != -1) {
        String tagPart = prefix.substring(0, colonPos);
        if (!tagPart.isEmpty()) tag = tagPart;
        String indexPart = prefix.substring(colonPos + 1);
        if (!indexPart.isEmpty()) {
            index = Integer.parseInt(indexPart);
        }
    } else if (!prefix.isEmpty()) {
        tag = prefix;
    }

    if (!tag.startsWith("/")) {
        tag = "//" + tag;
    }

    // Build XPath - FIX: properly escape quotes in text
    String escapedText = escapeXpathString(text);
    String xpath = contains
        ? tag + "[contains(normalize-space(text())," + escapedText + ")]"
        : tag + "[normalize-space(text())=" + escapedText + "]";

    return index == 0 ? xpath : "(" + xpath + ")[" + index + "]";
}

// FIX: Handle quotes in XPath strings
private static String escapeXpathString(String text) {
    if (!text.contains("'")) {
        return "'" + text + "'";
    }
    if (!text.contains("\"")) {
        return "\"" + text + "\"";
    }
    // Contains both - use concat()
    return "concat('" + text.replace("'", "',\"'\",'" ) + "')";
}
```

### XPath Selector (improved from v1)

```java
private static String xpathSelector(String xpath, String contextNode) {
    // Handle indexed wildcard: /(...)[n]
    if (xpath.startsWith("/(")) {
        if (DOCUMENT.equals(contextNode)) {
            xpath = xpath.substring(1);
        } else {
            xpath = "(." + xpath.substring(2);
        }
    } else if (!DOCUMENT.equals(contextNode) && !xpath.startsWith(".")) {
        xpath = "." + xpath; // evaluate relative to context node
    }

    // FIX: Properly escape quotes in XPath expression
    String escapedXpath = escapeQuotes(xpath);

    // XPathResult.FIRST_ORDERED_NODE_TYPE = 9
    return "document.evaluate(\"" + escapedXpath + "\", " + contextNode +
           ", null, 9, null).singleNodeValue";
}

// For querySelectorAll / XPath iterator
public static String selectorAll(String locator, String contextNode) {
    if (locator.startsWith("{")) {
        locator = expandWildcard(locator);
    }
    if (isXpath(locator)) {
        // XPathResult.ORDERED_NODE_ITERATOR_TYPE = 5
        String escapedXpath = escapeQuotes(locator);
        return "document.evaluate(\"" + escapedXpath + "\", " + contextNode +
               ", null, 5, null)";
    }
    return contextNode + ".querySelectorAll(\"" + escapeQuotes(locator) + "\")";
}
```

### Script Execution Helpers (retained from v1)

```java
// Execute function on single element
public static String scriptSelector(String locator, String expression) {
    return scriptSelector(locator, expression, DOCUMENT);
}

public static String scriptSelector(String locator, String expression, String contextNode) {
    String js = "var fun = " + toFunction(expression) +
                "; var e = " + selector(locator, contextNode) +
                "; return fun(e)";
    return wrapInFunctionInvoke(js);
}

// Execute function on all matching elements
public static String scriptAllSelector(String locator, String expression) {
    return scriptAllSelector(locator, expression, DOCUMENT);
}

public static String scriptAllSelector(String locator, String expression, String contextNode) {
    if (locator.startsWith("{")) {
        locator = expandWildcard(locator);
    }
    boolean isXpath = isXpath(locator);
    String selectorExpr = isXpath
        ? "document.evaluate(\"" + escapeQuotes(locator) + "\", " + contextNode + ", null, 5, null)"
        : contextNode + ".querySelectorAll(\"" + escapeQuotes(locator) + "\")";

    String js = "var res = []; var fun = " + toFunction(expression) + "; var es = " + selectorExpr + "; ";
    if (isXpath) {
        js += "var e = null; while(e = es.iterateNext()) res.push(fun(e)); return res";
    } else {
        js += "es.forEach(function(e){ res.push(fun(e)) }); return res";
    }
    return wrapInFunctionInvoke(js);
}
```

### UI Helper Functions (retained from v1)

```java
// Highlight element
public static String highlight(String locator, int millis) {
    String fn = "function(e){ var old = e.getAttribute('style');" +
                " e.setAttribute('style', 'background: yellow; border: 2px solid red;');" +
                " setTimeout(function(){ e.setAttribute('style', old) }, " + millis + ") }";
    String js = "var e = " + selector(locator) + "; var fun = " + fn + "; fun(e)";
    return wrapInFunctionInvoke(js);
}

// Select dropdown option
public static String optionSelector(String locator, String text) {
    boolean textEquals = text.startsWith("{}");
    boolean textContains = text.startsWith("{^}");
    String condition;
    if (textEquals || textContains) {
        text = text.substring(text.indexOf('}') + 1);
        condition = textContains
            ? "e.options[i].text.indexOf(t) !== -1"
            : "e.options[i].text === t";
    } else {
        condition = "e.options[i].value === t";
    }
    String escapedText = escapeQuotes(text);
    String js = "var e = " + selector(locator) + "; var t = \"" + escapedText + "\";" +
                " for (var i = 0; i < e.options.length; ++i)" +
                " if (" + condition + ") { e.options[i].selected = true; " +
                "e.dispatchEvent(new Event('change')) }";
    return wrapInFunctionInvoke(js);
}

// Element position
public static String getPositionJs(String locator) {
    String js = "var r = " + selector(locator) + ".getBoundingClientRect();" +
                " var dx = window.scrollX; var dy = window.scrollY;" +
                " return { x: r.x + dx, y: r.y + dy, width: r.width, height: r.height }";
    return wrapInFunctionInvoke(js);
}

// Focus with cursor at end
public static String focusJs(String locator) {
    return "var e = " + selector(locator) +
           "; e.focus(); try { e.selectionStart = e.selectionEnd = e.value.length } catch(x) {}";
}
```

### V2 Improvements & Bug Fixes

**Bug fixes from v1:**

1. **XPath quote escaping** - v1 breaks on `{div}It's working`:
```java
// V1 (broken): //div[text()='It's working']
// V2 (fixed):  //div[text()="It's working"] or concat() for both quotes
private static String escapeXpathString(String text) {
    if (!text.contains("'")) return "'" + text + "'";
    if (!text.contains("\"")) return "\"" + text + "\"";
    // Contains both - use concat()
    return "concat('" + text.replace("'", "',\"'\",'" ) + "')";
}
```

2. **JS string escaping** - v1 breaks on text with quotes/backslashes:
```java
// Escape for embedding in JS double-quoted string
private static String escapeForJs(String text) {
    return text.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("\n", "\\n")
               .replace("\r", "\\r");
}
```

3. **getPositionJs dimension bug** - v1 incorrectly adds scroll to width/height:
```java
// V1 (broken): width: r.width + dx, height: r.height + dy
// V2 (fixed):  width: r.width, height: r.height
public static String getPositionJs(String locator) {
    String js = "var r = " + selector(locator) + ".getBoundingClientRect();" +
                " var dx = window.scrollX; var dy = window.scrollY;" +
                " return { x: r.x + dx, y: r.y + dy, width: r.width, height: r.height }";
    return wrapInFunctionInvoke(js);
}
```

4. **scriptAllSelector contextNode bug** - v1 ignores relative context for XPath:
```java
// V2: Apply same relative XPath logic as selector()
public static String scriptAllSelector(String locator, String expression, String contextNode) {
    if (locator.startsWith("{")) {
        locator = expandWildcard(locator);
    }
    boolean isXpath = isXpath(locator);

    // FIX: Make XPath relative to contextNode (missing in v1)
    if (isXpath && !DOCUMENT.equals(contextNode) && !locator.startsWith(".")) {
        locator = "." + locator;
    }

    String selectorExpr = isXpath
        ? "document.evaluate(\"" + escapeForJs(locator) + "\", " + contextNode + ", null, 5, null)"
        : contextNode + ".querySelectorAll(\"" + escapeForJs(locator) + "\")";
    // ... rest unchanged
}
```

5. **toFunction empty string handling** - v1 throws on empty:
```java
// V2: Handle empty/null safely
public static String toFunction(String expression) {
    if (expression == null || expression.isEmpty()) {
        return "function(_){ return _ }";  // identity function
    }
    char first = expression.charAt(0);
    if ((first == '_' || first == '!') && !expression.contains("=>")) {
        return "function(_){ return " + expression + " }";
    }
    return expression;
}
```

6. **Input validation** - Early failure with clear messages:
```java
public static String selector(String locator) {
    if (locator == null || locator.isEmpty()) {
        throw new DriverException("locator cannot be null or empty");
    }
    return selector(locator, DOCUMENT);
}
```

**Performance improvements:**

7. **Avoid repeated XPath detection** - Cache the check:
```java
// Instead of checking startsWith() multiple times
private static final Set<String> XPATH_PREFIXES = Set.of("/", "./", "../", "(//");

public static boolean isXpath(String locator) {
    for (String prefix : XPATH_PREFIXES) {
        if (locator.startsWith(prefix)) return true;
    }
    return false;
}
```

8. **Pre-compiled patterns** - For wildcard parsing:
```java
// Compile once, reuse
private static final Pattern WILDCARD_PATTERN =
    Pattern.compile("^\\{(\\^)?([^:}]*)?(?::(\\d+))?}(.*)$");

public static String expandWildcard(String locator) {
    Matcher m = WILDCARD_PATTERN.matcher(locator);
    if (!m.matches()) {
        throw new DriverException("bad wildcard locator: " + locator);
    }
    boolean contains = m.group(1) != null;
    String tag = m.group(2) == null || m.group(2).isEmpty() ? "*" : m.group(2);
    int index = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
    String text = m.group(4);
    // ... build xpath
}
```

**Optional: JsParser integration for validation:**

```java
// Validate JS expression before injection
public static boolean isValidJs(String expression) {
    try {
        new JsParser(Resource.of(expression)).parse();
        return true;
    } catch (ParserException e) {
        return false;
    }
}
```

---

## Implementation Phases

### Phase 1: WebSocket + CDP Client
1. WsFrame, WsException, WsClientOptions
2. WsClient, WsClientHandler (Netty)
3. CdpMessage, CdpResponse, CdpEvent
4. CdpClient with request/response correlation
5. **Testable:** Connect to Chrome, call `Browser.getVersion`

### Phase 2: Browser Launch + Minimal Driver
1. BrowserLauncher (uses existing ProcessBuilder)
2. CdpDriverOptions (builder + map)
3. PageLoadStrategy enum
4. CdpDriver minimal: start, connect, quit, setUrl, getUrl, getTitle, script, screenshot
5. **Testable:** Launch Chrome, navigate, get title, take screenshot

### Phase 3: Testcontainers + E2E Infrastructure
1. Add Testcontainers dependency to pom.xml
2. ChromeContainer (chromedp/headless-shell)
3. DriverTestBase, TestPageServer
4. Test HTML pages (port from v1)
5. **Testable:** NavigationE2ETest, basic scripts

### Phase 4: Visibility Layer
1. CdpInspector class
2. Screenshot capture, DOM queries
3. Event streaming (Console, Network, Page)
4. Debug snapshot helper
5. **Testable:** Full observability in tests

### Phase 5: Element Operations
1. Locator transformation (Locators class)
2. click, input, clear, focus, value, select
3. text, html, attribute, property, enabled
4. locate, locateAll, exists, optional
5. position, scroll, highlight
6. waitFor, waitForAny, waitUntil
7. retry() fluent API
8. **Testable:** InputE2ETest, WaitE2ETest

### Phase 6: Frame & Dialog Support
1. Frame tracking (execution contexts, sessions)
2. switchFrame(index), switchFrame(locator)
3. Cross-origin frame attachment
4. Dialog callback registration
5. **Testable:** FrameE2ETest

### Phase 7: Advanced Features
1. Request interception (callback + mock modes)
2. Cookies (get, set, delete, clear)
3. Mouse and Keys, Actions API
4. Pages/tabs (getPages, switchPage)
5. Window management, PDF generation
6. Positional locators
7. **Testable:** InterceptE2ETest, full v1 parity

---

## Usage Examples

### Basic Navigation

```java
// Launch new browser
CdpDriver driver = CdpDriver.start(
    CdpDriverOptions.builder()
        .headless(true)
        .timeout(30000)
        .build()
);

driver.setUrl("https://example.com");
System.out.println(driver.getTitle());

driver.quit();
```

### Connect to Existing Browser

```java
// Start Chrome with: chrome --remote-debugging-port=9222
CdpDriver driver = CdpDriver.connect("ws://localhost:9222/devtools/page/...");
driver.setUrl("https://example.com");
```

### Element Operations

```java
driver.setUrl("https://github.com/login");
driver.input("#login_field", "username");
driver.input("#password", "secret");
driver.click("input[type='submit']");

// Wait for navigation
driver.waitForUrl("/dashboard");
```

### Dialog Handling

```java
driver.onDialog(dialog -> {
    System.out.println("Dialog: " + dialog.getMessage());
    if (dialog.getType().equals("confirm")) {
        dialog.accept();
    } else if (dialog.getType().equals("prompt")) {
        dialog.accept("user input");
    } else {
        dialog.dismiss();
    }
});

driver.script("confirm('Are you sure?')");
```

### Request Interception (Callback)

```java
driver.intercept(List.of("*api/users*"), request -> {
    if (request.getUrl().contains("/users/1")) {
        return Response.json(200, Map.of("id", 1, "name", "Mocked User"));
    }
    return null; // continue to network
});
```

### Request Interception (Mock Handler)

```java
driver.intercept(Map.of(
    "patterns", List.of("*api/*"),
    "mock", "classpath:mocks/api-mock.feature"
));
```

### Frame Switching

```java
driver.setUrl("https://example.com/with-iframe");

// Switch to iframe by locator
driver.switchFrame("#my-iframe");
driver.click("button.inside-frame");

// Switch back to main frame
driver.switchFrame(null);
```

### Raw CDP Access

```java
CdpClient cdp = driver.getCdpClient();

// Call any CDP method
CdpResponse response = cdp.method("Performance.getMetrics").send();
List<Map> metrics = response.getResult("metrics");

// Subscribe to events
cdp.on("Network.requestWillBeSent", event -> {
    System.out.println("Request: " + event.get("request.url"));
});
cdp.method("Network.enable").send();
```

---

## CdpInspector (Claude Code Visibility)

Full observability for debugging and AI-assisted development.

```java
public class CdpInspector {

    private final CdpDriver driver;
    private final List<String> consoleMessages = new CopyOnWriteArrayList<>();

    public CdpInspector(CdpDriver driver) {
        this.driver = driver;
        enableObservability();
    }

    // Screenshot capture
    public byte[] captureScreenshot();
    public byte[] captureScreenshot(String format);  // png, jpeg, webp
    public String captureScreenshotBase64();
    public void saveScreenshot(Path path);

    // DOM queries (execute JS, return results)
    public Object evalJs(String expression);
    public String getOuterHtml(String selector);
    public String getInnerHtml(String selector);
    public String getText(String selector);
    public Map<String, String> getAttributes(String selector);
    public List<Map<String, Object>> querySelectorAll(String selector);

    // Page state
    public String getPageSource();
    public String getCurrentUrl();
    public String getTitle();
    public List<String> getConsoleMessages();

    // Real-time event stream
    public void onConsoleMessage(Consumer<String> handler);
    public void onNetworkRequest(Consumer<Map<String, Object>> handler);
    public void onNetworkResponse(Consumer<Map<String, Object>> handler);
    public void onPageLoad(Runnable handler);
    public void onError(Consumer<Throwable> handler);

    // Debug snapshot (all state in one call)
    public Map<String, Object> getSnapshot();  // url, title, console, screenshot base64

    private void enableObservability() {
        CdpClient cdp = driver.getCdpClient();
        cdp.method("Console.enable").send();
        cdp.method("Network.enable").send();
        cdp.method("Page.enable").send();
        cdp.method("Runtime.enable").send();

        cdp.on("Console.messageAdded", event -> {
            consoleMessages.add(event.get("message.text"));
        });
    }
}
```

### Usage Example

```java
CdpInspector inspector = new CdpInspector(driver);

// Take screenshot
inspector.saveScreenshot(Path.of("debug.png"));

// Query DOM
String title = inspector.getText("h1");
List<Map<String, Object>> links = inspector.querySelectorAll("a");

// Stream console messages
inspector.onConsoleMessage(msg -> System.out.println("[CONSOLE] " + msg));

// Get full debug snapshot
Map<String, Object> snapshot = inspector.getSnapshot();
// { url: "...", title: "...", consoleErrors: [...], screenshotBase64: "..." }
```

---

## Docker Integration

Use minimal Chrome containers with Testcontainers for reproducible, isolated testing.

### Container Choice

| Container | Size | Use Case |
|-----------|------|----------|
| **chromedp/headless-shell** | ~200MB | Default - minimal CDP-only |
| zenika/alpine-chrome | ~400MB | When VNC debugging needed |
| selenium/standalone-chrome | ~1.5GB | Full Selenium features |

### ChromeContainer

```java
public class ChromeContainer extends GenericContainer<ChromeContainer> {

    private static final String IMAGE = "chromedp/headless-shell:latest";
    private static final int CDP_PORT = 9222;

    public ChromeContainer() {
        super(DockerImageName.parse(IMAGE));
        withExposedPorts(CDP_PORT);
        withCommand(
            "--remote-debugging-address=0.0.0.0",
            "--remote-debugging-port=9222",
            "--disable-gpu",
            "--no-sandbox",
            "--disable-dev-shm-usage"
        );
        waitingFor(Wait.forHttp("/json/version").forPort(CDP_PORT));
    }

    public String getCdpUrl() {
        String host = getHost();
        int port = getMappedPort(CDP_PORT);
        // Fetch WebSocket URL from /json/version
        return fetchWebSocketUrl(host, port);
    }

    public CdpDriver createDriver() {
        return CdpDriver.connect(getCdpUrl());
    }

    public CdpDriver createDriver(CdpDriverOptions options) {
        return CdpDriver.connect(getCdpUrl(), options);
    }

    private String fetchWebSocketUrl(String host, int port) {
        // GET http://host:port/json/version
        // Extract "webSocketDebuggerUrl" from response
        // Replace localhost with actual host
    }
}
```

### Maven Dependencies

Add to `karate-core/pom.xml` (test scope):

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

---

## E2E Test Structure

### Directory Layout

```
karate-core/src/test/java/io/karatelabs/driver/
├── unit/                           # Pure unit tests (no Chrome)
│   ├── CdpMessageTest.java
│   ├── CdpResponseTest.java
│   ├── LocatorsTest.java
│   └── CdpDriverOptionsTest.java
├── integration/                    # Require local Chrome
│   ├── CdpClientTest.java
│   └── BrowserLauncherTest.java
└── e2e/                            # Docker-based tests
    ├── support/
    │   ├── ChromeContainer.java
    │   ├── DriverTestBase.java
    │   └── TestPageServer.java
    ├── NavigationE2ETest.java
    ├── WaitE2ETest.java
    ├── ScriptE2ETest.java
    ├── InputE2ETest.java
    ├── FrameE2ETest.java
    └── InterceptE2ETest.java

karate-core/src/test/resources/io/karatelabs/driver/
└── pages/                          # Test HTML pages
    ├── index.html
    ├── navigation.html
    ├── wait.html
    ├── input.html
    ├── iframe.html
    └── resources/
        ├── test.css
        └── test.js
```

### DriverTestBase

```java
@Testcontainers
public abstract class DriverTestBase {

    @Container
    protected static final ChromeContainer chrome = new ChromeContainer();

    protected static HttpServer testServer;
    protected static CdpDriver driver;
    protected static CdpInspector inspector;

    @BeforeAll
    static void startTestServer() {
        testServer = HttpServer.start(0, TestPageServer::handle);
    }

    @BeforeAll
    static void createDriver() {
        driver = chrome.createDriver(
            CdpDriverOptions.builder()
                .timeout(30000)
                .pageLoadStrategy(PageLoadStrategy.DOMCONTENT_AND_FRAMES)
                .build()
        );
        inspector = new CdpInspector(driver);
    }

    @AfterAll
    static void cleanup() {
        if (driver != null) driver.quit();
        if (testServer != null) testServer.stopAsync();
    }

    protected String testUrl(String path) {
        // Use Testcontainers host for Docker networking
        return "http://host.testcontainers.internal:" + testServer.getPort() + path;
    }

    // Debug helper for Claude Code
    protected void debugSnapshot() {
        System.out.println("=== Debug Snapshot ===");
        System.out.println("URL: " + driver.getUrl());
        System.out.println("Title: " + driver.getTitle());
        System.out.println("Console: " + inspector.getConsoleMessages());
        inspector.saveScreenshot(Path.of("debug-" + System.currentTimeMillis() + ".png"));
    }
}
```

### TestPageServer

```java
public class TestPageServer {

    private static final Map<String, String> PAGES = loadPages();

    public static HttpResponse handle(HttpRequest request) {
        String path = request.getPath();

        // Serve static HTML pages
        String content = PAGES.get(path);
        if (content != null) {
            return HttpResponse.ok(content, "text/html");
        }

        // Serve static resources
        if (path.startsWith("/resources/")) {
            return serveResource(path);
        }

        return HttpResponse.notFound();
    }

    private static Map<String, String> loadPages() {
        Map<String, String> pages = new HashMap<>();
        pages.put("/", readResource("pages/index.html"));
        pages.put("/navigation", readResource("pages/navigation.html"));
        pages.put("/wait", readResource("pages/wait.html"));
        pages.put("/input", readResource("pages/input.html"));
        pages.put("/iframe", readResource("pages/iframe.html"));
        return pages;
    }
}
```

### Running Tests

```bash
# Unit tests (no Chrome needed)
mvn test -Dtest="io.karatelabs.driver.unit.*"

# Integration tests (local Chrome)
mvn test -Dtest="io.karatelabs.driver.integration.*"

# E2E tests (Docker Chrome via Testcontainers)
mvn test -Dtest="io.karatelabs.driver.e2e.*"

# All driver tests
mvn test -Dtest="io.karatelabs.driver.**"
```

---

## Files to Create

```
karate-core/src/main/java/io/karatelabs/http/
├── WsFrame.java             # WebSocket frame wrapper
├── WsException.java         # WebSocket exception
├── WsClientOptions.java     # Client configuration
├── WsClient.java            # Netty WebSocket client
└── WsClientHandler.java     # Netty handler

karate-core/src/main/java/io/karatelabs/driver/
├── Driver.java              # Interface (extracted from CdpDriver)
├── DriverOptions.java       # Base options interface
├── Element.java             # Element abstraction
├── Locators.java            # Locator transformation
├── Finder.java              # Positional element finder
├── Mouse.java               # Mouse interface
├── Keys.java                # Keyboard interface
├── Dialog.java              # Dialog interface
├── DriverException.java     # Exception
├── PageLoadStrategy.java    # Enum
├── DialogHandler.java       # Functional interface
├── InterceptHandler.java    # Functional interface
├── InterceptRequest.java    # Data class
├── InterceptResponse.java   # Data class
│
└── cdp/                     # CDP-specific implementation
    ├── CdpDriver.java       # implements Driver
    ├── CdpDriverOptions.java # implements DriverOptions
    ├── CdpClient.java       # CDP protocol client
    ├── CdpMessage.java      # Fluent message builder
    ├── CdpResponse.java     # Response wrapper
    ├── CdpEvent.java        # Event wrapper
    ├── CdpInspector.java    # Visibility/observability
    ├── CdpLauncher.java     # Chrome process
    ├── CdpMouse.java        # implements Mouse
    ├── CdpKeys.java         # implements Keys
    └── CdpDialog.java       # implements Dialog

karate-core/src/test/java/io/karatelabs/driver/
├── unit/
│   ├── CdpMessageTest.java
│   ├── CdpResponseTest.java
│   ├── LocatorsTest.java
│   └── CdpDriverOptionsTest.java
├── integration/
│   ├── CdpClientTest.java
│   └── BrowserLauncherTest.java
└── e2e/
    ├── support/
    │   ├── ChromeContainer.java
    │   ├── DriverTestBase.java
    │   └── TestPageServer.java
    ├── NavigationE2ETest.java
    ├── WaitE2ETest.java
    ├── ScriptE2ETest.java
    ├── InputE2ETest.java
    ├── FrameE2ETest.java
    └── InterceptE2ETest.java

karate-core/src/test/resources/io/karatelabs/driver/
└── pages/
    ├── index.html
    ├── navigation.html
    ├── wait.html
    ├── input.html
    └── iframe.html
```

---

## V1 Compatibility Notes

1. **@AutoDef removed**: V2 has better mechanisms for this
2. **Plugin interface removed**: Driver is standalone
3. **ScenarioRuntime dependency removed**: Driver works independently
4. **DriverOptions → CdpDriverOptions**: New name, builder pattern
5. **intercept() enhanced**: Now supports both callback and mock modes
6. **onDialog() added**: Replaces polling getDialogText()
7. **waitForPageLoad(strategy) added**: Explicit page load control
8. **getCdpClient() added**: Raw CDP access for power users
