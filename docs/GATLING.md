# Karate-Gatling v2 Port Plan

This document describes the plan to port karate-gatling from v1 to karate-v2.

## Summary of Decisions

| Decision | Choice |
|----------|--------|
| Gatling version | 3.12.x (latest stable) |
| Scala version | 3.x only |
| Integration approach | v2 RunListener event system |
| DSL strategy | Java-primary DSL |
| Async runtime | Virtual threads (Java 21+), no Akka/Pekko |
| Session variables | Keep `__karate`/`__gatling` pattern |
| Module location | Separate `karate-gatling` module |
| Scope | V1 parity first |

---

## 1. Module Structure

Create new Maven module: `karate-gatling`

```
karate-v2/
├── karate-js/
├── karate-core/
├── karate-gatling/          # NEW
│   ├── pom.xml
│   ├── src/main/java/io/karatelabs/gatling/
│   │   ├── KarateDsl.java           # Public Java DSL entry point
│   │   ├── KarateProtocol.java      # Gatling protocol implementation
│   │   ├── KarateProtocolBuilder.java
│   │   ├── KarateFeatureAction.java # Feature execution action
│   │   ├── KarateFeatureBuilder.java
│   │   ├── KarateSetAction.java     # Session variable injection
│   │   ├── KarateUriPattern.java    # URI pattern + pause config
│   │   └── MethodPause.java         # Method/pause data class
│   ├── src/main/scala/io/karatelabs/gatling/
│   │   └── ScalaDsl.scala           # Scala compatibility layer
│   ├── src/test/java/
│   │   └── io/karatelabs/gatling/
│   │       ├── GatlingSimulation.java  # Comprehensive test simulation
│   │       └── MockServer.java         # Test mock using v2 native mocks
│   ├── src/test/resources/
│   │   ├── karate-config.js
│   │   ├── features/                # Test feature files
│   │   └── logback-test.xml
│   └── README.md                    # Ported from v1 with updates
└── pom.xml                          # Add karate-gatling to modules
```

---

## 2. Dependencies (pom.xml)

```xml
<dependencies>
    <!-- Karate -->
    <dependency>
        <groupId>io.karatelabs</groupId>
        <artifactId>karate-core</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- Gatling 3.12.x -->
    <dependency>
        <groupId>io.gatling</groupId>
        <artifactId>gatling-core-java</artifactId>
        <version>3.12.0</version>
    </dependency>
    <dependency>
        <groupId>io.gatling.highcharts</groupId>
        <artifactId>gatling-charts-highcharts</artifactId>
        <version>3.12.0</version>
    </dependency>

    <!-- Scala 3 (for Gatling compatibility) -->
    <dependency>
        <groupId>org.scala-lang</groupId>
        <artifactId>scala3-library_3</artifactId>
        <version>3.4.0</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- Scala 3 compiler -->
        <plugin>
            <groupId>net.alchim31.maven</groupId>
            <artifactId>scala-maven-plugin</artifactId>
            <version>4.9.0</version>
            <configuration>
                <scalaVersion>3.4.0</scalaVersion>
            </configuration>
        </plugin>

        <!-- Gatling Maven plugin -->
        <plugin>
            <groupId>io.gatling</groupId>
            <artifactId>gatling-maven-plugin</artifactId>
            <version>4.9.0</version>
        </plugin>
    </plugins>
</build>
```

---

## 3. Core Classes Implementation

### 3.1 KarateDsl.java (Public API)

```java
package io.karatelabs.gatling;

public final class KarateDsl {

    // URI pattern builder
    public static KarateUriPattern.Builder uri(String pattern) { ... }

    // Protocol builder
    public static KarateProtocolBuilder karateProtocol(KarateUriPattern... patterns) { ... }

    // Feature action builder
    public static KarateFeatureBuilder karateFeature(String name, String... tags) { ... }

    // Session variable injection
    public static ActionBuilder karateSet(String key, Function<Session, Object> supplier) { ... }

    // Method pause helper
    public static MethodPause method(String method, int pauseMillis) { ... }
}
```

### 3.2 KarateProtocol.java

Key changes from v1:
- Use v2's `io.karatelabs.http.HttpRequest` and `io.karatelabs.core.ScenarioRuntime`
- Leverage v2's `Suite.getCallSingleCache()` and `Suite.getCallOnceCache()` (already ConcurrentHashMap)
- Expose `Runner.Builder` directly via `protocol.runner()`

```java
package io.karatelabs.gatling;

public class KarateProtocol implements Protocol {

    public static final String KARATE_KEY = "__karate";
    public static final String GATLING_KEY = "__gatling";

    private final Map<String, List<MethodPause>> uriPatterns;
    private BiFunction<HttpRequest, ScenarioRuntime, String> nameResolver = (req, sr) -> null;
    private Runner.Builder runner = Runner.builder();

    // Default name resolver using URI pattern matching
    public String defaultNameResolver(HttpRequest req, ScenarioRuntime sr) {
        String path = extractPath(req.getUrl());
        return uriPatterns.keySet().stream()
            .filter(pattern -> pathMatches(pattern, path))
            .findFirst()
            .orElse(path);
    }

    // Pause lookup
    public int pauseFor(String requestName, String method) { ... }

    // URI pattern matching (port from v1)
    public boolean pathMatches(String pattern, String path) { ... }
}
```

### 3.3 KarateFeatureAction.java

Key changes from v1:
- Use v2's `RunListener` event system instead of `PerfHook`
- Virtual threads for async execution and pauses
- Integrate with v2's `Suite`, `FeatureRuntime`, `ScenarioRuntime`

```java
package io.karatelabs.gatling;

public class KarateFeatureAction implements Action {

    private final String featurePath;
    private final String[] tags;
    private final KarateProtocol protocol;
    private final StatsEngine statsEngine;
    private final Action next;
    private final boolean silent;

    @Override
    public void execute(Session session) {
        // Run in virtual thread for non-blocking execution
        Thread.startVirtualThread(() -> executeFeature(session));
    }

    private void executeFeature(Session session) {
        // 1. Prepare session maps
        Map<String, Object> gatlingVars = new HashMap<>(session.attributes());
        Map<String, Object> karateVars = getOrCreate(session, KARATE_KEY);
        karateVars.put(GATLING_KEY, gatlingVars);

        // 2. Create Suite with RunListener for metrics
        Suite suite = Suite.builder()
            .path(featurePath)
            .tags(tags)
            .listener(createPerfListener())
            .build();

        // 3. Execute and collect results
        SuiteResult result = suite.run();

        // 4. Update session and continue
        Session updated = updateSession(session, result);
        next.execute(updated);
    }

    private RunListener createPerfListener() {
        return event -> {
            if (silent) return true; // Skip reporting for warm-up

            switch (event) {
                case StepRunEvent e -> {
                    // Report HTTP requests to Gatling
                    if (isHttpStep(e)) {
                        reportToGatling(e);
                    }
                }
            }
            return true;
        };
    }

    private void reportToGatling(StepRunEvent event) {
        String name = resolveName(event);
        long startTime = event.getStartTime();
        long endTime = event.getEndTime();
        Status status = event.isPassed() ? Status.OK : Status.KO;

        statsEngine.logResponse(
            session.scenario(),
            session.groups(),
            name,
            startTime,
            endTime,
            status,
            getStatusCode(event),
            getErrorMessage(event)
        );

        // Apply pause after request
        int pauseMs = protocol.pauseFor(name, getMethod(event));
        if (pauseMs > 0) {
            Thread.sleep(pauseMs); // Virtual thread - non-blocking
        }
    }
}
```

### 3.4 Virtual Thread Pause Implementation

```java
// In KarateFeatureAction or utility class
private void pause(int millis) {
    try {
        // With virtual threads, Thread.sleep() is non-blocking
        // The virtual thread yields, platform thread is freed
        Thread.sleep(millis);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

## 4. Custom Performance Event Capture

Add `PerfContext` interface to karate-core (same as v1) with `KarateJs` implementing it.

### 4.1 Add PerfContext interface to karate-core

```java
// io/karatelabs/core/PerfContext.java
package io.karatelabs.core;

/**
 * Interface for capturing custom performance events.
 * Used primarily for Gatling integration but designed as a NO-OP
 * when not in a performance testing context.
 */
public interface PerfContext {
    void capturePerfEvent(String name, long startTime, long endTime);
}
```

### 4.2 KarateJs implements PerfContext

```java
// In KarateJs.java
public class KarateJs extends KarateJsBase implements PerfContext {

    private Consumer<PerfEvent> perfEventHandler;

    @Override
    public void capturePerfEvent(String name, long startTime, long endTime) {
        if (perfEventHandler != null) {
            perfEventHandler.accept(new PerfEvent(name, startTime, endTime));
        }
        // NO-OP when perfEventHandler is null (normal Karate execution)
    }

    public void setPerfEventHandler(Consumer<PerfEvent> handler) {
        this.perfEventHandler = handler;
    }
}

// Simple data class
public record PerfEvent(String name, long startTime, long endTime) {}
```

### 4.3 Gatling module hooks the handler

```java
// In KarateFeatureAction, before running feature
karateJs.setPerfEventHandler(event -> {
    if (!silent) {
        statsEngine.logResponse(
            session.scenario(), session.groups(),
            event.name(), event.startTime(), event.endTime(),
            Status.OK, 200, null
        );
    }
});
```

### 4.4 Usage in feature files (identical to v1)

```gherkin
Scenario: Custom RPC
  * def result = Java.type('mock.MockUtils').myRpc({ sleep: 100 }, karate)
```

```java
// MockUtils.java - clean typed API (same as v1!)
public static Map<String, Object> myRpc(Map<String, Object> args, PerfContext context) {
    long start = System.currentTimeMillis();
    // ... custom logic (database, gRPC, etc.) ...
    long end = System.currentTimeMillis();

    context.capturePerfEvent("myRpc", start, end);
    return Map.of("success", true);
}
```

This approach:
- Provides clean `PerfContext` interface for users (same as v1)
- `KarateJs` implements `PerfContext` so `karate` object can be passed directly
- NO-OP when not in Gatling context (perfEventHandler is null)
- Gatling module sets the handler before feature execution
- **100% API compatible with v1 user code**

---

## 5. Session Variable Flow

Maintain v1 compatibility with `__karate` and `__gatling` maps.

```
Gatling Session                    Karate Context
─────────────────                  ──────────────
userId: 1                    →     __gatling.userId
name: "Fluffy"              →     __gatling.name
__karate: { catId: 123 }    ←     catId (from feature)
```

### Access patterns in features:
```gherkin
# Access Gatling variables
* def userId = karate.get('__gatling.userId', 0)

# Access previous Karate results
* def catId = __karate.catId
```

---

## 6. Test Implementation

### 6.1 Single Comprehensive Simulation

Consolidate v1's multiple simulations into one comprehensive test:

```java
package io.karatelabs.gatling;

public class GatlingSimulation extends Simulation {

    // Start mock server
    static {
        MockServer.start();
    }

    KarateProtocolBuilder protocol = karateProtocol(
        uri("/cats/{id}").nil(),
        uri("/cats").pauseFor(method("get", 10), method("post", 20))
    );

    // Feeder for data-driven tests
    Iterator<Map<String, Object>> feeder = Stream.iterate(0, i -> i + 1)
        .map(i -> Map.<String, Object>of("name", "Cat" + i))
        .iterator();

    // Scenario 1: Basic CRUD
    ScenarioBuilder crud = scenario("CRUD Operations")
        .exec(karateFeature("classpath:features/cats-crud.feature"));

    // Scenario 2: Chained with feeders
    ScenarioBuilder chained = scenario("Chained Operations")
        .feed(feeder)
        .exec(karateSet("name", s -> s.getString("name")))
        .exec(karateFeature("classpath:features/cats-create.feature"))
        .exec(karateFeature("classpath:features/cats-read.feature"));

    // Scenario 3: Silent warm-up
    ScenarioBuilder warmup = scenario("Warm-up")
        .exec(karateFeature("classpath:features/cats-crud.feature").silent());

    {
        setUp(
            warmup.injectOpen(atOnceUsers(1)),
            crud.injectOpen(rampUsers(5).during(5)),
            chained.injectOpen(rampUsers(3).during(5))
        ).protocols(protocol.build());
    }
}
```

### 6.2 Test Features

Port and simplify v1 features:

```gherkin
# features/cats-crud.feature
Feature: CRUD Operations

Scenario: Create and read cat
  Given url baseUrl
  And path 'cats'
  And request { name: 'Fluffy' }
  When method post
  Then status 201
  * def catId = response.id

  Given path 'cats', catId
  When method get
  Then status 200
  And match response.name == 'Fluffy'
```

### 6.3 Mock Server (v2 native)

```java
package io.karatelabs.gatling;

public class MockServer {
    private static Server server;

    public static void start() {
        server = Server.builder()
            .feature("classpath:mock/mock.feature")
            .build();
        System.setProperty("mock.port", String.valueOf(server.getPort()));
    }

    public static void stop() {
        if (server != null) server.stop();
    }
}
```

---

## 7. Features Checklist (V1 Parity)

### Core Features
- [ ] `karateProtocol()` with URI patterns
- [ ] `karateFeature()` with tag selection
- [ ] `karateSet()` for variable injection
- [ ] `pauseFor()` method-specific pauses
- [ ] Custom `nameResolver`
- [ ] `Runner.Builder` exposure via `protocol.runner()`
- [ ] Silent mode (`.silent()`)

### Session Management
- [ ] `__gatling` map passed to Karate
- [ ] `__karate` map returned to Gatling
- [ ] Feature variable chaining
- [ ] Feeder integration

### Metrics & Reporting
- [ ] HTTP request timing to Gatling StatsEngine
- [ ] Status code reporting
- [ ] Error message capture
- [ ] Custom perf event capture (via JsCallable)

### Caching
- [ ] Leverage v2's `Suite.getCallSingleCache()`
- [ ] Leverage v2's `Suite.getCallOnceCache()`

### Configuration
- [ ] `karateEnv` via Runner.Builder
- [ ] `configDir` via Runner.Builder
- [ ] `systemProperty` via Runner.Builder
- [ ] Tag filtering

---

## 8. Implementation Order

### Phase 1: Foundation
1. Create `karate-gatling` module with pom.xml
2. Implement `KarateProtocol` and `KarateProtocolBuilder`
3. Implement `MethodPause` and `KarateUriPattern`

### Phase 2: Core Actions
4. Implement `KarateFeatureAction` with RunListener integration
5. Implement `KarateFeatureBuilder` with `.silent()`
6. Implement `KarateSetAction` and builder
7. Implement `KarateDsl` public API

### Phase 3: Testing
8. Create mock server using v2 native mocks
9. Port test features (simplified)
10. Create `GatlingSimulation` comprehensive test

### Phase 4: Polish
11. Add Scala compatibility layer
12. Port README.md with updated examples
13. Add to parent pom.xml modules
14. CI/CD integration

### Phase 5: Standalone CLI Support (Non-Java Teams)
15. Add `CommandProvider` SPI to karate-core for dynamic subcommand discovery
16. Implement `PerfCommand` in karate-gatling (`karate perf`)
17. Implement dynamic simulation generation from feature files
18. Create `karate-gatling-bundle.jar` fatjar (Gatling + Scala + karate-gatling)
19. Document standalone CLI usage

---

## 9. Package Changes

| V1 Package | V2 Package |
|------------|------------|
| `com.intuit.karate.gatling` | `io.karatelabs.gatling` |
| `com.intuit.karate.gatling.javaapi` | `io.karatelabs.gatling` (merged) |
| `com.intuit.karate.core.ScenarioRuntime` | `io.karatelabs.core.ScenarioRuntime` |
| `com.intuit.karate.http.HttpRequest` | `io.karatelabs.http.HttpRequest` |
| `com.intuit.karate.Runner` | `io.karatelabs.core.Runner` |
| `com.intuit.karate.PerfContext` | Replaced by JsCallable pattern |

---

## 10. Migration Guide (for users)

### Import Changes
```java
// V1
import com.intuit.karate.gatling.javaapi.*;
import static com.intuit.karate.gatling.javaapi.KarateDsl.*;

// V2
import io.karatelabs.gatling.*;
import static io.karatelabs.gatling.KarateDsl.*;
```

### Simulation Class
```java
// No change - still extends Gatling's Simulation
public class MySimulation extends Simulation { ... }
```

### Custom Perf Events
```java
// V1 - works unchanged in V2!
public static void myRpc(Map args, PerfContext ctx) {
    ctx.capturePerfEvent("name", start, end);
}
```
**No changes needed** - just update the import from `com.intuit.karate.PerfContext` to `io.karatelabs.core.PerfContext`.

---

## 11. Files to Create/Modify

### karate-core (modifications)
| File | Purpose |
|------|---------|
| `PerfContext.java` | Interface for custom perf event capture (new) |
| `PerfEvent.java` | Perf event data record (new) |
| `KarateJs.java` | Add `implements PerfContext`, handler methods (modify) |
| `CommandProvider.java` | SPI for dynamic subcommand discovery (new) |
| `Main.java` | Add ServiceLoader discovery for CommandProvider (modify) |

### karate-gatling (new module)
| File | Purpose |
|------|---------|
| `pom.xml` | Maven module configuration |
| `KarateDsl.java` | Public API entry point |
| `KarateProtocol.java` | Gatling protocol |
| `KarateProtocolBuilder.java` | Protocol builder |
| `KarateFeatureAction.java` | Feature execution |
| `KarateFeatureBuilder.java` | Feature action builder |
| `KarateSetAction.java` | Variable injection |
| `KarateUriPattern.java` | URI pattern + pause |
| `MethodPause.java` | Method/pause record |
| `ScalaDsl.scala` | Scala compatibility |
| `GatlingSimulation.java` | Comprehensive test |
| `MockServer.java` | Test mock server |
| `features/*.feature` | Test features |
| `mock/mock.feature` | Mock implementation |
| `README.md` | Documentation |
| `GatlingCommandProvider.java` | ServiceLoader provider for `perf` command (Phase 5) |
| `PerfCommand.java` | CLI command implementation (Phase 5) |
| `DynamicSimulation.java` | Runtime simulation generator (Phase 5) |
| `META-INF/services/...` | ServiceLoader registration (Phase 5) |

---

## 12. Standalone CLI Support (Phase 5)

Enable non-Java teams to run performance tests without Maven/Gradle.

### 12.1 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Rust Launcher (karate binary)                              │
│  - Constructs classpath: karate.jar + ext/*.jar             │
│  - Delegates to Java CLI                                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Java CLI (Main.java)                                       │
│  - Uses ServiceLoader to discover CommandProvider           │
│  - Finds GatlingCommandProvider from karate-gatling-bundle  │
│  - Registers 'perf' subcommand                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  PerfCommand (in karate-gatling)                            │
│  - Generates dynamic Gatling simulation from features       │
│  - Runs Gatling with specified load profile                 │
└─────────────────────────────────────────────────────────────┘
```

### 12.2 CommandProvider SPI (in karate-core)

```java
// io/karatelabs/cli/CommandProvider.java
package io.karatelabs.cli;

/**
 * SPI for modules to register CLI subcommands.
 * Discovered via ServiceLoader when JARs are on classpath.
 */
public interface CommandProvider {
    String getName();           // e.g., "perf"
    String getDescription();    // e.g., "Run performance tests"
    Object getCommand();        // PicoCLI command instance
}
```

```java
// In Main.java - discover and register commands
ServiceLoader<CommandProvider> providers = ServiceLoader.load(CommandProvider.class);
for (CommandProvider provider : providers) {
    spec.addSubcommand(provider.getName(), provider.getCommand());
}
```

### 12.3 PerfCommand (in karate-gatling)

```java
@Command(name = "perf", description = "Run performance tests with Gatling")
public class PerfCommand implements Callable<Integer> {

    @Parameters(description = "Feature files or directories")
    List<String> paths;

    @Option(names = {"-u", "--users"}, description = "Number of concurrent users")
    int users = 1;

    @Option(names = {"-d", "--duration"}, description = "Test duration (e.g., 30s, 5m)")
    String duration = "30s";

    @Option(names = {"-r", "--ramp"}, description = "Ramp-up time (e.g., 10s)")
    String rampUp = "0s";

    @Option(names = {"-t", "--tags"}, description = "Tag expression filter")
    String tags;

    @Option(names = {"--simulation"}, description = "Custom simulation class (power users)")
    String simulationClass;

    @Option(names = {"-o", "--output"}, description = "Output directory")
    String outputDir = "target/gatling";

    @Override
    public Integer call() {
        if (simulationClass != null) {
            // Power user: run custom simulation
            return runCustomSimulation(simulationClass);
        }
        // Generate dynamic simulation from features
        return runDynamicSimulation(paths, users, duration, rampUp, tags);
    }
}
```

### 12.4 Dynamic Simulation Generation

```java
// Generates Gatling simulation at runtime from feature files
public class DynamicSimulation extends Simulation {

    public DynamicSimulation() {
        // Read config from system properties (set by PerfCommand)
        String[] paths = System.getProperty("karate.perf.paths").split(",");
        int users = Integer.getInteger("karate.perf.users", 1);
        Duration duration = parseDuration(System.getProperty("karate.perf.duration", "30s"));
        Duration rampUp = parseDuration(System.getProperty("karate.perf.rampUp", "0s"));
        String tags = System.getProperty("karate.perf.tags");

        KarateProtocolBuilder protocol = karateProtocol();

        ScenarioBuilder scenario = scenario("Performance Test")
            .exec(karateFeature(paths).tags(tags));

        setUp(
            scenario.injectOpen(
                rampUsers(users).during(rampUp),
                constantUsersPerSec(users).during(duration)
            )
        ).protocols(protocol.build());
    }
}
```

### 12.5 CLI Usage Examples

```bash
# Setup: Download and install the Gatling bundle
# Option A: Manual download
curl -L https://github.com/karatelabs/karate/releases/download/v2.0.0/karate-gatling-bundle.jar \
  -o ~/.karate/ext/karate-gatling-bundle.jar

# Option B: Via karate CLI (future)
karate plugin install gatling

# Run performance tests
karate perf features/api.feature

# With load profile
karate perf --users 10 --duration 60s --ramp 10s features/

# Filter by tags
karate perf --users 5 --duration 30s -t @smoke features/

# Custom simulation (power users)
karate perf --simulation com.example.MySimulation
```

### 12.6 karate-pom.json Support

```json
{
  "paths": ["features/"],
  "tags": ["@api"],
  "perf": {
    "users": 10,
    "duration": "60s",
    "rampUp": "10s",
    "output": "target/gatling-reports"
  }
}
```

```bash
# Reads perf config from karate-pom.json
karate perf
```

### 12.7 Bundle JAR Build

```xml
<!-- In karate-gatling/pom.xml -->
<profile>
    <id>bundle</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <configuration>
                    <artifactSet>
                        <includes>
                            <include>io.karatelabs:karate-gatling</include>
                            <include>io.gatling:*</include>
                            <include>io.gatling.highcharts:*</include>
                            <include>org.scala-lang:*</include>
                            <!-- Gatling transitive deps -->
                        </includes>
                        <excludes>
                            <!-- Don't include karate-core - user already has it -->
                            <exclude>io.karatelabs:karate-core</exclude>
                            <exclude>io.karatelabs:karate-js</exclude>
                        </excludes>
                    </artifactSet>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

Build command:
```bash
mvn package -Pbundle -DskipTests
# Output: karate-gatling/target/karate-gatling-bundle.jar
```

### 12.8 Memory Configuration

Gatling may need more memory for high user counts. Users configure via:

```json
// ~/.karate/karate-cli.json
{
  "jvm_opts": "-Xmx2g"
}
```

Or PerfCommand could auto-adjust based on user count:
```java
// In PerfCommand
if (users > 100) {
    System.setProperty("karate.jvm.opts.extra", "-Xmx2g");
}
```

### 12.9 JRE Compatibility

JustJ (bundled JRE) provides Java 21 which is fully compatible with:
- Gatling 3.12.x (requires Java 11+) ✓
- Scala 3.x (requires Java 11+) ✓

No special JRE configuration needed.

### 12.10 CLI Testing Strategy

Testing the standalone CLI requires building JARs and manual verification.

#### Test Setup Script

Create `etc/test-gatling-cli.sh`:

```bash
#!/bin/bash
set -e

# Build all modules
echo "Building karate-core fatjar..."
mvn clean package -DskipTests -Pfatjar -pl karate-core -am

echo "Building karate-gatling bundle..."
mvn package -DskipTests -Pbundle -pl karate-gatling

# Setup test environment
TEST_HOME="home/.karate"
mkdir -p "$TEST_HOME/ext"

# Copy JARs
cp karate-core/target/karate.jar "$TEST_HOME/dist/"
cp karate-gatling/target/karate-gatling-bundle.jar "$TEST_HOME/ext/"

echo "Test environment ready at $TEST_HOME"
```

#### Manual Test Scenarios

| Test | Command | Expected |
|------|---------|----------|
| Help displayed | `karate perf --help` | Shows perf command options |
| Basic run | `karate perf home/test-project/features/hello.feature` | Runs Gatling, generates report |
| With users | `karate perf -u 5 features/` | 5 concurrent users |
| With duration | `karate perf -u 3 -d 10s features/` | Runs for 10 seconds |
| With ramp | `karate perf -u 10 -r 5s -d 30s features/` | 5s ramp to 10 users |
| With tags | `karate perf -t @smoke features/` | Filters by tag |
| From pom | `karate perf` (with karate-pom.json) | Reads perf config from pom |
| Report output | Check `target/gatling/` | HTML report generated |

#### Test Project Structure

```
home/test-project/
├── karate-pom.json
├── karate-config.js
├── features/
│   ├── hello.feature      # Simple GET request
│   └── crud.feature       # CRUD operations
└── mock/
    └── mock.feature       # Mock server
```

#### Sample karate-pom.json for Testing

```json
{
  "paths": ["features/"],
  "perf": {
    "users": 5,
    "duration": "20s",
    "rampUp": "5s"
  }
}
```

#### Test with Claude Code

When developing, use Claude Code to:

1. **Build and test incrementally:**
   ```
   # Ask Claude to build and run a specific test
   "Build the gatling module and test karate perf --help"
   ```

2. **Verify Gatling reports:**
   ```
   # Ask Claude to check report output
   "Run karate perf with 3 users for 10s and verify the HTML report was generated"
   ```

3. **Debug issues:**
   ```
   # If something fails
   "The karate perf command failed with [error]. Check the classpath and ServiceLoader registration"
   ```

#### Verification Checklist

- [ ] `karate --help` shows `perf` subcommand when bundle JAR present
- [ ] `karate perf --help` shows all options
- [ ] Basic feature execution works
- [ ] Load profile options work (users, duration, ramp)
- [ ] Tag filtering works
- [ ] karate-pom.json perf section is read
- [ ] Gatling HTML reports generated
- [ ] Custom simulation class works (--simulation)
- [ ] Error messages are clear when bundle JAR missing

---

## 13. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Gatling 3.12 API changes | Review Gatling changelog, test thoroughly |
| Bundle JAR size (~50-80MB) | Document size, consider optional download, compress |
| ServiceLoader not finding provider | Test classpath construction, clear error messages |
| Scala 3 compilation issues | Use latest scala-maven-plugin, test cross-compilation |
| Virtual thread edge cases | Fallback to platform threads if issues found |
| RunListener timing accuracy | Compare with v1 PerfHook metrics in testing |
| Session variable conflicts | Document reserved keys, validate on set |