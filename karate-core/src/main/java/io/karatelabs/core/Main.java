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
package io.karatelabs.core;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command-line interface for running Karate tests.
 * <p>
 * Usage examples:
 * <pre>
 * # Run all tests in a directory
 * java -jar karate.jar src/test/resources
 *
 * # Run with specific tags and environment
 * java -jar karate.jar -t @smoke -e dev src/test/resources
 *
 * # Run with multiple threads
 * java -jar karate.jar -T 5 src/test/resources
 * </pre>
 */
@Command(
        name = "karate",
        mixinStandardHelpOptions = true,
        version = "Karate 2.0",
        description = "Run Karate API tests"
)
public class Main implements Callable<Integer> {

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
            description = "Number of parallel threads (default: 1)",
            defaultValue = "1"
    )
    int threads = 1;

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
    String outputDir = "target/karate-reports";

    @Option(
            names = {"-g", "--configdir"},
            description = "Directory containing karate-config.js"
    )
    String configDir;

    @Option(
            names = {"-C", "--clean"},
            description = "Clean output directory before running"
    )
    boolean clean;

    @Option(
            names = {"-D", "--dryrun"},
            description = "Dry run mode (parse but don't execute)"
    )
    boolean dryRun;

    @Option(
            names = {"--no-color"},
            description = "Disable colored output"
    )
    boolean noColor;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // Handle color settings
        if (noColor) {
            Console.setColorsEnabled(false);
        }

        // Print header
        Console.println();
        Console.println(Console.bold("Karate v2"));
        Console.println();

        // Clean output directory if requested
        if (clean) {
            cleanOutputDir();
        }

        // Check if paths are provided
        if (paths == null || paths.isEmpty()) {
            Console.println(Console.yellow("No test paths specified."));
            Console.println("Usage: karate [options] <paths...>");
            Console.println("Run 'karate --help' for more information.");
            return 0;
        }

        // Build and run
        try {
            Runner.Builder builder = Runner.builder()
                    .path(paths)
                    .outputDir(outputDir)
                    .dryRun(dryRun);

            if (env != null) {
                builder.karateEnv(env);
            }

            if (tags != null && !tags.isEmpty()) {
                builder.tags(tags.toArray(new String[0]));
            }

            if (scenarioName != null) {
                builder.scenarioName(scenarioName);
            }

            if (configDir != null) {
                builder.configDir(configDir);
            }

            // Run tests
            SuiteResult result = builder.parallel(threads);

            // Return exit code based on test results
            return result.isFailed() ? 1 : 0;

        } catch (Exception e) {
            Console.println(Console.fail("Error: " + e.getMessage()));
            e.printStackTrace();
            return 1;
        }
    }

    private void cleanOutputDir() {
        Path output = Path.of(outputDir);
        if (Files.exists(output)) {
            try {
                deleteDirectory(output.toFile());
                Console.println(Console.info("Cleaned: " + outputDir));
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

    public int getThreads() {
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

    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Parse command-line arguments without executing.
     * Useful for integrating with other tools.
     */
    public static Main parse(String... args) {
        Main main = new Main();
        new CommandLine(main).parseArgs(args);
        return main;
    }

}
