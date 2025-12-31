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
| **9** | Gherkin/DSL Integration | ✅ Complete |
| **9b** | Gherkin E2E Tests + DriverProvider | ✅ Complete |
| **9c** | Test Optimization (browser reuse) | ✅ Complete (via DriverProvider) |
| **10** | Playwright Backend | ⬜ Not started |
| **11** | WebDriver Backend (Legacy) | ⬜ Not started |
| **12** | WebDriver BiDi (Future) | ⬜ Not started |
| **13** | Cloud Provider Integration | ⬜ Not started |

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
- Phase 13 adds cloud provider support (SauceLabs, BrowserStack, LambdaTest)

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
| Target interface (DockerTarget, custom) | `DriverProvider` interface |
| Custom provisioners | `ThreadLocalDriverProvider` or custom implementations |
| Per-scenario browser | Optional - provider controls lifecycle |

**DriverProvider vs V1 Target:**
- V1 `Target.start()/stop()` - created browser per scenario
- V2 `DriverProvider.acquire()/release()` - flexible lifecycle (per-scenario, per-thread, pooled)
- Simpler interface, more control over reuse patterns

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

### Phases 3-8 Summary (Completed)

| Phase | Summary | Tests |
|-------|---------|-------|
| **3** | Testcontainers + ChromeContainer + TestPageServer + test HTML pages | 25 |
| **4** | CdpInspector (screenshots, DOM queries, console, network events) | 25 |
| **5** | Locators, Element class, wait methods, v1 bug fixes | 182 |
| **6** | Dialog handling (callback-based), frame switching (same-origin) | 211 |
| **7** | Intercept, cookies, window, PDF, navigation, Mouse, Keys, Finder | 241 |
| **8** | Package restructuring: `Driver` interface + `cdp/` subpackage | 241 |

**Package Structure (Phase 8+9c):**
```
io.karatelabs.driver/
├── Driver, Element, Locators, Finder    # Backend-agnostic
├── Mouse, Keys, Dialog                   # Interfaces
├── DialogHandler, InterceptHandler       # Functional interfaces
├── InterceptRequest, InterceptResponse   # Data classes
├── DriverProvider                        # Lifecycle management interface
├── ThreadLocalDriverProvider             # Per-thread driver reuse
└── cdp/                                  # CDP implementation
    ├── CdpDriver, CdpMouse, CdpKeys, CdpDialog
    ├── CdpClient, CdpMessage, CdpEvent, CdpResponse
    └── CdpInspector, CdpLauncher, CdpDriverOptions
```

**V1 Bug Fixes (Phase 5):** XPath quote escaping, JS string escaping, getPositionJs dimensions, toFunction empty string

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
- Integrated `DriverProvider` for efficient driver reuse (see Phase 9c)
- `DriverFeatureTest` now uses `ThreadLocalDriverProvider`

**Working v2 Gherkin Syntax:**
```gherkin
* configure driver = driverConfig
* driver serverUrl + '/'
* match driver.title == 'Karate Driver Test'
* def result = script('1 + 1')
```

**Completed (all issues resolved):**
- All 7 feature files created and passing (83 scenarios total)
- `Key.TAB` etc. - Fixed by wrapping `Keys.class` in `JavaType` for static field access
- Dialog callbacks - Fixed via `onDialog(handler)` root binding that wraps `JsCallable` to `DialogHandler`
- Frame switching - Working correctly

**V1 API Reference (from karate-core/README.md):**

*Navigation:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `driver 'url'` | Navigate to URL | ✅ Working |
| `driver.url` | Get/set current URL | ✅ Working |
| `driver.title` | Get page title | ✅ Working |
| `refresh()` | Page reload (keep cache) | 🔲 Test needed |
| `reload()` | Hard reload (clear cache) | 🔲 Test needed |
| `back()` | Navigate back | 🔲 Test needed |
| `forward()` | Navigate forward | 🔲 Test needed |

*Element Actions:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `click(locator)` | Click element | 🔲 Test needed |
| `input(locator, value)` | Input text | 🔲 Test needed |
| `input(locator, ['a', Key.ENTER])` | Input array | 🔲 Key issue |
| `input(locator, 'text', delay)` | Input with delay | 🔲 Test needed |
| `submit().click(locator)` | Submit + click | 🔲 Test needed |
| `focus(locator)` | Focus element | 🔲 Test needed |
| `clear(locator)` | Clear input | 🔲 Test needed |
| `value(locator, value)` | Set value | 🔲 Test needed |
| `select(locator, text)` | Select dropdown | 🔲 Test needed |
| `scroll(locator)` | Scroll to element | 🔲 Test needed |
| `highlight(locator)` | Highlight element | 🔲 Test needed |

*Element State:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `html(locator)` | Get outerHTML | 🔲 Test needed |
| `text(locator)` | Get textContent | 🔲 Test needed |
| `value(locator)` | Get value | 🔲 Test needed |
| `attribute(locator, name)` | Get attribute | 🔲 Test needed |
| `enabled(locator)` | Check if enabled | 🔲 Test needed |
| `exists(locator)` | Check if exists | 🔲 Test needed |
| `position(locator)` | Get position | 🔲 Test needed |

*Locators:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `locate(locator)` | Find single element | 🔲 Test needed |
| `locateAll(locator)` | Find all elements | 🔲 Test needed |
| `optional(locator)` | Find without failing | 🔲 Test needed |

*Wait Methods:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `waitFor(locator)` | Wait for element | 🔲 Test needed |
| `waitForAny(loc1, loc2)` | Wait for any | 🔲 Test needed |
| `waitForUrl('path')` | Wait for URL | 🔲 Test needed |
| `waitForText(loc, text)` | Wait for text | 🔲 Test needed |
| `waitForEnabled(loc)` | Wait until enabled | 🔲 Test needed |
| `waitForResultCount(loc, n)` | Wait for count | 🔲 Test needed |
| `waitUntil('js')` | Wait until JS true | 🔲 Test needed |
| `waitUntil(loc, 'js')` | Wait on element | 🔲 Test needed |
| `delay(ms)` | Sleep | 🔲 Test needed |

*Scripts:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `script('js')` | Execute JS | ✅ Working |
| `script(locator, 'js')` | JS on element | 🔲 Test needed |
| `scriptAll(locator, 'js')` | JS on all elements | 🔲 Test needed |

*Retry:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `retry()` | Enable retry | 🔲 Test needed |
| `retry(count)` | Set retry count | 🔲 Test needed |
| `retry(count, interval)` | Set count + interval | 🔲 Test needed |

*Dialogs:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `onDialog(handler)` | Register callback | ✅ Working |
| `dialog(accept)` | Accept/dismiss | ✅ Working |
| `dialog(accept, text)` | Accept with text | ✅ Working |
| `driver.dialogText` | Get dialog text | ✅ Working |

*Cookies:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `cookie(name)` | Get cookie | ✅ Working |
| `cookie(map)` | Set cookie | ✅ Working |
| `driver.cookies` | Get all cookies | ✅ Working |
| `deleteCookie(name)` | Delete cookie | ✅ Working |
| `clearCookies()` | Clear all | ✅ Working |

*Frames:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `switchFrame(index)` | By index | ✅ Working |
| `switchFrame(locator)` | By locator | ✅ Working |
| `switchFrame(null)` | Return to main | ✅ Working |

*Pages/Tabs:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `switchPage(titleOrUrl)` | Switch by title/URL | 🔲 Test needed |
| `switchPage(index)` | Switch by index | 🔲 Test needed |

*Mouse:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `mouse()` | Create mouse | ✅ Working |
| `mouse(locator)` | At element | ✅ Working |
| `mouse(x, y)` | At coordinates | ✅ Working |
| `.move()`, `.click()`, `.doubleClick()` | Chain methods | ✅ Working |

*Special Keys:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `Key.ENTER`, `Key.TAB`, etc. | Key constants | ✅ Working |
| `keys().press(Key.TAB)` | Press special key | ✅ Working |
| `keys().type('text')` | Type text | ✅ Working |
| `keys().ctrl('a')` | Ctrl+key combo | ✅ Working |

*Window:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `driver.dimensions` | Get/set dimensions | 🔲 Test needed |
| `maximize()`, `minimize()` | Window state | 🔲 Test needed |
| `fullscreen()` | Fullscreen | 🔲 Test needed |
| `quit()`, `close()` | Close browser | 🔲 Test needed |

*Screenshots:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `screenshot()` | Take screenshot | 🔲 Test needed |
| `screenshot(locator)` | Element screenshot | 🔲 Test needed |
| `pdf(options)` | Generate PDF | 🔲 Test needed |

*Friendly Locators:*
| V1 Gherkin | Description | V2 Status |
|------------|-------------|-----------|
| `rightOf(loc).input(val)` | To the right | 🔲 Test needed |
| `leftOf(loc).click()` | To the left | 🔲 Test needed |
| `above(loc)`, `below(loc)` | Above/below | 🔲 Test needed |
| `near(loc)` | Nearby | 🔲 Test needed |

*Known Issues:* None - all features working!

**Test Structure:**
```
karate-core/src/test/
├── java/io/karatelabs/driver/e2e/
│   └── DriverFeatureTest.java    # JUnit runner with ThreadLocalDriverProvider
│
└── resources/io/karatelabs/driver/
    ├── pages/                    # Test HTML pages
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
        ├── element.feature       # ✅ 37 scenarios passing
        ├── mouse.feature         # ✅ 9 scenarios passing
        ├── cookie.feature        # ✅ 5 scenarios passing
        ├── frame.feature         # ✅ 16 scenarios passing
        ├── dialog.feature        # ✅ 5 scenarios passing
        └── keys.feature          # ✅ 8 scenarios passing
```

**Test Runner Pattern:**
```java
@Test
void testDriverFeatures() {
    ThreadLocalDriverProvider provider = new ThreadLocalDriverProvider();

    Runner.path("classpath:io/karatelabs/driver/features")
        .configDir("classpath:io/karatelabs/driver/features/karate-config.js")
        .driverProvider(provider)  // Reuse driver across scenarios
        .parallel(1);
}
```

**Infrastructure Reuse:**
- `TestPageServer` - Serves test HTML pages
- `ChromeContainer` - Testcontainers Chrome setup
- `ThreadLocalDriverProvider` - Driver reuse across scenarios
- Test HTML pages in `pages/` directory

**V1 Reference:** `/Users/peter/dev/zcode/karate/karate-core/README.md`
- Use v1 README as API reference for Gherkin syntax
- v2 tests should be cleaner, better organized, using Testcontainers

### Phase 9c: DriverProvider Architecture (Complete)

**Problem:** V1's approach of closing driver after each scenario is inefficient. Parallel execution needs separate driver instances per thread.

**Solution:** `DriverProvider` interface for flexible driver lifecycle management.

```java
public interface DriverProvider {
    Driver acquire(ScenarioRuntime runtime, Map<String, Object> config);
    void release(ScenarioRuntime runtime, Driver driver);
    void shutdown();
}
```

**Flow:**
```
Runner.driverProvider(provider)
    ↓
Suite.driverProvider (stored)
    ↓
ScenarioRuntime.initDriver()
    → provider.acquire(runtime, config)  // Get driver
    ↓
ScenarioRuntime.closeDriver()
    → provider.release(runtime, driver)  // Return driver (don't quit)
    ↓
Suite.run() finally
    → provider.shutdown()  // Close all drivers
```

**Built-in Implementation: `ThreadLocalDriverProvider`**
- One driver per thread, reused across scenarios
- Resets state between scenarios (about:blank, clear cookies)
- Efficient for sequential and parallel execution

```java
// Usage
Runner.path("features/")
    .driverProvider(new ThreadLocalDriverProvider())
    .parallel(4);
```

**Key Files:**
- `io.karatelabs.driver.DriverProvider` - Interface
- `io.karatelabs.driver.ThreadLocalDriverProvider` - Per-thread implementation
- `io.karatelabs.core.Suite` - Holds provider, calls shutdown()
- `io.karatelabs.core.Runner.Builder` - `.driverProvider()` method
- `io.karatelabs.core.ScenarioRuntime` - Uses provider for acquire/release

**Parallel Execution Options:**
| Approach | Description | Use Case |
|----------|-------------|----------|
| `ThreadLocalDriverProvider` | One driver per thread | Multiple containers or local browsers |
| Custom `DriverProvider` | Pool of tabs in single browser | Single Testcontainer, CDP `Target.createTarget` |
| Multiple containers | One container per thread | Full isolation, higher resource usage |

**Test Results:**
```
Gherkin E2E: 7 features, 83 scenarios - all passed
Java E2E:   9 test classes, 121 tests - all passed
```

**Java E2E Test Optimization: SharedChromeContainer**

All Java E2E tests (`*E2eTest.java`) share a single Testcontainers Chrome instance via `SharedChromeContainer`:

```java
// SharedChromeContainer - singleton for all Java E2E tests
public class SharedChromeContainer {
    private static volatile SharedChromeContainer instance;

    public static SharedChromeContainer getInstance() {
        // Lazy singleton - starts container on first access
        // Shutdown hook cleans up when JVM exits
    }
}

// DriverTestBase - base class for all Java E2E tests
public abstract class DriverTestBase {
    @BeforeAll
    static void setupDriver() {
        shared = SharedChromeContainer.getInstance();  // Reuses singleton
        driver = shared.getChrome().createDriver(...);
    }
}
```

**Key Files:**
- `io.karatelabs.driver.e2e.support.SharedChromeContainer` - Singleton container holder
- `io.karatelabs.driver.e2e.support.DriverTestBase` - Base class using shared container

**Safety: Handling `driver.quit()` by Users:**

Users can still call `driver.quit()` explicitly. The system handles this gracefully:

1. **`ScenarioRuntime.getDriver()`** - Checks `driver.isTerminated()` and auto-creates new driver if needed
2. **`ThreadLocalDriverProvider.acquire()`** - Checks `isTerminated()` before reusing; discards stale references

```gherkin
# This works - auto-recovers after quit
* driver 'http://example.com'
* driver.quit()
* driver 'http://other.com'  # Creates new driver automatically
```

**When ThreadLocalDriverProvider is Sufficient:**

| Scenario | Sufficient? | Notes |
|----------|-------------|-------|
| Local Chrome/Edge with CDP | ✅ Yes | Default use case |
| Testcontainers (chromedp/headless-shell) | ✅ Yes | Recommended for CI |
| Multiple Testcontainers (parallel) | ✅ Yes | One container per thread |
| Single container, multiple tabs | ❌ No | Need custom provider with `Target.createTarget` |
| Cloud providers (SauceLabs, BrowserStack) | ❌ No | Need custom provider (see below) |
| WebDriver backend | ❌ No | Override `createDriver()` or custom provider |

**Cloud Provider Considerations (SauceLabs, BrowserStack):**

Cloud providers support CDP via WebDriver's `se:cdp` capability:
1. Establish WebDriver session to cloud grid
2. Extract CDP WebSocket URL from session response (`se:cdp` field)
3. Connect CDP client to that WebSocket

This requires a custom `DriverProvider`:
```java
public class SauceLabsDriverProvider implements DriverProvider {
    @Override
    public Driver acquire(ScenarioRuntime runtime, Map<String, Object> config) {
        // 1. Create WebDriver session to SauceLabs
        // 2. Get se:cdp WebSocket URL from response
        // 3. Return CdpDriver.connect(cdpUrl, options)
    }

    @Override
    public void release(ScenarioRuntime runtime, Driver driver) {
        // Cloud providers often prefer fresh sessions per test
        // May want to quit here rather than reuse
        driver.quit();
    }
}
```

**Note:** Cloud providers are moving toward WebDriver BiDi as the future standard. Karate v2 Phase 12 will add BiDi support.

**Extending ThreadLocalDriverProvider:**

For simpler customizations, extend rather than replace:
```java
public class MyDriverProvider extends ThreadLocalDriverProvider {
    @Override
    protected Driver createDriver(Map<String, Object> config) {
        // Custom driver creation (e.g., add capabilities, connect to grid)
        return super.createDriver(config);
    }

    @Override
    protected void resetDriver(Driver driver) {
        // Custom reset logic between scenarios
        super.resetDriver(driver);
    }
}
```

### Phase 13 Notes (TODO)

**Cloud Provider Integration (SauceLabs, BrowserStack, LambdaTest)**

**Goals:**
1. Provide `DriverProvider` implementations for top 3 cloud providers
2. Support both CDP-over-WebDriver (`se:cdp`) and pure WebDriver modes
3. Handle cloud-specific capabilities, authentication, and session reporting

**Cloud Provider CDP Support:**

| Provider | CDP Support | Method | Docs |
|----------|-------------|--------|------|
| SauceLabs | ✅ Yes | `se:cdp` via WebDriver | [CDP/BiDi docs](https://docs.saucelabs.com/web-apps/automated-testing/cdp-bidi/) |
| BrowserStack | ✅ Yes | `se:cdp` via WebDriver | [DevTools guide](https://www.browserstack.com/guide/selenium-devtools) |
| LambdaTest | ✅ Yes | `se:cdp` via WebDriver | Similar pattern |

**Architecture:**

```
┌─────────────────────────────────────────────────────────────┐
│                    CloudDriverProvider                       │
│  (abstract base - handles WebDriver session + CDP extract)   │
└─────────────────────────────────────────────────────────────┘
        ↑                    ↑                    ↑
┌───────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ SauceLabsDP   │  │ BrowserStackDP  │  │  LambdaTestDP   │
│ - auth        │  │ - auth          │  │  - auth         │
│ - capabilities│  │ - capabilities  │  │  - capabilities │
│ - reporting   │  │ - reporting     │  │  - reporting    │
└───────────────┘  └─────────────────┘  └─────────────────┘
```

**Implementation Approach:**

1. **`CloudDriverProvider` base class:**
   - Establish WebDriver session to cloud grid (REST API)
   - Extract `se:cdp` WebSocket URL from session response
   - Create `CdpDriver.connect(cdpUrl)` for CDP operations
   - Handle session cleanup and test status reporting

2. **Provider-specific subclasses:**
   - Authentication (API keys, access tokens)
   - Capability mapping (browser versions, platforms, tunnels)
   - Test result reporting (pass/fail status, artifacts)
   - Tunnel/proxy configuration for local testing

**Key Considerations:**

- **Session reuse:** Cloud providers typically charge per session and prefer fresh sessions. Default to `quit()` on `release()`, but allow opt-in reuse.
- **Parallel execution:** Cloud grids handle parallelism server-side. `ThreadLocalDriverProvider` pattern still works - each thread gets its own cloud session.
- **Fallback to WebDriver:** If CDP unavailable (older browsers, Safari), fall back to WebDriver-only mode with reduced functionality.
- **Dependency on Phase 11:** Full WebDriver backend needed for non-CDP browsers. CDP-over-WebDriver can work with just Karate's HTTP client for session establishment.

**Minimal MVP (CDP-over-WebDriver only):**
```java
public class SauceLabsDriverProvider implements DriverProvider {
    private final String username;
    private final String accessKey;

    @Override
    public Driver acquire(ScenarioRuntime runtime, Map<String, Object> config) {
        // 1. POST to SauceLabs /session endpoint with capabilities
        // 2. Extract sessionId and se:cdp URL from response
        // 3. Return CdpDriver.connect(cdpUrl, options)
    }

    @Override
    public void release(ScenarioRuntime runtime, Driver driver) {
        // 1. Quit driver (closes CDP)
        // 2. DELETE /session/{sessionId} to end cloud session
        // 3. PUT /jobs/{sessionId} with pass/fail status
        driver.quit();
        reportTestStatus(runtime);
    }
}
```

**Future: WebDriver BiDi**

When Phase 12 (BiDi) is complete, cloud providers will likely prefer BiDi over `se:cdp`. The `CloudDriverProvider` abstraction should accommodate both:
- CDP mode: `se:cdp` WebSocket extraction
- BiDi mode: Native BiDi WebSocket (when available)

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

## Implementation Reference

> **Note:** Phases 1-8 are fully implemented. See source code for authoritative API definitions.

**Key Source Files:**
- `io.karatelabs.driver.Driver` - Main interface (v1 API compatible)
- `io.karatelabs.driver.DriverProvider` - Driver lifecycle management
- `io.karatelabs.driver.ThreadLocalDriverProvider` - Per-thread driver reuse
- `io.karatelabs.driver.cdp.CdpDriver` - CDP implementation
- `io.karatelabs.driver.Locators` - Selector transformation (CSS, XPath, wildcards)
- `io.karatelabs.driver.Element` - Fluent element API
- `io.karatelabs.driver.cdp.CdpInspector` - Observability (screenshots, DOM, events)

**Design Decisions:**
| Decision | Choice |
|----------|--------|
| Wait model | Sync API with CompletableFuture internal |
| Page ready | DOM + frames (v1 default), configurable via PageLoadStrategy |
| Dialog handling | Callback: `onDialog(handler)` |
| Request interception | Both callback and mock-feature modes |
| Docker | Testcontainers + `chromedp/headless-shell` (~200MB) |
| Driver lifecycle | `DriverProvider` interface (replaces v1 `Target`) |

**V1 Compatibility:**
- Gherkin DSL is drop-in compatible
- Java API redesigned (clean break)
- `@AutoDef` removed, Plugin interface removed
- `onDialog()` replaces polling `getDialogText()`
- `getCdpClient()` exposes raw CDP access
- `DriverProvider` replaces `Target` interface (simpler, more flexible)
