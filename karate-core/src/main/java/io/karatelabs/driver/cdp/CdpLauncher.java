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
package io.karatelabs.driver.cdp;

import io.karatelabs.common.OsUtils;
import io.karatelabs.output.LogContext;
import io.karatelabs.process.PortUtils;
import io.karatelabs.process.ProcessBuilder;
import io.karatelabs.process.ProcessHandle;
import net.minidev.json.JSONValue;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDP-based browser launcher.
 * Manages Chrome browser process lifecycle.
 * Launches Chrome with CDP debugging enabled and provides WebSocket URL for connection.
 */
public class CdpLauncher {

    private static final Logger logger = LogContext.RUNTIME_LOGGER;

    // Chrome executable paths (same as v1)
    public static final String DEFAULT_PATH_MAC = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    public static final String DEFAULT_PATH_WIN64 = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
    public static final String DEFAULT_PATH_WIN32 = "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe";
    public static final String DEFAULT_PATH_LINUX = "/usr/bin/google-chrome";

    // Track active launchers for cleanup
    private static final Set<CdpLauncher> ACTIVE = ConcurrentHashMap.newKeySet();

    private final ProcessHandle process;
    private final String host;
    private final int port;
    private String webSocketUrl;
    private volatile boolean closed = false;

    private CdpLauncher(ProcessHandle process, String host, int port) {
        this.process = process;
        this.host = host;
        this.port = port;
        ACTIVE.add(this);
    }

    /**
     * Close all active browser instances.
     */
    public static void closeAll() {
        ACTIVE.forEach(CdpLauncher::close);
    }

    /**
     * Launch Chrome browser and return launcher instance.
     */
    public static CdpLauncher start(CdpDriverOptions options) {
        String executable = resolveExecutable(options.getExecutable());
        int port = options.getPort() > 0 ? options.getPort() : PortUtils.findFreePort();
        String host = options.getHost();

        List<String> args = buildArgs(executable, port, options);
        logger.debug("launching chrome: {}", args);

        ProcessHandle process = ProcessHandle.start(
                ProcessBuilder.create()
                        .args(args)
                        .logToContext(false) // don't log browser output to test context
                        .build()
        );

        CdpLauncher launcher = new CdpLauncher(process, host, port);

        // Wait for Chrome to be ready
        if (!launcher.waitForReady(options.getTimeout())) {
            launcher.close();
            throw new RuntimeException("chrome failed to start within timeout");
        }

        // Fetch WebSocket URL
        launcher.webSocketUrl = fetchWebSocketUrl(host, port);
        if (launcher.webSocketUrl == null) {
            launcher.close();
            throw new RuntimeException("failed to get WebSocket URL from chrome");
        }

        logger.info("chrome started on port {} with WebSocket: {}", port, launcher.webSocketUrl);
        return launcher;
    }

    /**
     * Get WebSocket URL from existing browser at host:port.
     */
    public static String getWebSocketUrl(String host, int port) {
        return fetchWebSocketUrl(host, port);
    }

    private static String resolveExecutable(String configured) {
        if (configured != null && !configured.isEmpty()) {
            if (Files.isExecutable(Path.of(configured))) {
                return configured;
            }
            logger.warn("configured executable not found: {}", configured);
        }

        String defaultPath = getDefaultPath();
        if (defaultPath != null && Files.isExecutable(Path.of(defaultPath))) {
            logger.debug("using default chrome path: {}", defaultPath);
            return defaultPath;
        }

        throw new RuntimeException("chrome executable not found. " +
                "Set 'executable' option or install Chrome at: " + defaultPath);
    }

    private static String getDefaultPath() {
        if (OsUtils.isMac()) {
            return DEFAULT_PATH_MAC;
        } else if (OsUtils.isWindows()) {
            // Prefer 64-bit, fall back to 32-bit
            if (Files.isRegularFile(Path.of(DEFAULT_PATH_WIN64)) &&
                    Files.isReadable(Path.of(DEFAULT_PATH_WIN64))) {
                return DEFAULT_PATH_WIN64;
            }
            return DEFAULT_PATH_WIN32;
        } else if (OsUtils.isLinux()) {
            return DEFAULT_PATH_LINUX;
        }
        return null;
    }

    private static List<String> buildArgs(String executable, int port, CdpDriverOptions options) {
        List<String> args = new ArrayList<>();
        args.add(executable);
        args.add("--remote-debugging-port=" + port);
        args.add("--remote-allow-origins=*");
        args.add("--no-first-run");
        args.add("--disable-popup-blocking");

        if (options.getUserDataDir() != null) {
            args.add("--user-data-dir=" + options.getUserDataDir());
        }

        if (options.isHeadless()) {
            args.add("--headless=new");
        }

        if (options.getUserAgent() != null) {
            args.add("--user-agent=" + options.getUserAgent());
        }

        // Add any extra options
        if (options.getAddOptions() != null) {
            args.addAll(options.getAddOptions());
        }

        return args;
    }

    private boolean waitForReady(int timeoutMs) {
        String url = "http://" + host + ":" + port + "/json/version";
        int attempts = timeoutMs / 250;
        return PortUtils.waitForHttp(url, attempts, 250, process::isAlive);
    }

    @SuppressWarnings("unchecked")
    private static String fetchWebSocketUrl(String host, int port) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            // First try /json to get page targets
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/json"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                List<Map<String, Object>> targets = (List<Map<String, Object>>) JSONValue.parse(response.body());
                if (targets != null && !targets.isEmpty()) {
                    for (Map<String, Object> target : targets) {
                        String targetUrl = (String) target.get("url");
                        String targetType = (String) target.get("type");

                        // Skip chrome:// pages
                        if (targetUrl != null && targetUrl.startsWith("chrome-")) {
                            continue;
                        }

                        // Only connect to page targets
                        if (!"page".equals(targetType)) {
                            continue;
                        }

                        String wsUrl = (String) target.get("webSocketDebuggerUrl");
                        if (wsUrl != null) {
                            return wsUrl;
                        }
                    }
                }
            }

            // Fallback to /json/version for browser-level WebSocket
            request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/json/version"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> version = (Map<String, Object>) JSONValue.parse(response.body());
                if (version != null) {
                    return (String) version.get("webSocketDebuggerUrl");
                }
            }

            return null;
        } catch (Exception e) {
            logger.warn("failed to fetch WebSocket URL: {}", e.getMessage());
            return null;
        }
    }

    // Getters

    public String getWebSocketUrl() {
        return webSocketUrl;
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Close the browser process.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ACTIVE.remove(this);

        if (process != null) {
            logger.debug("closing chrome process");
            process.close();
        }
    }

    /**
     * Close the browser process and wait for it to terminate.
     */
    public void closeAndWait() {
        if (closed) {
            return;
        }
        closed = true;
        ACTIVE.remove(this);

        if (process != null) {
            logger.debug("closing chrome process and waiting");
            process.close();
            try {
                process.waitSync(5000);
            } catch (Exception e) {
                logger.warn("timeout waiting for chrome to close, forcing");
                process.close(true);
            }
        }
    }

}
