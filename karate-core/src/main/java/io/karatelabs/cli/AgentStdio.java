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

import io.karatelabs.common.Json;
import io.karatelabs.driver.AgentDriver;
import io.karatelabs.driver.Keys;
import io.karatelabs.js.Engine;
import io.karatelabs.js.ExternalBridge;
import io.karatelabs.js.JavaType;
import net.minidev.json.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles the JSON-over-stdio protocol for the agent CLI.
 *
 * Protocol:
 * - Input:  {"command":"eval","payload":"<javascript code>"}
 * - Output: {"command":"result","payload":{...}} or {"command":"error","payload":{...}}
 */
public class AgentStdio {

    private static final Logger logger = LoggerFactory.getLogger(AgentStdio.class);

    private final AgentDriver agent;
    private final Engine engine;
    private final BufferedReader stdin;
    private final PrintWriter stdout;

    public AgentStdio(AgentDriver agent) {
        this.agent = agent;
        this.engine = new Engine();
        this.stdin = new BufferedReader(new InputStreamReader(System.in));
        this.stdout = new PrintWriter(new OutputStreamWriter(System.out), true);

        // Enable Java interop in the JS engine
        engine.setExternalBridge(new ExternalBridge() {
        });

        // Bind agent object and Keys class to JS engine (as root bindings so they don't appear in getBindings)
        engine.putRootBinding("agent", agent);
        engine.putRootBinding("Keys", new JavaType(Keys.class));
    }

    /**
     * Send ready signal and start the request/response loop.
     */
    public void run() {
        // Send ready signal
        sendReady();

        // Main loop
        String line;
        try {
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                processRequest(line);
            }
        } catch (IOException e) {
            logger.debug("stdin closed: {}", e.getMessage());
        }
    }

    private void sendReady() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("command", "ready");
        response.put("payload", Map.of());
        sendJson(response);
    }

    private void processRequest(String line) {
        try {
            // Parse request
            @SuppressWarnings("unchecked")
            Map<String, Object> request = (Map<String, Object>) Json.parseLenient(line);

            String command = (String) request.get("command");
            Object payload = request.get("payload");

            if (!"eval".equals(command)) {
                sendError("unknown command: " + command, "UNKNOWN_COMMAND");
                return;
            }

            if (payload == null) {
                sendError("missing payload", "INVALID_ARGS");
                return;
            }

            String js = String.valueOf(payload);
            executeAndRespond(js);

        } catch (Exception e) {
            logger.error("request processing failed", e);
            sendError(e.getMessage(), "PARSE_ERROR");
        }
    }

    private void executeAndRespond(String js) {
        try {
            Object result = engine.eval(js);

            // Check if result contains error (from agent methods)
            if (result instanceof Map<?, ?> map) {
                if (map.containsKey("error")) {
                    String message = String.valueOf(map.get("error"));
                    String code = map.containsKey("code") ? String.valueOf(map.get("code")) : "ERROR";
                    sendError(message, code);
                    return;
                }
            }

            sendResult(result);

        } catch (Exception e) {
            logger.debug("JS eval failed: {}", e.getMessage());
            sendError(e.getMessage(), "EVAL_FAILED");
        }
    }

    private void sendResult(Object payload) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("command", "result");
        response.put("payload", payload != null ? payload : Map.of());
        sendJson(response);
    }

    private void sendError(String message, String code) {
        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("message", message);
        errorPayload.put("code", code);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("command", "error");
        response.put("payload", errorPayload);
        sendJson(response);
    }

    private void sendJson(Map<String, Object> response) {
        String json = JSONValue.toJSONString(response);
        stdout.println(json);
        stdout.flush();
    }
}
