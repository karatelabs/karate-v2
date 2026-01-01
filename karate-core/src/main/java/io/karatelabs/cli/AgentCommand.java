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

import io.karatelabs.driver.AgentDriver;
import io.karatelabs.driver.Driver;
import io.karatelabs.driver.cdp.CdpDriver;
import io.karatelabs.driver.cdp.CdpDriverOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * The 'agent' subcommand for LLM browser automation.
 * <p>
 * Starts a browser and exposes a 5-method API over stdio for LLM orchestration.
 * <p>
 * Usage examples:
 * <pre>
 * # Start agent with headed browser
 * karate agent
 *
 * # Start agent with headless browser
 * karate agent --headless
 *
 * # Start agent with logging
 * karate agent --headless --log agent.log
 * </pre>
 * <p>
 * Protocol:
 * - Input (stdin):  {"command":"eval","payload":"agent.look()"}
 * - Output (stdout): {"command":"result","payload":{...}}
 * <p>
 * See docs/DRIVER_AGENT.md for full protocol specification.
 */
@Command(
        name = "agent",
        mixinStandardHelpOptions = true,
        description = "Start browser agent for LLM automation"
)
public class AgentCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(AgentCommand.class);

    @Option(
            names = {"--headless"},
            description = "Run browser in headless mode"
    )
    boolean headless;

    @Option(
            names = {"--browser"},
            description = "Browser type (default: chrome)",
            defaultValue = "chrome"
    )
    String browser;

    @Option(
            names = {"--log"},
            description = "Log file for debug output"
    )
    String logFile;

    @Option(
            names = {"--sidebar"},
            description = "Show command sidebar in browser (for debugging)"
    )
    boolean sidebar;

    private Driver driver;

    @Override
    public Integer call() {
        try {
            // Redirect debug output to log file if specified
            if (logFile != null) {
                try {
                    PrintStream logStream = new PrintStream(new FileOutputStream(logFile, true));
                    // Note: This is a simple approach. For production, consider proper logging config.
                    System.setErr(logStream);
                } catch (Exception e) {
                    // If log file fails, continue without it
                    logger.warn("Failed to open log file: {}", e.getMessage());
                }
            }

            // Build driver options
            CdpDriverOptions.Builder optionsBuilder = CdpDriverOptions.builder()
                    .headless(headless);

            // Use temp user data dir for non-headless mode to avoid conflicts with existing Chrome
            if (!headless) {
                String tempDir = System.getProperty("java.io.tmpdir") + "/karate-agent-" + System.currentTimeMillis();
                optionsBuilder.userDataDir(tempDir);
            }

            // TODO: Support Firefox when available
            if (!"chrome".equalsIgnoreCase(browser) && !"chromium".equalsIgnoreCase(browser)) {
                System.err.println("Warning: Only Chrome/Chromium is currently supported. Using Chrome.");
            }

            CdpDriverOptions options = optionsBuilder.build();

            // Launch browser
            logger.debug("Launching browser (headless={})", headless);
            driver = CdpDriver.start(options);

            // Add shutdown hook to clean up browser
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));

            // Create agent wrapper
            AgentDriver agent = new AgentDriver(driver, sidebar);

            // Start stdio protocol handler
            AgentStdio stdio = new AgentStdio(agent);
            stdio.run();

            // Clean exit
            cleanup();
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.error("Agent command failed", e);
            cleanup();
            return 2;
        }
    }

    private void cleanup() {
        if (driver != null && !driver.isTerminated()) {
            try {
                logger.debug("Closing browser");
                driver.quit();
            } catch (Exception e) {
                logger.debug("Error closing browser: {}", e.getMessage());
            }
        }
    }
}
