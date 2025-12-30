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
package io.karatelabs.driver;

import io.karatelabs.http.WsClient;
import io.karatelabs.http.WsClientOptions;
import io.karatelabs.http.WsException;
import io.karatelabs.http.WsFrame;
import net.minidev.json.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Chrome DevTools Protocol client built on WebSocket.
 * Handles request/response correlation and event subscription.
 */
public class CdpClient {

    private static final Logger logger = LoggerFactory.getLogger(CdpClient.class);

    private final WsClient ws;
    private final AtomicInteger idGenerator = new AtomicInteger();
    private final ConcurrentHashMap<Integer, CompletableFuture<CdpResponse>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<CdpEvent>>> eventHandlers = new ConcurrentHashMap<>();
    private final Duration defaultTimeout;

    // Factory methods

    public static CdpClient connect(String webSocketUrl) {
        return connect(webSocketUrl, Duration.ofSeconds(30));
    }

    public static CdpClient connect(String webSocketUrl, Duration defaultTimeout) {
        WsClientOptions options = WsClientOptions.builder(webSocketUrl)
                .disablePing() // CDP handles its own keepalive
                .build();
        WsClient ws = WsClient.connect(options);
        return new CdpClient(ws, defaultTimeout);
    }

    private CdpClient(WsClient ws, Duration defaultTimeout) {
        this.ws = ws;
        this.defaultTimeout = defaultTimeout;
        setupMessageHandler();
    }

    private void setupMessageHandler() {
        ws.onMessage(frame -> {
            if (frame.isText()) {
                handleMessage(frame.getText());
            }
        });
        ws.onClose(() -> {
            // Complete all pending futures exceptionally
            for (CompletableFuture<CdpResponse> future : pending.values()) {
                future.completeExceptionally(
                        new WsException(WsException.Type.CONNECTION_CLOSED, "websocket closed"));
            }
            pending.clear();
        });
        ws.onError(error -> {
            logger.error("CDP connection error: {}", error.getMessage());
        });
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String json) {
        Map<String, Object> map;
        try {
            map = (Map<String, Object>) JSONValue.parseWithException(json);
        } catch (Exception e) {
            logger.error("failed to parse CDP message: {}", e.getMessage());
            return;
        }

        if (map.containsKey("id")) {
            // Response to a request
            int id = ((Number) map.get("id")).intValue();
            CompletableFuture<CdpResponse> future = pending.remove(id);
            if (future != null) {
                CdpResponse response = new CdpResponse(map);
                future.complete(response);
            } else {
                logger.warn("received response for unknown request id: {}", id);
            }
        } else if (map.containsKey("method")) {
            // Event
            String method = (String) map.get("method");
            CdpEvent event = new CdpEvent(map);
            dispatchEvent(method, event);
        }
    }

    private void dispatchEvent(String method, CdpEvent event) {
        List<Consumer<CdpEvent>> handlers = eventHandlers.get(method);
        if (handlers != null) {
            for (Consumer<CdpEvent> handler : handlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    logger.error("event handler error for {}: {}", method, e.getMessage());
                }
            }
        }
    }

    // Message creation

    public int nextId() {
        return idGenerator.incrementAndGet();
    }

    /**
     * Create a new CDP message builder.
     */
    public CdpMessage method(String method) {
        return new CdpMessage(this, nextId(), method);
    }

    // Send methods

    /**
     * Blocking send with response.
     */
    CdpResponse send(CdpMessage message) {
        try {
            return sendAsync(message).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new RuntimeException("CDP timeout for: " + message.getMethod());
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("CDP error: " + cause.getMessage(), cause);
        }
    }

    /**
     * Async send with response tracking.
     */
    CompletableFuture<CdpResponse> sendAsync(CdpMessage message) {
        CompletableFuture<CdpResponse> future = new CompletableFuture<>();
        pending.put(message.getId(), future);

        String json = message.toJson();
        logger.trace(">>> {}", json);

        try {
            ws.send(json);
        } catch (Exception e) {
            pending.remove(message.getId());
            return CompletableFuture.failedFuture(e);
        }

        // Apply timeout
        Duration timeout = message.getTimeout() != null ? message.getTimeout() : defaultTimeout;
        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Fire and forget - no response expected.
     */
    void sendWithoutWaiting(CdpMessage message) {
        String json = message.toJson();
        logger.trace(">>> {}", json);
        ws.send(json);
    }

    // Event subscription

    /**
     * Subscribe to CDP events by method name.
     */
    public void on(String eventName, Consumer<CdpEvent> handler) {
        eventHandlers.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Unsubscribe from CDP events.
     */
    public void off(String eventName, Consumer<CdpEvent> handler) {
        List<Consumer<CdpEvent>> handlers = eventHandlers.get(eventName);
        if (handlers != null) {
            handlers.remove(handler);
        }
    }

    /**
     * Remove all handlers for an event.
     */
    public void offAll(String eventName) {
        eventHandlers.remove(eventName);
    }

    // Lifecycle

    public void close() {
        ws.close();
    }

    public boolean isOpen() {
        return ws.isOpen();
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

}
