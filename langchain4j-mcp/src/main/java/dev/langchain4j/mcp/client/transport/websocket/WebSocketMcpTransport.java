package dev.langchain4j.mcp.client.transport.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.internal.Jdk8;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.logging.McpLoggers;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;

/** Java 8 WebSocket transport. Connection recovery never replays an application message. */
public class WebSocketMcpTransport implements McpTransport {
    private final Object lock = new Object();
    private final String url;
    private volatile OkHttpClient client;
    private final OkHttpClient ownedClient;
    private final boolean ownsClient;
    private final McpHeadersSupplier headers;
    private final boolean logRequests, logResponses;
    private final Logger traffic;
    private final long connectTimeoutMillis, requestTimeoutMillis, closeTimeoutMillis;
    private final int maxPendingRequests;
    private final long maxQueuedBytes;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicLong recoveryIds = new AtomicLong(-1);
    private final Set<Pending> pending = new HashSet<>();
    private Generation current;
    private McpOperationHandler handler;
    private Map<String, Object> initializationTemplate;
    private Consumer<String> reinitialized = ignored -> {};
    private Runnable invalidated = () -> {};
    private boolean closed;

    private static final class Generation {
        final CompletableFuture<WebSocket> connected = new CompletableFuture<>();
        final CompletableFuture<String> ready = new CompletableFuture<>();
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        WebSocket socket;
        ScheduledFuture<?> deadline;
        boolean initializeStarted;
        final boolean recovery;
        Generation(boolean recovery) { this.recovery = recovery; }
    }
    private static final class Pending {
        final Generation generation;
        final Long id;
        final String json;
        final CompletableFuture<String> result = new CompletableFuture<>();
        ScheduledFuture<?> deadline;
        boolean sent;
        boolean cancelledByServer;
        Pending(Generation generation, Long id, String json) {
            this.generation = generation; this.id = id; this.json = json;
        }
    }

    public WebSocketMcpTransport(Builder builder) {
        url = Objects.requireNonNull(builder.url, "url");
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) throw new IllegalArgumentException("Expected ws:// or wss:// URL");
        connectTimeoutMillis = positive(builder.timeout, Duration.ofSeconds(60));
        requestTimeoutMillis = positive(builder.requestTimeout, Duration.ofSeconds(60));
        closeTimeoutMillis = positive(builder.closeTimeout, Duration.ofSeconds(1));
        if (builder.maxPendingRequests < 1 || builder.maxQueuedBytes < 1 || builder.maxQueuedBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Positive pending limit and queue limit <= 16 MiB required");
        }
        maxPendingRequests = builder.maxPendingRequests; maxQueuedBytes = builder.maxQueuedBytes;
        headers = builder.headersSupplier == null ? ignored -> Collections.emptyMap() : builder.headersSupplier;
        logRequests = builder.logRequests; logResponses = builder.logResponses;
        traffic = builder.logger == null ? McpLoggers.traffic() : builder.logger;
        ownsClient = builder.httpClient == null;
        OkHttpClient base = ownsClient ? new OkHttpClient() : builder.httpClient;
        OkHttpClient.Builder configured = configure(base);
        if (builder.sslContext != null) configured.sslSocketFactory(builder.sslContext.getSocketFactory(), builder.trustManager);
        client = configured.build();
        ownedClient = ownsClient ? client : null;
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread t = new Thread(task, "langchain4j-mcp-websocket-deadline"); t.setDaemon(true); return t;
        });
        timer.setRemoveOnCancelPolicy(true);
    }
    private OkHttpClient.Builder configure(OkHttpClient source) {
        return source.newBuilder().connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);
    }
    /** New connections use the replacement; an existing channel and caller resources remain intact. */
    public void reloadHttpClient(OkHttpClient replacement) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Transport is closed");
            client = configure(Objects.requireNonNull(replacement, "httpClient")).build();
        }
    }
    /** OkHttp requires the matching trust manager in addition to SSLContext. */
    public void reloadSslContext(SSLContext context, X509TrustManager trustManager) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Transport is closed");
            client = configure(client).sslSocketFactory(Objects.requireNonNull(context).getSocketFactory(), Objects.requireNonNull(trustManager)).build();
        }
    }
    private static long positive(Duration value, Duration fallback) {
        long millis = (value == null ? fallback : value).toMillis();
        if (millis <= 0) throw new IllegalArgumentException("Timeout must be positive milliseconds");
        return millis;
    }

    @Override public void start(McpOperationHandler handler) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Transport is closed");
            if (current != null) retire(current, new IOException("Connection replaced"));
            this.handler = Objects.requireNonNull(handler, "handler");
            open(false);
        }
    }
    private Generation open(boolean recovery) {
        Generation g = new Generation(recovery);
        current = g;
        try {
            Request.Builder request = new Request.Builder().url(url);
            Objects.requireNonNull(headers.apply(null), "headers supplier returned null").forEach(request::header);
            g.deadline = timer.schedule(() -> retire(g, new TimeoutException("WebSocket initialization timed out")),
                    connectTimeoutMillis, TimeUnit.MILLISECONDS);
            g.socket = client.newWebSocket(request.build(), new WebSocketMcpListener(
                    socket -> opened(g, socket), text -> received(g, text),
                    error -> retire(g, error), () -> g.ended.complete(null)));
        } catch (RuntimeException failure) { retire(g, failure); }
        return g;
    }
    private void opened(Generation g, WebSocket socket) {
        synchronized (lock) {
            if (closed || current != g) { socket.cancel(); return; }
            g.socket = socket;
            g.connected.complete(socket);
            if (g.recovery) initialize(g);
        }
    }
    @SuppressWarnings("unchecked")
    @Override public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest operation) {
        synchronized (lock) {
            if (closed || handler == null) return Jdk8.failedFuture(new IllegalStateException("Transport is not started"));
            initializationTemplate = new LinkedHashMap<>((Map<String,Object>) McpJson.toValue(McpJson.serialize(operation)));
            Generation g = current == null ? open(false) : current;
            g.connected.whenComplete((socket, error) -> { if (error == null) initialize(g); });
            CompletableFuture<String> result = mirror(g.ready);
            result.whenComplete((value, error) -> {
                if (result.isCancelled() && !g.recovery) retire(g, new CancellationException("Initialization cancelled"));
            });
            return result;
        }
    }
    private void initialize(Generation g) {
        synchronized (lock) {
            if (closed || current != g || g.initializeStarted || initializationTemplate == null) return;
            g.initializeStarted = true;
            Map<String,Object> request = new LinkedHashMap<>(initializationTemplate);
            long id = g.recovery ? recoveryIds.getAndDecrement() : ((Number) request.get("id")).longValue();
            request.put("id", id);
            Pending p = register(g, id, McpJson.serialize(request), false);
            p.result.whenComplete((response, error) -> {
                if (error != null) { retire(g, error); return; }
                synchronized (lock) {
                    if (closed || current != g) return;
                    try {
                        Map<?,?> parsed = (Map<?,?>) McpJson.toValue(response);
                        Object result = parsed.get("result");
                        if (!(result instanceof Map) || parsed.containsKey("error")) throw new IOException("Invalid initialization response");
                        String protocol = String.valueOf(((Map<?,?>)result).get("protocolVersion"));
                        if (!"2025-11-25".equals(protocol)) throw new IOException("Unverified WebSocket protocol: " + protocol);
                        enqueue(g, McpJson.serialize(new McpInitializationNotification()));
                        if (g.recovery) reinitialized.accept(response);
                        if (g.deadline != null) g.deadline.cancel(false);
                        g.ready.complete(response);
                    } catch (Exception failure) { retire(g, failure); }
                }
            });
            transmit(p);
        }
    }
    @Override public CompletableFuture<String> sendRequest(McpClientMessage request) { return sendRequest(new McpCallContext(null, request)); }
    @Override public CompletableFuture<String> sendRequest(McpCallContext context) {
        synchronized (lock) {
            if (closed || initializationTemplate == null) return Jdk8.failedFuture(new IllegalStateException("Transport is not initialized"));
            if (pending.size() >= maxPendingRequests) return Jdk8.failedFuture(new RejectedExecutionException("MCP pending request limit reached"));
            String json;
            try { json = McpJson.serialize(context.message()); }
            catch (RuntimeException failure) { return Jdk8.failedFuture(failure); }
            Generation g = current == null ? open(true) : current;
            Pending p = register(g, context.message().getId(), json, true);
            g.ready.whenComplete((response, error) -> {
                if (error != null) p.result.completeExceptionally(error);
                else transmit(p);
            });
            return p.result;
        }
    }
    private Pending register(Generation g, Long id, String json, boolean timed) {
        Pending p = new Pending(g, id, json);
        for (Pending existing : pending) {
            if (existing.generation == g && id != null && id.equals(existing.id)) {
                p.result.completeExceptionally(new IllegalArgumentException("Duplicate in-flight MCP request id: " + id));
                return p;
            }
        }
        pending.add(p);
        if (id != null) handler.expectResponse(id, p.result);
        p.result.whenComplete((value, error) -> {
            synchronized (lock) {
                pending.remove(p);
                if (p.deadline != null) p.deadline.cancel(false);
                if (p.sent && !p.cancelledByServer && current == g && (p.result.isCancelled() || error instanceof TimeoutException)) {
                    try { enqueue(g, McpJson.serialize(new McpCancellationNotification(id, "Cancelled or timed out"))); }
                    catch (RuntimeException failure) { retire(g, failure); }
                }
            }
        });
        if (timed) p.deadline = timer.schedule(() -> p.result.completeExceptionally(new TimeoutException("WebSocket request timed out")), requestTimeoutMillis, TimeUnit.MILLISECONDS);
        return p;
    }
    private void transmit(Pending p) {
        synchronized (lock) {
            if (p.result.isDone()) return;
            if (closed || current != p.generation) { p.result.completeExceptionally(new IOException("Connection lost before send")); return; }
            try {
                enqueue(p.generation, p.json); p.sent = true;
                if (p.id == null) p.result.complete(null);
            } catch (RuntimeException failure) { p.result.completeExceptionally(failure); }
        }
    }
    private void enqueue(Generation g, String json) {
        if (current != g || g.socket == null || closed) throw new IllegalStateException("WebSocket is unavailable");
        long bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + g.socket.queueSize() > maxQueuedBytes) throw new RejectedExecutionException("MCP outbound byte limit reached");
        if (logRequests) traffic.debug("> {}", json);
        if (!g.socket.send(json)) {
            IOException failure = new IOException("WebSocket rejected message"); retire(g, failure);
            throw new IllegalStateException(failure);
        }
    }
    @Override public void sendMessage(McpClientMessage request) { sendMessage(new McpCallContext(null, request)); }
    @Override public void sendMessage(McpCallContext context) {
        synchronized (lock) {
            if (current == null || !current.connected.isDone()) throw new IllegalStateException("WebSocket is disconnected");
            enqueue(current, McpJson.serialize(context.message()));
        }
    }
    private void received(Generation g, String text) {
        synchronized (lock) {
            if (closed || current != g) return;
            if (logResponses) traffic.debug("< {}", text);
            try {
                Object parsed = McpJson.toValue(text);
                if (parsed instanceof Map) {
                    Map<?,?> message = (Map<?,?>) parsed;
                    if ("notifications/cancelled".equals(message.get("method")) && message.get("params") instanceof Map) {
                        Object id = ((Map<?,?>)message.get("params")).get("requestId");
                        for (Pending p : pending) if (p.generation == g && p.id != null && p.id.toString().equals(String.valueOf(id))) p.cancelledByServer = true;
                    }
                    if (message.containsKey("result") || message.containsKey("error")) {
                        String id = String.valueOf(message.get("id"));
                        for (Pending p : new ArrayList<>(pending)) {
                            if (p.generation == g && p.sent && p.id != null && p.id.toString().equals(id)) {
                                pending.remove(p); p.result.complete(text); return;
                            }
                        }
                        return;
                    }
                }
                handler.onMessage(text);
            } catch (RuntimeException failure) {
                // Match the upstream stream policy: malformed individual messages do not kill the reader.
                traffic.warn("Ignoring malformed MCP WebSocket message", failure);
            }
        }
    }
    private void retire(Generation g, Throwable failure) { retire(g, failure, true); }
    private void retire(Generation g, Throwable failure, boolean cancelSocket) {
        synchronized (lock) {
            if (current != g) return;
            current = null;
            if (g.deadline != null) g.deadline.cancel(false);
            try { invalidated.run(); } catch (RuntimeException callbackError) { failure.addSuppressed(callbackError); }
            g.connected.completeExceptionally(failure); g.ready.completeExceptionally(failure);
            for (Pending p : new ArrayList<>(pending)) if (p.generation == g) p.result.completeExceptionally(failure);
            if (cancelSocket && g.socket != null) g.socket.cancel();
        }
    }
    private static <T> CompletableFuture<T> mirror(CompletableFuture<T> source) {
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value,error) -> { if (error != null) result.completeExceptionally(error); else result.complete(value); });
        return result;
    }
    /** Observes shared handshake completion; cancelling this view does not cancel other callers. */
    public CompletableFuture<String> sessionInitialization() {
        synchronized (lock) { return current == null ? Jdk8.failedFuture(new IllegalStateException("Disconnected")) : mirror(current.ready); }
    }
    @Override public void checkHealth() { synchronized (lock) { if (closed) throw new IllegalStateException("Transport is closed"); } }
    @Override public void onFailure(Runnable ignored) { /* Recovery is owned by this transport, only on a new request. */ }
    @Override public void onSessionInvalidated(Runnable listener) { synchronized (lock) { invalidated = Objects.requireNonNull(listener); } }
    @Override public void onSessionReinitialized(Consumer<String> listener) { synchronized (lock) { reinitialized = Objects.requireNonNull(listener); } }
    @Override public boolean handlesRequestCancellation() { return true; }
    @Override public boolean requiresCancellationNotification() { return true; }
    @Override public void close() throws IOException {
        Generation g;
        synchronized (lock) {
            if (closed) return;
            closed = true; g = current;
            if (g != null) retire(g, new IOException("Transport closed"), false);
        }
        if (g != null && g.socket != null) {
            try {
                if (g.connected.isCompletedExceptionally() || !g.socket.close(1000, "Client closing")) g.socket.cancel();
                else g.ended.get(closeTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // The peer is allowed to disappear; local release is still bounded.
            } finally { g.socket.cancel(); }
        }
        timer.shutdownNow();
        if (ownedClient != null) { ownedClient.dispatcher().executorService().shutdownNow(); ownedClient.connectionPool().evictAll(); }
    }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String url; private OkHttpClient httpClient; private Logger logger;
        private SSLContext sslContext; private X509TrustManager trustManager;
        private boolean logRequests, logResponses;
        private Duration timeout, requestTimeout, closeTimeout;
        private McpHeadersSupplier headersSupplier;
        private int maxPendingRequests = 1024;
        private long maxQueuedBytes = 1024 * 1024;
        public Builder url(String value) { url = value; return this; }
        public Builder httpClient(OkHttpClient value) { httpClient = value; return this; }
        public Builder sslContext(SSLContext context, X509TrustManager manager) {
            sslContext = Objects.requireNonNull(context); trustManager = Objects.requireNonNull(manager); return this;
        }
        public Builder logger(Logger value) { logger = value; return this; }
        public Builder logRequests(boolean value) { logRequests = value; return this; }
        public Builder logResponses(boolean value) { logResponses = value; return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }
        public Builder requestTimeout(Duration value) { requestTimeout = value; return this; }
        public Builder closeTimeout(Duration value) { closeTimeout = value; return this; }
        public Builder maxPendingRequests(int value) { maxPendingRequests = value; return this; }
        public Builder maxQueuedBytes(long value) { maxQueuedBytes = value; return this; }
        public Builder headersSupplier(Supplier<Map<String,String>> value) { headersSupplier = ignored -> value.get(); return this; }
        public Builder headersSupplier(McpHeadersSupplier value) { headersSupplier = value; return this; }
        public WebSocketMcpTransport build() { return new WebSocketMcpTransport(this); }
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
