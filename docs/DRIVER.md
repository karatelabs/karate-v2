# Browser Driver Design

> **This is the primary design document** for browser automation in karate-v2.
> Prerequisite: [WEBSOCKET.md](./WEBSOCKET.md) (WebSocket infrastructure)

## Progress Tracking

| Phase | Description | Status |
|-------|-------------|--------|
| **1** | WebSocket + CDP Client | ✅ Complete |
| **2** | Browser Launch + Minimal Driver | ✅ Complete |
| **3** | Testcontainers + E2E Infrastructure | ✅ Complete |
| **4** | Visibility Layer (DriverInspector) | ✅ Complete |
| **5** | Element Operations | ⬜ Not started |
| **6** | Frame & Dialog Support | ⬜ Not started |
| **7** | Advanced Features | ⬜ Not started |

**Legend:** ⬜ Not started | 🟡 In progress | ✅ Complete

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
- Implemented `DriverInspector` class for full observability:
  - Screenshot capture (PNG, JPEG, WebP formats)
  - DOM queries (getOuterHtml, getInnerHtml, getText, getAttributes, querySelectorAll)
  - Console message capture and streaming
  - Network request/response event handlers
  - Page load event handlers
  - Error/exception handlers
  - Debug snapshot (url, title, console, screenshot)
- Added 17 E2E tests for DriverInspector
- Added 5 navigation E2E tests using setUrl()
- All 25 E2E tests passing

**Next Steps for Phase 5:**
- Implement Locators class for selector transformation
- Add element operations (click, input, clear, focus, etc.)
- Add Element class abstraction
- Add wait methods (waitFor, waitForAny, waitUntil)

### Future Enhancements (TODO)

**Video Recording via CDP Screencast:**
- CDP has `Page.startScreencast` which streams frames as events - no container changes needed
- Could add to `DriverInspector`:
  ```java
  inspector.startRecording();
  // ... test actions ...
  byte[] video = inspector.stopRecording(); // returns mp4 via ffmpeg
  ```
- Uses `Page.screencastFrame` events with `Page.screencastFrameAck` for flow control
- Frames can be stitched with ffmpeg: `ffmpeg -framerate 2 -i '*.png' -c:v libx264 output.mp4`
- Useful for debugging complex test failures
- Not critical for v2 launch

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
| Observability | DriverInspector: screenshots + DOM queries + event stream |

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
1. DriverInspector class
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

## DriverInspector (Claude Code Visibility)

Full observability for debugging and AI-assisted development.

```java
public class DriverInspector {

    private final CdpDriver driver;
    private final List<String> consoleMessages = new CopyOnWriteArrayList<>();

    public DriverInspector(CdpDriver driver) {
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
DriverInspector inspector = new DriverInspector(driver);

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
    protected static DriverInspector inspector;

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
        inspector = new DriverInspector(driver);
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
├── Driver.java              # Interface
├── Element.java             # Element abstraction
├── CdpClient.java           # CDP protocol client
├── CdpMessage.java          # Fluent message builder
├── CdpResponse.java         # Response wrapper
├── CdpEvent.java            # Event wrapper
├── CdpDriver.java           # Main implementation
├── CdpDriverOptions.java    # Configuration
├── BrowserLauncher.java     # Chrome process
├── PageLoadStrategy.java    # Enum
├── DriverInspector.java     # Visibility/observability
├── DialogHandler.java       # Functional interface
├── Dialog.java              # Dialog wrapper
├── InterceptHandler.java    # Functional interface
├── Locators.java            # Locator transformation
├── Mouse.java               # Mouse actions
├── Keys.java                # Keyboard actions
├── Finder.java              # Positional locator interface
└── DriverException.java     # Exception

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
