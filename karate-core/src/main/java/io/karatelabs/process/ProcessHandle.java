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
 * Manages process lifecycle, stream readers, and line dispatch.
 * <p>
 * Supports two creation modes:
 * 1. Immediate start: ProcessHandle.start(config) - starts process immediately
 * 2. Deferred start: ProcessHandle.create(config) then handle.start() - allows setup before start
 */
public class ProcessHandle implements SimpleObject {

    private static final List<String> KEYS = List.of(
            "stdOut", "stdErr", "exitCode", "alive", "pid",
            "waitSync", "waitForOutput", "close", "signal", "start",
            "waitForPort", "waitForHttp", "onStdOut", "onStdErr"
    );

    private final ProcessConfig config;
    private Process process;
    private final CompletableFuture<Integer> exitFuture;
    private final StringBuilder stdoutBuffer = new StringBuilder();
    private final StringBuilder stderrBuffer = new StringBuilder();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    // For waitForOutput() functionality
    private final CopyOnWriteArrayList<Predicate<String>> waitPredicates = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Predicate<String>, CompletableFuture<String>> waitFutures = new ConcurrentHashMap<>();

    // Additional listeners (beyond config.listener)
    private final CopyOnWriteArrayList<Consumer<String>> stdOutListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> stdErrListeners = new CopyOnWriteArrayList<>();

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
     * Add a stdout listener. Can be called before or after start().
     * When redirectErrorStream is true (default), receives both stdout and stderr.
     */
    public ProcessHandle onStdOut(Consumer<String> listener) {
        stdOutListeners.add(listener);
        return this;
    }

    /**
     * Add a stderr listener. Can be called before or after start().
     * Only receives lines when redirectErrorStream is false.
     */
    public ProcessHandle onStdErr(Consumer<String> listener) {
        stdErrListeners.add(listener);
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
                    handleStdout(line);
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
                        handleStderr(line);
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
                exitFuture.complete(code);
                JvmLogger.debug("process exited with code: {}", code);
            } catch (Exception e) {
                exitFuture.completeExceptionally(e);
            } finally {
                executor.shutdown();
            }
        });
    }

    private void handleStdout(String line) {
        // Buffer the output
        synchronized (stdoutBuffer) {
            stdoutBuffer.append(line).append('\n');
        }
        // Log to LogContext if enabled
        if (config.logToContext()) {
            LogContext.get().log(line);
        }
        // Dispatch to config listener
        if (config.listener() != null) {
            try {
                config.listener().accept(line);
            } catch (Exception e) {
                JvmLogger.warn("listener error: {}", e.getMessage());
            }
        }
        // Dispatch to additional listeners
        for (Consumer<String> listener : stdOutListeners) {
            try {
                listener.accept(line);
            } catch (Exception e) {
                JvmLogger.warn("stdOut listener error: {}", e.getMessage());
            }
        }
        // Check waitForOutput predicates
        checkWaitPredicates(line);
    }

    private void handleStderr(String line) {
        // Buffer the output
        synchronized (stderrBuffer) {
            stderrBuffer.append(line).append('\n');
        }
        // Log to LogContext if enabled
        if (config.logToContext()) {
            LogContext.get().log(line);
        }
        // Dispatch to config errorListener
        if (config.errorListener() != null) {
            try {
                config.errorListener().accept(line);
            } catch (Exception e) {
                JvmLogger.warn("error listener error: {}", e.getMessage());
            }
        }
        // Dispatch to additional error listeners
        for (Consumer<String> listener : stdErrListeners) {
            try {
                listener.accept(line);
            } catch (Exception e) {
                JvmLogger.warn("stdErr listener error: {}", e.getMessage());
            }
        }
        // Check waitForOutput predicates (stderr lines also checked)
        checkWaitPredicates(line);
    }

    private void checkWaitPredicates(String line) {
        for (Predicate<String> predicate : waitPredicates) {
            try {
                if (predicate.test(line)) {
                    CompletableFuture<String> future = waitFutures.remove(predicate);
                    if (future != null) {
                        waitPredicates.remove(predicate);
                        future.complete(line);
                    }
                }
            } catch (Exception e) {
                JvmLogger.warn("waitForOutput predicate error: {}", e.getMessage());
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
     * Wait until predicate returns true for an output line.
     *
     * @param predicate Test function that receives the line
     * @return The line that matched
     */
    public String waitForOutput(Predicate<String> predicate) {
        return waitForOutput(predicate, 0);
    }

    /**
     * Wait until predicate returns true for an output line, with timeout.
     *
     * @param predicate     Test function that receives the line
     * @param timeoutMillis Timeout in milliseconds (0 = no timeout)
     * @return The line that matched
     */
    public String waitForOutput(Predicate<String> predicate, long timeoutMillis) {
        CompletableFuture<String> future = new CompletableFuture<>();
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
            throw new RuntimeException("waitForOutput timed out after " + timeoutMillis + "ms");
        } catch (Exception e) {
            waitPredicates.remove(predicate);
            waitFutures.remove(predicate);
            throw new RuntimeException("error in waitForOutput", e);
        }
    }

    public String getStdOut() {
        synchronized (stdoutBuffer) {
            return stdoutBuffer.toString();
        }
    }

    public String getStdErr() {
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
            case "stdOut" -> getStdOut();
            case "stdErr" -> getStdErr();
            case "exitCode" -> getExitCode();
            case "alive" -> isAlive();
            case "pid" -> getPid();
            case "start" -> (JsCallable) (ctx, args) -> {
                start();
                return this;
            };
            case "onStdOut" -> (JsCallable) (ctx, args) -> {
                if (args.length == 0 || !(args[0] instanceof JsCallable)) {
                    throw new RuntimeException("onStdOut requires a function argument");
                }
                JsCallable listener = (JsCallable) args[0];
                onStdOut(line -> {
                    try {
                        listener.call(ctx, line);
                    } catch (Exception e) {
                        JvmLogger.warn("onStdOut listener error: {}", e.getMessage());
                    }
                });
                return this;
            };
            case "onStdErr" -> (JsCallable) (ctx, args) -> {
                if (args.length == 0 || !(args[0] instanceof JsCallable)) {
                    throw new RuntimeException("onStdErr requires a function argument");
                }
                JsCallable listener = (JsCallable) args[0];
                onStdErr(line -> {
                    try {
                        listener.call(ctx, line);
                    } catch (Exception e) {
                        JvmLogger.warn("onStdErr listener error: {}", e.getMessage());
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
            case "waitForOutput" -> (JsCallable) (ctx, args) -> {
                if (args.length == 0 || !(args[0] instanceof JsCallable)) {
                    throw new RuntimeException("waitForOutput requires a function argument");
                }
                JsCallable predicate = (JsCallable) args[0];
                long timeout = args.length > 1 && args[1] instanceof Number
                        ? ((Number) args[1]).longValue() : 0;
                return waitForOutput(line -> {
                    Object res = predicate.call(ctx, line);
                    return Boolean.TRUE.equals(res);
                }, timeout);
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
