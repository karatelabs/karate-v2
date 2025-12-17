/*
 * The MIT License
 *
 * Copyright 2025 Karate Labs Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.karatelabs.cli;

import io.karatelabs.core.Console;
import io.karatelabs.core.Globals;
import io.karatelabs.core.KarateConfig;
import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The 'run' subcommand for executing Karate tests.
 * <p>
 * Usage examples:
 * <pre>
 * # Run all tests (uses karate-pom.json if present)
 * karate run
 *
 * # Run specific paths (inherits other settings from karate-pom.json)
 * karate run src/test/resources
 *
 * # Run with specific tags and environment
 * karate run -t @smoke -e dev src/test/resources
 *
 * # Run with custom pom file
 * karate run -p custom-pom.json
 *
 * # Run ignoring karate-pom.json
 * karate run --no-pom src/test/resources
 * </pre>
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Run Karate tests"
)
public class RunCommand implements Callable<Integer> {

    public static final String DEFAULT_POM_FILE = "karate-pom.json";

    @Parameters(
            description = "Feature files or directories to run",
            arity = "0..*"
    )
    List<String> paths;

    @Option(
            names = {"-t", "--tags"},
            description = "Tag expression to filter scenarios (e.g., '@smoke', '~@slow')"
    )
    List<String> tags;

    @Option(
            names = {"-T", "--threads"},
            description = "Number of parallel threads (default: 1)"
    )
    Integer threads;

    @Option(
            names = {"-e", "--env"},
            description = "Value of 'karate.env'"
    )
    String env;

    @Option(
            names = {"-n", "--name"},
            description = "Scenario name filter (regex)"
    )
    String scenarioName;

    @Option(
            names = {"-o", "--output"},
            description = "Output directory for reports (default: target/karate-reports)"
    )
    String outputDir;

    @Option(
            names = {"-g", "--configdir"},
            description = "Directory containing karate-config.js"
    )
    String configDir;

    @Option(
            names = {"-w", "--workdir"},
            description = "Working directory for relative path resolution (default: current directory)"
    )
    String workingDir;

    @Option(
            names = {"-C", "--clean"},
            description = "Clean output directory before running"
    )
    Boolean clean;

    @Option(
            names = {"-D", "--dryrun"},
            description = "Dry run mode (parse but don't execute)"
    )
    Boolean dryRun;

    @Option(
            names = {"-p", "--pom"},
            description = "Path to project file (default: karate-pom.json)"
    )
    String pomFile;

    @Option(
            names = {"--no-pom"},
            description = "Ignore karate-pom.json even if present"
    )
    boolean noPom;

    // Loaded pom config
    private KarateConfig pom;

    @Override
    public Integer call() {
        // Print header
        Console.println();
        Console.println(Console.bold("Karate " + Globals.KARATE_VERSION));
        Console.println();

        // Load pom file (unless --no-pom)
        if (!noPom) {
            loadPom();
        }

        // Resolve effective values (CLI overrides pom)
        List<String> effectivePaths = resolvePaths();
        String effectiveOutputDir = resolveOutputDir();
        String effectiveWorkingDir = resolveWorkingDir();
        int effectiveThreads = resolveThreads();
        boolean effectiveClean = resolveClean();
        boolean effectiveDryRun = resolveDryRun();

        // Clean output directory if requested
        if (effectiveClean) {
            cleanOutputDir(effectiveOutputDir, effectiveWorkingDir);
        }

        // Check if paths are provided
        if (effectivePaths == null || effectivePaths.isEmpty()) {
            Console.println(Console.yellow("No test paths specified."));
            Console.println("Usage: karate run [options] <paths...>");
            Console.println("       karate run -p karate-pom.json");
            Console.println("Run 'karate run --help' for more information.");
            return 0;
        }

        // Build and run
        try {
            Runner.Builder builder = Runner.builder();

            // Apply pom settings first (if present)
            if (pom != null) {
                pom.applyTo(builder);
            }

            // CLI options override pom
            builder.path(effectivePaths);
            builder.outputDir(effectiveOutputDir);

            if (effectiveDryRun) {
                builder.dryRun(true);
            }

            if (env != null) {
                builder.karateEnv(env);
            } else if (pom != null && pom.getEnv() != null) {
                builder.karateEnv(pom.getEnv());
            }

            if (tags != null && !tags.isEmpty()) {
                builder.tags(tags.toArray(new String[0]));
            } else if (pom != null && !pom.getTags().isEmpty()) {
                builder.tags(pom.getTags().toArray(new String[0]));
            }

            if (scenarioName != null) {
                builder.scenarioName(scenarioName);
            } else if (pom != null && pom.getScenarioName() != null) {
                builder.scenarioName(pom.getScenarioName());
            }

            if (configDir != null) {
                builder.configDir(configDir);
            } else if (pom != null && pom.getConfigDir() != null) {
                builder.configDir(pom.getConfigDir());
            }

            if (effectiveWorkingDir != null) {
                builder.workingDir(effectiveWorkingDir);
            }

            // Run tests
            SuiteResult result = builder.parallel(effectiveThreads);

            // Return exit code based on test results
            return result.isFailed() ? 1 : 0;

        } catch (Exception e) {
            Console.println(Console.fail("Error: " + e.getMessage()));
            e.printStackTrace();
            return 3;
        }
    }

    private void loadPom() {
        String file = pomFile != null ? pomFile : DEFAULT_POM_FILE;
        Path pomPath;

        // If workdir specified, look for pom there
        if (workingDir != null) {
            pomPath = Path.of(workingDir).resolve(file);
        } else {
            pomPath = Path.of(file);
        }

        if (Files.exists(pomPath)) {
            try {
                pom = KarateConfig.load(pomPath);
                Console.println(Console.info("Loaded: " + pomPath));
            } catch (Exception e) {
                Console.println(Console.warn("Failed to load pom: " + e.getMessage()));
            }
        }
    }

    private List<String> resolvePaths() {
        if (paths != null && !paths.isEmpty()) {
            return paths;
        }
        if (pom != null && !pom.getPaths().isEmpty()) {
            return pom.getPaths();
        }
        return null;
    }

    private String resolveOutputDir() {
        if (outputDir != null) {
            return outputDir;
        }
        if (pom != null && pom.getOutput().getDir() != null) {
            return pom.getOutput().getDir();
        }
        return "target/karate-reports";
    }

    private String resolveWorkingDir() {
        if (workingDir != null) {
            return workingDir;
        }
        if (pom != null && pom.getWorkingDir() != null) {
            return pom.getWorkingDir();
        }
        return null;
    }

    private int resolveThreads() {
        if (threads != null) {
            return threads;
        }
        if (pom != null && pom.getThreads() > 0) {
            return pom.getThreads();
        }
        return 1;
    }

    private boolean resolveClean() {
        if (clean != null) {
            return clean;
        }
        if (pom != null) {
            return pom.isClean();
        }
        return false;
    }

    private boolean resolveDryRun() {
        if (dryRun != null) {
            return dryRun;
        }
        if (pom != null) {
            return pom.isDryRun();
        }
        return false;
    }

    /**
     * Run with paths provided externally (for legacy command handling).
     *
     * @param legacyPaths paths from legacy command invocation
     * @return exit code
     */
    public Integer runWithPaths(List<String> legacyPaths) {
        this.paths = legacyPaths;
        return call();
    }

    private void cleanOutputDir(String dir, String workDir) {
        Path output;
        if (workDir != null) {
            output = Path.of(workDir).resolve(dir);
        } else {
            output = Path.of(dir);
        }

        if (Files.exists(output)) {
            try {
                deleteDirectory(output.toFile());
                Console.println(Console.info("Cleaned: " + output));
            } catch (Exception e) {
                Console.println(Console.warn("Failed to clean output directory: " + e.getMessage()));
            }
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    // ========== Getters for programmatic access ==========

    public List<String> getPaths() {
        return paths;
    }

    public List<String> getTags() {
        return tags;
    }

    public Integer getThreads() {
        return threads;
    }

    public String getEnv() {
        return env;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public String getConfigDir() {
        return configDir;
    }

    public Boolean isDryRun() {
        return dryRun;
    }

    public String getPomFile() {
        return pomFile;
    }

    public String getWorkingDir() {
        return workingDir;
    }

}
