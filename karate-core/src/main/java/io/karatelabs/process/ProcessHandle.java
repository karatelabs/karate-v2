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
package io.karatelabs.process;

import io.karatelabs.js.JsCallable;
import io.karatelabs.js.SimpleObject;
import io.karatelabs.log.JvmLogger;
import io.karatelabs.log.LogContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Main process wrapper exposed to JavaScript.
 * Manages process lifecycle, stream readers, and event dispatch.
 * <p>
 * Supports two creation modes:
 * 1. Immediate start: ProcessHandle.start(config) - starts process immediately
 * 2. Deferred start: ProcessHandle.create(config) then handle.start() - allows setup before start
 */
public class ProcessHandle implements SimpleObject {

    private static final List<String> KEYS = List.of(
            "sysOut", "sysErr", "exitCode", "alive", "pid",
            "waitSync", "waitUntil", "close", "signal", "start",
            "waitForPort", "waitForHttp", "onEvent"
    );

    private final ProcessConfig config;
    private Process process;
    private final CompletableFuture<Integer> exitFuture;
    private final StringBuilder stdoutBuffer = new StringBuilder();
    private final StringBuilder stderrBuffer = new StringBuilder();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    // For waitUntil() functionality
    private final CopyOnWriteArrayList<Predicate<ProcessEvent>> waitPredicates = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Predicate<ProcessEvent>, CompletableFuture<ProcessEvent>> waitFutures = new ConcurrentHashMap<>();

    // Additional event listeners (beyond config.listener)
    private final CopyOnWriteArrayList<Consumer<ProcessEvent>> eventListeners = new CopyOnWriteArrayList<>();

    // Signal/listen integration
    private Consumer<Object> signalConsumer;

    // Virtual thread executor
    private ExecutorService executor;
    private volatile int exitCode = -1;

    private ProcessHandle(ProcessConfig config) {
        this.config = config;
        this.exitFuture = new CompletableFuture<>();
    }

    /**
     * Create ProcessHandle without starting the process.
     * Call start() to begin execution.
     */
    public static ProcessHandle create(ProcessConfig config) {
        return new ProcessHandle(config);
    }

    /**
     * Create and immediately start ProcessHandle.
     */
    public static ProcessHandle start(ProcessConfig config) {
        ProcessHandle handle = new ProcessHandle(config);
        handle.start();
        return handle;
    }

    /**
     * Start the process. Can only be called once.
     */
    public ProcessHandle start() {
        if (!started.compareAndSet(false, true)) {
            throw new RuntimeException("process already started");
        }
        try {
            java.lang.ProcessBuilder pb = new java.lang.ProcessBuilder(config.args());
            if (config.workingDir() != null) {
                pb.directory(config.workingDir().toFile());
            }
            if (!config.env().isEmpty()) {
                pb.environment().putAll(config.env());
            }
            pb.redirectErrorStream(config.redirectErrorStream());
            JvmLogger.debug("starting process: {}", config.args());
            this.process = pb.start();
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            startStreamReaders();
            startExitWaiter();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("failed to start process: " + e.getMessage(), e);
        }
    }

    /**
     * Add an event listener. Can be called before or after start().
     */
    public ProcessHandle onEvent(Consumer<ProcessEvent> listener) {
        eventListeners.add(listener);
        return this;
    }

    private void startStreamReaders() {
        // Stdout reader
        executor.submit(() -> {
            Thread.currentThread().setName("process-stdout-reader");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(ProcessEvent.Type.STDOUT, line);
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    JvmLogger.warn("stdout reader error: {}", e.getMessage());
                }
            }
        });

        // Stderr reader (only if not redirecting)
        if (!config.redirectErrorStream()) {
            executor.submit(() -> {
                Thread.currentThread().setName("process-stderr-reader");
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        handleLine(ProcessEvent.Type.STDERR, line);
                    }
                } catch (Exception e) {
                    if (!closed.get()) {
                        JvmLogger.warn("stderr reader error: {}", e.getMessage());
                    }
                }
            });
        }
    }

    private void startExitWaiter() {
        executor.submit(() -> {
            Thread.currentThread().setName("process-exit-waiter");
            try {
                int code = process.waitFor();
                exitCode = code;
                // Dispatch exit event
                ProcessEvent exitEvent = ProcessEvent.exit(code);
                dispatchEvent(exitEvent);
                exitFuture.complete(code);
                JvmLogger.debug("process exited with code: {}", code);
            } catch (Exception e) {
                exitFuture.completeExceptionally(e);
            } finally {
                executor.shutdown();
            }
        });
    }

    private void handleLine(ProcessEvent.Type type, String line) {
        // Buffer the output
        StringBuilder buffer = (type == ProcessEvent.Type.STDOUT) ? stdoutBuffer : stderrBuffer;
        synchronized (buffer) {
            buffer.append(line).append('\n');
        }
        // Log to LogContext if enabled
        if (config.logToContext()) {
            LogContext.get().log(line);
        }
        // Create and dispatch event
        ProcessEvent event = (type == ProcessEvent.Type.STDOUT)
                ? ProcessEvent.stdout(line)
                : ProcessEvent.stderr(line);
        dispatchEvent(event);
    }

    private void dispatchEvent(ProcessEvent event) {
        // Dispatch to config listener
        if (config.listener() != null) {
            try {
                config.listener().accept(event);
            } catch (Exception e) {
                JvmLogger.warn("listener error: {}", e.getMessage());
            }
        }
        // Dispatch to additional listeners
        for (Consumer<ProcessEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                JvmLogger.warn("event listener error: {}", e.getMessage());
            }
        }
        // Check waitUntil predicates
        for (Predicate<ProcessEvent> predicate : waitPredicates) {
            try {
                if (predicate.test(event)) {
                    CompletableFuture<ProcessEvent> future = waitFutures.remove(predicate);
                    if (future != null) {
                        waitPredicates.remove(predicate);
                        future.complete(event);
                    }
                }
            } catch (Exception e) {
                JvmLogger.warn("waitUntil predicate error: {}", e.getMessage());
            }
        }
    }

    // ========== Public API ==========

    public int waitSync() {
        try {
            return exitFuture.get();
        } catch (Exception e) {
            throw new RuntimeException("error waiting for process", e);
        }
    }

    public int waitSync(long timeoutMillis) {
        try {
            return exitFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("process timed out after " + timeoutMillis + "ms");
        } catch (Exception e) {
            throw new RuntimeException("error waiting for process", e);
        }
    }

    /**
     * Wait until predicate returns true for an event.
     *
     * @param predicate Test function that receives ProcessEvent
     * @return The event that matched
     */
    public ProcessEvent waitUntil(Predicate<ProcessEvent> predicate) {
        return waitUntil(predicate, 0);
    }

    /**
     * Wait until predicate returns true for an event, with timeout.
     *
     * @param predicate     Test function that receives ProcessEvent
     * @param timeoutMillis Timeout in milliseconds (0 = no timeout)
     * @return The event that matched
     */
    public ProcessEvent waitUntil(Predicate<ProcessEvent> predicate, long timeoutMillis) {
        CompletableFuture<ProcessEvent> future = new CompletableFuture<>();
        waitPredicates.add(predicate);
        waitFutures.put(predicate, future);
        try {
            if (timeoutMillis > 0) {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                return future.get();
            }
        } catch (TimeoutException e) {
            waitPredicates.remove(predicate);
            waitFutures.remove(predicate);
            throw new RuntimeException("waitUntil timed out after " + timeoutMillis + "ms");
        } catch (Exception e) {
            waitPredicates.remove(predicate);
            waitFutures.remove(predicate);
            throw new RuntimeException("error in waitUntil", e);
        }
    }

    public String getSysOut() {
        synchronized (stdoutBuffer) {
            return stdoutBuffer.toString();
        }
    }

    public String getSysErr() {
        synchronized (stderrBuffer) {
            return stderrBuffer.toString();
        }
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void close() {
        close(false);
    }

    public void close(boolean force) {
        if (closed.compareAndSet(false, true)) {
            if (force) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
            executor.shutdownNow();
            JvmLogger.debug("process closed (force={})", force);
        }
    }

    public long getPid() {
        return process.pid();
    }

    public CompletableFuture<Integer> getExitFuture() {
        return exitFuture;
    }

    // ========== Signal Integration ==========

    public void setSignalConsumer(Consumer<Object> consumer) {
        this.signalConsumer = consumer;
    }

    public void signal(Object result) {
        if (signalConsumer != null) {
            signalConsumer.accept(result);
        }
    }

    // ========== Port Waiting ==========

    public boolean waitForPort(String host, int port, int attempts, int intervalMs) {
        return PortUtils.waitForPort(host, port, attempts, intervalMs, this::isAlive);
    }

    public boolean waitForHttp(String url, int attempts, int intervalMs) {
        return PortUtils.waitForHttp(url, attempts, intervalMs, this::isAlive);
    }

    // ========== SimpleObject for JS access ==========

    @Override
    public Collection<String> keys() {
        return KEYS;
    }

    @Override
    public Object jsGet(String key) {
        return switch (key) {
            case "sysOut" -> getSysOut();
            case "sysErr" -> getSysErr();
            case "exitCode" -> getExitCode();
            case "alive" -> isAlive();
            case "pid" -> getPid();
            case "start" -> (JsCallable) (ctx, args) -> {
                start();
                return this;
            };
            case "onEvent" -> (JsCallable) (ctx, args) -> {
                if (args.length == 0 || !(args[0] instanceof JsCallable)) {
                    throw new RuntimeException("onEvent requires a function argument");
                }
                JsCallable listener = (JsCallable) args[0];
                onEvent(event -> {
                    try {
                        listener.call(ctx, event.toMap());
                    } catch (Exception e) {
                        JvmLogger.warn("onEvent listener error: {}", e.getMessage());
                    }
                });
                return this;
            };
            case "waitSync" -> (JsCallable) (ctx, args) -> {
                if (args.length > 0 && args[0] instanceof Number) {
                    return waitSync(((Number) args[0]).longValue());
                }
                return waitSync();
            };
            case "waitUntil" -> (JsCallable) (ctx, args) -> {
                if (args.length == 0 || !(args[0] instanceof JsCallable)) {
                    throw new RuntimeException("waitUntil requires a function argument");
                }
                JsCallable predicate = (JsCallable) args[0];
                long timeout = args.length > 1 && args[1] instanceof Number
                        ? ((Number) args[1]).longValue() : 0;
                ProcessEvent result = waitUntil(event -> {
                    Object res = predicate.call(ctx, event.toMap());
                    return Boolean.TRUE.equals(res);
                }, timeout);
                return result.toMap();
            };
            case "close" -> (JsCallable) (ctx, args) -> {
                boolean force = args.length > 0 && Boolean.TRUE.equals(args[0]);
                close(force);
                return null;
            };
            case "signal" -> (JsCallable) (ctx, args) -> {
                if (args.length > 0) {
                    signal(args[0]);
                }
                return null;
            };
            case "waitForPort" -> (JsCallable) (ctx, args) -> {
                String host = args.length > 0 ? args[0].toString() : "localhost";
                int port = args.length > 1 ? ((Number) args[1]).intValue() : 8080;
                int attempts = args.length > 2 ? ((Number) args[2]).intValue() : 30;
                int interval = args.length > 3 ? ((Number) args[3]).intValue() : 250;
                return waitForPort(host, port, attempts, interval);
            };
            case "waitForHttp" -> (JsCallable) (ctx, args) -> {
                String url = args.length > 0 ? args[0].toString() : "http://localhost:8080";
                int attempts = args.length > 1 ? ((Number) args[1]).intValue() : 30;
                int interval = args.length > 2 ? ((Number) args[2]).intValue() : 1000;
                return waitForHttp(url, attempts, interval);
            };
            default -> null;
        };
    }

}
