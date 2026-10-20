package dev.langchain4j.mcp.client.transport.stdio;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.exception.JsonException;
import dev.langchain4j.internal.DefaultExecutorProvider;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.transport.stdio.JsonRpcIoHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import dev.langchain4j.internal.Jdk8;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StdioMcpTransport implements McpTransport {

    private final List<String> command;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    // These are (re)assigned by start(), which may be invoked again from the health-check thread
    // during reconnection, so they are volatile to ensure visibility across threads.
    private volatile Process process;
    private volatile JsonRpcIoHandler jsonRpcIoHandler;
    private final boolean logEvents;
    private final Logger logger;
    private static final Logger log = LoggerFactory.getLogger(StdioMcpTransport.class);
    private volatile McpOperationHandler messageHandler;
    private volatile ProcessStderrHandler stderrHandler;
    private final Executor executor;
    private boolean closed;
    private volatile Runnable invalidationListener = () -> {};

    public StdioMcpTransport(Builder builder) {
        this.command = Jdk8.copyList(builder.command);
        this.environment = Jdk8.copyMap(builder.environment);
        this.workingDirectory = builder.workingDirectory;
        this.logEvents = builder.logEvents;
        this.logger = builder.logger;
        this.executor = getOrDefault(builder.executorService, DefaultExecutorProvider::getDefaultExecutor);
    }

    @Override
    public synchronized void start(McpOperationHandler messageHandler) {
        if (closed) throw new IllegalStateException("Transport is closed");
        stopCurrentProcess();
        invalidationListener.run();
        this.messageHandler = java.util.Objects.requireNonNull(messageHandler, "messageHandler");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        try {
            final Process started = builder.start();
            process = started;
            final JsonRpcIoHandler io = new JsonRpcIoHandler(started.getInputStream(),
                    started.getOutputStream(), messageHandler::onMessage, logEvents, logger);
            jsonRpcIoHandler = io;
            stderrHandler = new ProcessStderrHandler(started);
            // A dedicated waiter must not consume a slot in a caller's bounded I/O executor.
            Thread waiter = new Thread(() -> {
                try {
                    int exit = started.waitFor();
                    log.debug("MCP subprocess exited with code {}", exit);
                    failGeneration(started);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failGeneration(started);
                }
            }, "langchain4j-mcp-stdio-waiter");
            waiter.setDaemon(true);
            waiter.start();
            executor.execute(() -> {
                try { io.run(); }
                catch (RuntimeException failure) {
                    synchronized (StdioMcpTransport.this) {
                        if (process == started && !closed) log.warn("MCP subprocess reader failed", failure);
                    }
                } finally { failGeneration(started); }
            });
            executor.execute(stderrHandler);
        } catch (Exception e) {
            stopCurrentProcess();
            throw new IllegalStateException("Cannot start MCP subprocess", e);
        }
    }

    private synchronized void failGeneration(Process expected) {
        if (process == expected) {
            // Do not let an old process exit cancel requests belonging to its replacement.
            stopCurrentProcess();
        }
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest operation) {
        try {
            String requestString = McpJson.serialize(operation);
            String initializationNotification = McpJson.serialize(new McpInitializationNotification());
            CompletableFuture<String> response = execute(requestString, operation.getId());
            CompletableFuture<String> result = new CompletableFuture<>();
            result.whenComplete((value, error) -> { if (result.isCancelled()) response.cancel(true); });
            response.whenComplete((value, error) -> {
                if (error != null) result.completeExceptionally(error);
                else if (!result.isDone()) {
                    execute(initializationNotification, null).whenComplete((ignored, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(value);
                    });
                }
            });
            return result;
        } catch (JsonException | IllegalArgumentException e) {
            return Jdk8.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage operation) {
        return sendRequest(new McpCallContext(null, operation));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        try {
            String requestString = McpJson.serialize(context.message());
            return execute(requestString, context.message().getId());
        } catch (JsonException | IllegalArgumentException e) {
            return Jdk8.failedFuture(e);
        }
    }

    @Override
    public void sendMessage(McpClientMessage operation) {
        sendMessage(new McpCallContext(null, operation));
    }

    @Override
    public void sendMessage(McpCallContext context) {
        String requestString = McpJson.serialize(context.message());
        execute(requestString, null);
    }

    @Override
    public synchronized void checkHealth() {
        if (closed || process == null || !process.isAlive()) {
            throw new IllegalStateException("Process is not alive");
        }
    }

    @Override
    public boolean requiresCancellationNotification() {
        return true;
    }

    @Override
    public void onSessionInvalidated(Runnable listener) {
        invalidationListener = java.util.Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void onFailure(Runnable actionOnFailure) {
        // ignore, for stdio transport, we currently don't do reconnection attempts
    }

    /**
     * Closes the current I/O handlers and destroys the current subprocess, if any.
     * Does not shut down the (potentially shared) executor service, so it is safe to call
     * before starting a replacement process during reconnection.
     */
    private synchronized void stopCurrentProcess() {
        Process previous = process;
        process = null;
        JsonRpcIoHandler io = jsonRpcIoHandler;
        jsonRpcIoHandler = null;
        ProcessStderrHandler stderr = stderrHandler;
        stderrHandler = null;
        if (previous == null) return;
        try { invalidationListener.run(); } catch (RuntimeException error) { log.warn("MCP cache invalidation failed", error); }
        if (messageHandler != null) messageHandler.cancelAllPendingOperations("Process has stopped");
        try { if (stderr != null) stderr.close(); } catch (IOException ignored) { }
        // Destroy before closing readers: a blocked read must be released before close takes its lock.
        previous.destroy();
        boolean interrupted = false;
        try {
            if (!previous.waitFor(500, TimeUnit.MILLISECONDS)) {
                previous.destroyForcibly();
                previous.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            previous.destroyForcibly();
        }
        try { if (io != null) io.close(); } catch (IOException ignored) { }
        try { previous.getErrorStream().close(); } catch (IOException ignored) { }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        stopCurrentProcess();
        // Both supplied and DefaultExecutorProvider executors are host-owned.
    }

    public static Builder builder() {
        return new Builder();
    }

    private CompletableFuture<String> execute(String request, Long id) {
        CompletableFuture<String> future = new CompletableFuture<>();
        JsonRpcIoHandler io;
        synchronized (this) {
            if (closed || process == null || !process.isAlive() || jsonRpcIoHandler == null) {
                return Jdk8.failedFuture(new IllegalStateException("MCP subprocess is not running"));
            }
            io = jsonRpcIoHandler;
            if (id != null) messageHandler.expectResponse(id, future);
        }
        try {
            io.submit(request);
            if (id == null) future.complete(null);
        } catch (IOException | RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public Process getProcess() {
        return process;
    }

    public static class Builder {

        private List<String> command;
        private Map<String, String> environment;
        private Path workingDirectory;
        private boolean logEvents;
        private Logger logger;
        private ExecutorService executorService;

        public Builder command(List<String> command) {
            this.command = command;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the working directory of the MCP server subprocess, including when it is restarted.
         * If not set or {@code null}, the subprocess inherits the current process's working directory.
         * This does not change the working directory of the current process.
         *
         * @param workingDirectory the subprocess working directory, or {@code null} to use the default
         * @return {@code this}
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder logEvents(boolean logEvents) {
            this.logEvents = logEvents;
            return this;
        }

        /**
         * Sets the {@link ExecutorService} to use for background I/O operations.
         * If not provided, will use {@link DefaultExecutorProvider#getDefaultExecutor()}.
         * <p>
         * Frameworks like Quarkus should provide their managed executor here.
         * The provided executor is never shut down when the transport is closed.
         *
         * @param executorService the executor service to use
         * @return {@code this}
         */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * @param logger an alternate {@link Logger} to be used instead of the default one provided by Langchain4J for traffic logging.
         * @return {@code this}.
         */
        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public StdioMcpTransport build() {
            ensureNotEmpty(command, "command");
            if (environment == null) {
                environment = java.util.Collections.emptyMap();
            }
            return new StdioMcpTransport(this);
        }
    }

    /**
     * @deprecated use {@link #sendInitializeRequest(McpInitializeRequest)} instead, which does not
     * expose Jackson types.
     */
    @Deprecated
    @Override
    public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) {
        return McpJson.map(sendInitializeRequest(request), McpJson::parse);
    }

    /**
     * @deprecated use {@link #sendRequest(McpClientMessage)} instead, which does not expose Jackson
     * types.
     */
    @Deprecated
    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage request) {
        return McpJson.map(sendRequest(request), McpJson::parse);
    }

    /**
     * @deprecated use {@link #sendRequest(McpCallContext)} instead, which does not expose Jackson
     * types.
     */
    @Deprecated
    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) {
        return McpJson.map(sendRequest(context), McpJson::parse);
    }

    /**
     * @deprecated use {@link #sendMessage(McpClientMessage)} instead, which does not expose Jackson
     * types.
     */
    @Deprecated
    @Override
    public void executeOperationWithoutResponse(McpClientMessage request) {
        sendMessage(request);
    }

    /**
     * @deprecated use {@link #sendMessage(McpCallContext)} instead, which does not expose Jackson
     * types.
     */
    @Deprecated
    @Override
    public void executeOperationWithoutResponse(McpCallContext context) {
        sendMessage(context);
    }
}
