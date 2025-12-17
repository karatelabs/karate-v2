# Karate v2 CLI Design

This document describes the CLI architecture for Karate v2, including subcommand design and integration with the Rust launcher.

> See also: [RUNTIME.md](./RUNTIME.md) | [Rust Launcher Spec](../../karate-cli/docs/spec.md)

---

## Architecture Overview

Karate v2 CLI has a two-tier architecture:

```
┌─────────────────────────────────────────────────────────────┐
│  Rust Launcher (karate binary)                              │
│  - Management commands: setup, update, config, doctor       │
│  - Delegates runtime commands to JVM                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ delegates
┌─────────────────────────────────────────────────────────────┐
│  Java CLI (karate-core Main.java)                           │
│  - Runtime commands: run, mock, mcp, init, clean            │
│  - Receives args from Rust launcher                         │
│  - Uses PicoCLI for parsing                                 │
└─────────────────────────────────────────────────────────────┘
```

**Key principle:** The Rust launcher handles installation/management; the Java CLI handles test execution and runtime features.

---

## Subcommand Design

### Command Hierarchy

```
karate <command> [options] [args]

Runtime Commands (implemented in Java):
  run              Run Karate tests
  mock             Start mock server
  mcp              MCP server commands
  clean            Clean output directories

Management Commands (implemented in Rust):
  setup            First-run wizard
  update           Check for updates
  config           View/edit configuration
  init             Initialize new project (interactive)
  doctor           System diagnostics
  version          Show version info
```

### Java CLI Responsibility

The Java CLI (`io.karatelabs.Main`) implements:

| Command | Description | Status |
|---------|-------------|--------|
| `run` | Run Karate tests | Priority 1 |
| `clean` | Clean output directories | Priority 2 |
| `mock` | Start mock server | Future |
| `mcp` | MCP server mode | Future |

> **Note:** `init` is implemented in Rust (not Java) because it needs to scaffold different project types (Maven, Gradle, standalone) before Java/JVM is involved. See [Rust Launcher Spec](../../karate-cli/docs/spec.md).

---

## The `run` Command

### Synopsis

```bash
karate run [options] [paths...]
```

### Behavior

1. **With paths:** Run specified feature files/directories
2. **Without paths:** Look for `karate.json` in current directory
3. **With `--config`:** Use specified config file

### Options

| Option | Description |
|--------|-------------|
| `-t, --tags <expr>` | Tag expression filter (e.g., `@smoke`, `~@slow`) |
| `-T, --threads <n>` | Parallel thread count (default: 1) |
| `-e, --env <name>` | Karate environment (karate.env) |
| `-n, --name <regex>` | Scenario name filter |
| `-o, --output <dir>` | Output directory (default: target/karate-reports) |
| `-w, --workdir <dir>` | Working directory for relative paths |
| `-g, --configdir <dir>` | Directory containing karate-config.js |
| `-c, --config <file>` | Path to karate.json config file |
| `-C, --clean` | Clean output directory before running |
| `-D, --dryrun` | Parse but don't execute |
| `--no-color` | Disable colored output |

### Examples

```bash
# Run with auto-detected karate.json
karate run

# Run specific paths
karate run src/test/features

# Run with explicit config
karate run --config karate.json

# Run with options
karate run -t @smoke -e dev -T 5 src/test/features

# Run from different working directory
karate run -w /home/user/project src/test/features
```

---

## Configuration File

### Canonical Name: `karate.json`

The recommended configuration file name is `karate.json`. When `karate run` is invoked without paths, it automatically loads `karate.json` from the current directory.

### Schema

```json
{
  "paths": ["src/test/features/", "classpath:features/"],
  "tags": ["@smoke", "~@slow"],
  "env": "dev",
  "threads": 5,
  "scenarioName": ".*login.*",
  "configDir": "src/test/resources",
  "workingDir": "/home/user/project",
  "dryRun": false,
  "clean": false,
  "output": {
    "dir": "target/karate-reports",
    "html": true,
    "junitXml": false,
    "cucumberJson": false,
    "ndjson": false
  }
}
```

### Precedence

CLI arguments override config file values:

```
CLI flags → karate.json → defaults
```

---

## Implementation Plan

### Phase 1: Subcommand Refactoring

Refactor `Main.java` to use PicoCLI subcommands:

```java
@Command(
    name = "karate",
    subcommands = {
        RunCommand.class,
        CleanCommand.class,
    }
)
public class Main implements Callable<Integer> {

    @Parameters(arity = "0..*", hidden = true)
    List<String> unknownArgs;

    @Override
    public Integer call() {
        // No subcommand specified
        if (unknownArgs != null && !unknownArgs.isEmpty()) {
            // Legacy: treat args as paths, delegate to run
            return new RunCommand().runWithPaths(unknownArgs);
        }
        // Look for karate.json in cwd
        if (Files.exists(Path.of("karate.json"))) {
            return new RunCommand().call();
        }
        // Show help
        CommandLine.usage(this, System.out);
        return 0;
    }
}
```

### Phase 2: RunCommand

Extract current `Main.java` logic into `RunCommand`:

```java
@Command(name = "run", description = "Run Karate tests")
public class RunCommand implements Callable<Integer> {

    @Parameters(description = "Feature files or directories", arity = "0..*")
    List<String> paths;

    @Option(names = {"-c", "--config"}, description = "Config file (default: karate.json)")
    String configFile = "karate.json";

    // ... other options ...

    @Override
    public Integer call() {
        // If no paths and config exists, load config
        if ((paths == null || paths.isEmpty()) && Files.exists(Path.of(configFile))) {
            KarateConfig config = KarateConfig.load(configFile);
            // ... apply config and run
        }
        // ... rest of execution
    }
}
```

### Phase 3: CleanCommand

```java
@Command(name = "clean", description = "Clean output directories")
public class CleanCommand implements Callable<Integer> {

    @Option(names = {"-o", "--output"}, description = "Output directory to clean")
    String outputDir = "target/karate-reports";

    @Override
    public Integer call() {
        // Delete output directory
        return 0;
    }
}
```

---

## Backward Compatibility

### Legacy Behavior

For backward compatibility, bare arguments (no subcommand) are treated as paths:

| Invocation | Interpretation |
|------------|----------------|
| `karate run src/test` | Explicit run command |
| `karate src/test` | Legacy → delegates to `run` |
| `karate` | Auto-load `karate.json` or show help |
| `karate -t @smoke src/test` | Legacy with options → delegates to `run` |

### Migration Path

1. **v2.0:** Support both `karate run` and legacy `karate <paths>`
2. **v2.1+:** Deprecation warnings for legacy usage
3. **v3.0:** Consider removing legacy support

---

## Integration with Rust Launcher

The Rust launcher (see [spec.md](../../karate-cli/docs/spec.md)) delegates runtime commands to the Java CLI:

```
karate run src/test/features -t @smoke
    │
    ▼ Rust launcher
java -jar ~/.karate/dist/karate-2.0.0.jar run src/test/features -t @smoke
    │
    ▼ Java CLI
Main.java parses and executes
```

### Classpath Construction

The Rust launcher constructs the classpath:
- Karate fatjar (`~/.karate/dist/karate-X.X.X.jar`)
- Extension JARs (`~/.karate/ext/*.jar`)
- Project-local extensions (`.karate/ext/*.jar`)

### JVM Options

Configured via `karate-cli.json`:
```json
{
  "jvm_opts": "-Xmx512m"
}
```

---

## Future Commands (Java)

### `karate mock`

Start a mock server:

```bash
karate mock --port 8080 mocks/
```

### `karate mcp`

Start MCP server mode for LLM integration:

```bash
karate mcp --stdio
```

> **Note:** `karate init` is implemented in Rust. See [Rust Launcher Spec](../../karate-cli/docs/spec.md) for details.

---

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success (all tests passed) |
| `1` | Test failures |
| `2` | Configuration error |
| `3` | Runtime error |

---

## File Structure

After subcommand refactoring:

```
io.karatelabs.core/
├── Main.java              # Parent command, delegates to subcommands
├── RunCommand.java        # karate run
├── CleanCommand.java      # karate clean
├── KarateConfig.java      # JSON config parsing
├── Runner.java            # Programmatic API
└── ...
```

---

## Manual CLI Testing

### Setup

A test script is provided at `etc/test-cli.sh`. The `home/` folder is gitignored for test workspaces.

**Build project:**
```bash
# Standard build
mvn clean install -DskipTests

# Build fatjar (for simpler testing)
mvn clean package -DskipTests -Pfatjar
```

**Test workspace** (already set up in `home/test-project/`):
```
home/test-project/
├── karate.json           # Test config
└── features/
    └── hello.feature     # Test feature with @smoke, @api tags
```

### Running CLI Commands

**Option 1: Using test-cli.sh (recommended)**

The script auto-detects fatjar or builds classpath:
```bash
# Help
./etc/test-cli.sh --help

# Run tests
./etc/test-cli.sh home/test-project/features

# Run with workdir
./etc/test-cli.sh -w home/test-project features

# Run with config
./etc/test-cli.sh -c home/test-project/karate.json
```

**Option 2: Using fatjar directly**
```bash
# Build fatjar first
mvn package -DskipTests -Pfatjar

# Run
java -jar karate-core/target/karate.jar --help
java -jar karate-core/target/karate.jar home/test-project/features
```

**Option 3: Using mvn exec:java**
```bash
cd karate-core
mvn exec:java -Dexec.mainClass="io.karatelabs.Main" \
  -Dexec.args="--help"
```

### Fatjar Build

The fatjar profile is configured in `karate-core/pom.xml`:

```bash
# Build fatjar
mvn package -DskipTests -Pfatjar

# Output: karate-core/target/karate.jar
```

### Test Scenarios

| Test | Command | Expected |
|------|---------|----------|
| Help | `--help` | Shows usage with all options |
| Version | `--version` | Shows "Karate 2.0" |
| Run with paths | `home/test-project/features` | Executes tests, reports to default dir |
| Run with config | `-c home/test-project/karate.json` | Loads config, uses config paths |
| Run with workdir | `-w home/test-project features` | Clean relative paths in reports |
| Run with env | `-e dev features` | Sets karate.env |
| Run with tags | `-t @smoke features` | Filters by tag |
| Dry run | `-D features` | Parses but doesn't execute |
| Clean | `-C -o home/test-project/target features` | Cleans output before run |

### Verify Output

After running tests, verify:

```bash
# Reports generated
ls -la home/test-project/target/reports/

# Expected files
# - karate-summary.json
# - karate-summary.html
# - features/*.html (per-feature reports)

# Check relative paths in report (should NOT have ugly ../../../ paths)
grep "relativePath" home/test-project/target/reports/karate-summary.json
```

### Cleanup

```bash
rm -rf home/test-project/target
```

---

## References

- [Rust Launcher Spec](../../karate-cli/docs/spec.md) - Full architecture for Rust-based CLI launcher
- [RUNTIME.md](./RUNTIME.md) - Runtime architecture and feature implementation status
- [PicoCLI Subcommands](https://picocli.info/#_subcommands) - PicoCLI documentation
