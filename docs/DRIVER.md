# Browser Driver Design

> **Primary design document** for browser automation in karate-v2.
> Prerequisite: [WEBSOCKET.md](./WEBSOCKET.md) (WebSocket infrastructure)

## Progress Tracking

| Phase | Description | Status |
|-------|-------------|--------|
| **1-9c** | CDP Driver + Gherkin DSL + DriverProvider | ✅ Complete |
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
├── Mouse, Keys, Dialog                   # Input interfaces
├── DriverProvider, PooledDriverProvider  # Lifecycle management
└── cdp/                                  # CDP implementation
    ├── CdpDriver, CdpMouse, CdpKeys, CdpDialog
    ├── CdpClient, CdpMessage, CdpEvent, CdpResponse
    └── CdpInspector, CdpLauncher, CdpDriverOptions
```

**Key Source Files:**
- `io.karatelabs.driver.Driver` - Main interface (v1 Gherkin compatible)
- `io.karatelabs.driver.PooledDriverProvider` - Driver reuse for parallel execution
- `io.karatelabs.driver.cdp.CdpDriver` - CDP implementation
- `io.karatelabs.driver.cdp.CdpInspector` - Observability (screenshots, DOM, events)

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

---

## DriverProvider (Browser Reuse)

V2 uses `DriverProvider` for efficient driver lifecycle management with parallel execution.

```java
public interface DriverProvider {
    Driver acquire(ScenarioRuntime runtime, Map<String, Object> config);
    void release(ScenarioRuntime runtime, Driver driver);
    void shutdown();
}
```

**Built-in: `PooledDriverProvider`**
- Pool size auto-detected from `Runner.parallel(N)`
- Works correctly with virtual threads (Karate v2 default)
- Resets state between scenarios (about:blank, clear cookies)

```java
// Usage - pool size auto-detected
Runner.path("features/")
    .driverProvider(new PooledDriverProvider())
    .parallel(4);  // Creates pool of 4 drivers
```

**Custom Provider (Testcontainers example):**
```java
public class ContainerDriverProvider extends PooledDriverProvider {
    private final ChromeContainer container;

    public ContainerDriverProvider(ChromeContainer container) {
        super();  // Pool size auto-detected from Suite
        this.container = container;
    }

    @Override
    protected Driver createDriver(Map<String, Object> config) {
        return CdpDriver.connect(container.getCdpUrl(), CdpDriverOptions.fromMap(config));
    }
}
```

**Why PooledDriverProvider?** Karate v2 uses virtual threads where each task gets a new thread ID. `ThreadLocal`-based approaches create a driver per scenario. `PooledDriverProvider` correctly bounds the pool to match parallelism.

---

## Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Interface name | `Driver` | V1 familiarity |
| CDP-only APIs | Base interface with graceful degradation | Returns null/no-op on WebDriver |
| Async events | Separate `CdpInspector` class | Driver stays pure sync |
| Error handling | Always verbose | AI-agent friendly, includes selector/URL/timing |
| Docker | Testcontainers + `chromedp/headless-shell` | ~200MB, fast startup |

**Timeout Taxonomy:**
```java
driver.sessionTimeout(Duration)   // Overall session
driver.pageLoadTimeout(Duration)  // Navigation
driver.elementTimeout(Duration)   // Element waits
driver.scriptTimeout(Duration)    // JS execution
```

---

## Multi-Backend Architecture (Planned)

**Phase 10: Playwright Backend**
- Same `Driver` interface, different implementation
- Docker: `mcr.microsoft.com/playwright` or `browserless/chrome`
- Config switch: `Driver.builder().backend(Backend.PLAYWRIGHT).build()`

**Phase 11: WebDriver Backend**
- Legacy support, lower priority
- Sync REST/HTTP to chromedriver, geckodriver, etc.

**Phase 12: WebDriver BiDi**
- Add when spec matures (2025+)
- Combines WebDriver compatibility with CDP-like streaming

**Phase 13: Cloud Providers**
- SauceLabs, BrowserStack, LambdaTest support
- CDP via `se:cdp` capability extraction
- Custom `DriverProvider` implementations

---

## Cloud Provider Integration (Phase 13 TODO)

Cloud providers support CDP via WebDriver's `se:cdp` capability:

```java
public class SauceLabsDriverProvider implements DriverProvider {
    @Override
    public Driver acquire(ScenarioRuntime runtime, Map<String, Object> config) {
        // 1. POST to SauceLabs /session with capabilities
        // 2. Extract se:cdp WebSocket URL from response
        // 3. Return CdpDriver.connect(cdpUrl, options)
    }

    @Override
    public void release(ScenarioRuntime runtime, Driver driver) {
        driver.quit();
        reportTestStatus(runtime);  // PUT pass/fail to cloud API
    }
}
```

| Provider | CDP Support | Method |
|----------|-------------|--------|
| SauceLabs | ✅ | `se:cdp` via WebDriver |
| BrowserStack | ✅ | `se:cdp` via WebDriver |
| LambdaTest | ✅ | `se:cdp` via WebDriver |

---

## Deferred Features

**Commercial JavaFX Application:**
- Real-time browser visualization (CDP Screencast)
- LLM agent debugging integration
- Record & replay, API test derivation
- Network/console viewers

**karate-robot:**
- Cross-platform desktop automation
- Coordinate transform from viewport to screen-absolute
- Computer-use agent scenarios

**Video Recording:**
- CDP `Page.startScreencast` streams frames
- Stitch with ffmpeg for mp4 output
- Add to `CdpInspector` when needed
