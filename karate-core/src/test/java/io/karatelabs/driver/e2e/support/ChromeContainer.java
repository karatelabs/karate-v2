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
package io.karatelabs.driver.e2e.support;

import io.karatelabs.driver.CdpDriver;
import io.karatelabs.driver.CdpDriverOptions;
import net.minidev.json.JSONValue;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Testcontainers wrapper for Chrome browser using chromedp/headless-shell (~200MB).
 * Provides Docker-based Chrome for reproducible, isolated E2E testing.
 */
public class ChromeContainer extends GenericContainer<ChromeContainer> {

    private static final String IMAGE = "chromedp/headless-shell:latest";
    private static final int CDP_PORT = 9222;

    public ChromeContainer() {
        super(DockerImageName.parse(IMAGE));
        withExposedPorts(CDP_PORT);
        // Pass additional Chrome flags to allow WebSocket connections from any origin
        // The chromedp/headless-shell image's entrypoint script appends these to Chrome
        withCommand("--remote-allow-origins=*");
        waitingFor(Wait.forHttp("/json/version").forPort(CDP_PORT));
        withStartupTimeout(Duration.ofMinutes(2));
    }

    /**
     * Get the WebSocket URL for CDP connection.
     */
    public String getCdpUrl() {
        String host = getHost();
        int port = getMappedPort(CDP_PORT);
        return fetchWebSocketUrl(host, port);
    }

    /**
     * Create a CdpDriver connected to this container.
     */
    public CdpDriver createDriver() {
        return CdpDriver.connect(getCdpUrl());
    }

    /**
     * Create a CdpDriver connected to this container with custom options.
     */
    public CdpDriver createDriver(CdpDriverOptions options) {
        return CdpDriver.connect(getCdpUrl(), options);
    }

    /**
     * Get the base URL for accessing services from inside the container.
     * Use this for navigating to test pages served by TestPageServer.
     */
    public String getHostAccessUrl(int hostPort) {
        // host.testcontainers.internal is automatically mapped to the host
        return "http://host.testcontainers.internal:" + hostPort;
    }

    @SuppressWarnings("unchecked")
    private String fetchWebSocketUrl(String host, int port) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            // First try /json to get existing page targets
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/json"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                List<Map<String, Object>> targets = (List<Map<String, Object>>) JSONValue.parse(response.body());
                if (targets != null && !targets.isEmpty()) {
                    for (Map<String, Object> target : targets) {
                        String targetType = (String) target.get("type");
                        if ("page".equals(targetType)) {
                            String wsUrl = (String) target.get("webSocketDebuggerUrl");
                            if (wsUrl != null) {
                                return wsUrl.replace("localhost", host).replace("127.0.0.1", host);
                            }
                        }
                    }
                }
            }

            // No page targets exist - create a new one
            request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/json/new?about:blank"))
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> newTarget = (Map<String, Object>) JSONValue.parse(response.body());
                if (newTarget != null) {
                    String wsUrl = (String) newTarget.get("webSocketDebuggerUrl");
                    if (wsUrl != null) {
                        return wsUrl.replace("localhost", host).replace("127.0.0.1", host);
                    }
                }
            }

            throw new RuntimeException("Failed to get WebSocket URL from Chrome container");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch WebSocket URL: " + e.getMessage(), e);
        }
    }

}
