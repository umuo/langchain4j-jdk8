package dev.langchain4j.mcp.client.transport.http;


import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java 8 Streamable HTTP tools transport for MCP 2025-11-25.
 * Supports JSON and non-resumable POST SSE responses. See the module README for boundaries.
 * No request is automatically replayed: tools may have side effects.
 */
public final class StreamableHttpMcpTransport implements McpTransport {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final String url;
    private final OkHttpClient client;
    private final boolean ownsClient;
    private final McpHeadersSupplier headers;
    private final Logger logger;
    private final boolean logRequests;
    private final boolean logResponses;
    private final Map<CompletableFuture<String>, Call> calls = new ConcurrentHashMap<>();
    private volatile McpOperationHandler handler;
    private volatile boolean closed;
    private volatile String sessionId;
    private volatile String protocolVersion;

    public StreamableHttpMcpTransport(Builder builder) {
        HttpUrl parsed = HttpUrl.parse(java.util.Objects.requireNonNull(builder.url, "url"));
        if (parsed == null) throw new IllegalArgumentException("Invalid MCP HTTP URL");
        url = parsed.toString();
        if (builder.subsidiaryChannel) throw new UnsupportedOperationException("Subsidiary SSE is not ported");
        Duration timeout = builder.timeout == null ? Duration.ofSeconds(60) : builder.timeout;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        ownsClient = builder.httpClient == null;
        client = ownsClient ? new OkHttpClient.Builder()
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(builder.followRedirects).followSslRedirects(false)
                .protocols(builder.httpVersion1 ? java.util.Collections.singletonList(Protocol.HTTP_1_1)
                        : java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)).build() : builder.httpClient;
        headers = builder.headers == null ? context -> java.util.Collections.emptyMap() : builder.headers;
        logger = builder.logger == null ? LoggerFactory.getLogger(getClass()) : builder.logger;
        logRequests = builder.logRequests;
        logResponses = builder.logResponses;
    }

    @Override public void start(McpOperationHandler handler) {
        if (closed) throw new IllegalStateException("MCP transport closed");
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
    }
    @Override public void setModernProtocol(boolean modern) {
        if (modern) throw new UnsupportedOperationException("Only MCP 2025-11-25 is ported");
    }
    @Override public void setProtocolVersion(String version) { protocolVersion = version; }
    public String getMcpSessionId() { return sessionId; }
    public void setMcpSessionId(String sessionId) { this.sessionId = sessionId; }

    @Override public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        CompletableFuture<String> initial = execute(new McpCallContext(null, request), true);
        // Track the current leaf so cancellation also covers the initialized notification.
        CompletableFuture<String> result = new CompletableFuture<>();
        dev.langchain4j.internal.CancellationChain cancellation = new dev.langchain4j.internal.CancellationChain(result);
        cancellation.track(initial);
        initial.whenComplete((response, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            if (result.isCancelled()) return;
            try {
                JsonNode root = McpJson.parse(response);
                if (root.has("error")) throw new IllegalStateException("MCP initialization failed: " + response);
                String version = root.path("result").path("protocolVersion").asText();
                if (!"2025-11-25".equals(version)) throw new IllegalStateException("Unsupported MCP version: " + version);
                protocolVersion = version;
                CompletableFuture<String> notification = execute(new McpCallContext(null, new McpInitializationNotification()), false);
                cancellation.track(notification);
                notification.whenComplete((ignored, failure) -> {
                    if (failure != null) result.completeExceptionally(failure); else result.complete(response);
                });
            } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        });
        return result;
    }
    @Override public CompletableFuture<String> sendRequest(McpClientMessage message) { return sendRequest(new McpCallContext(null, message)); }
    @Override public CompletableFuture<String> sendRequest(McpCallContext context) { return execute(context, true); }
    @Override public void sendMessage(McpClientMessage message) { sendMessage(new McpCallContext(null, message)); }
    @Override public void sendMessage(McpCallContext context) {
        execute(context, false).whenComplete((ignored, failure) -> {
            if (failure != null && !closed) logger.warn("MCP notification failed", failure);
        });
    }

    private CompletableFuture<String> execute(McpCallContext context, boolean responseExpected) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            if (closed) throw new IllegalStateException("MCP transport closed");
            if (handler == null) throw new IllegalStateException("MCP transport not started");
            McpClientMessage message = context.message();
            String json = McpJson.serialize(message);
            Request.Builder builder = new Request.Builder().url(url)
                    .post(RequestBody.create(json, JSON));
            Map<String, String> supplied = headers.apply(context);
            if (supplied != null) supplied.forEach(builder::header);
            builder.header("Accept", "application/json, text/event-stream");
            if (!(message instanceof McpInitializeRequest)) {
                if (sessionId != null) builder.header("MCP-Session-Id", sessionId);
                if (protocolVersion != null) builder.header("MCP-Protocol-Version", protocolVersion);
            }
            if (logRequests) logger.info("MCP request: {}", json);
            Call call = client.newCall(builder.build());
            calls.put(result, call);
            if (responseExpected) handler.expectResponse(message.getId(), result);
            result.whenComplete((value, failure) -> {
                calls.remove(result);
                // Completing a request, including timeout/error, releases its response stream.
                call.cancel();
            });
            if (closed) { result.completeExceptionally(new IllegalStateException("MCP transport closed")); return result; }
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException failure) { result.completeExceptionally(failure); }
                @Override public void onResponse(Call call, Response response) {
                    try (Response received = response) {
                        if (!received.isSuccessful()) throw new HttpException(received.code(), "MCP HTTP " + received.code());
                        if (!responseExpected) {
                            if (received.code() != 202) throw new IOException("MCP notification requires HTTP 202");
                            result.complete(null); return;
                        }
                        if (received.code() != 200 || received.body() == null) throw new IOException("MCP response requires HTTP 200 and a body");
                        if (message instanceof McpInitializeRequest) sessionId = received.header("MCP-Session-Id");
                        String contentType = received.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
                        if (contentType.startsWith("text/event-stream")) {
                            readEvents(received.body(), result);
                        } else if (contentType.startsWith("application/json")) {
                            deliver(received.body().string());
                        } else throw new IOException("Unsupported MCP content type: " + contentType);
                        if (!result.isDone()) throw new IOException("MCP response ended without the matching JSON-RPC result");
                    } catch (Exception failure) { result.completeExceptionally(failure); }
                }
            });
        } catch (Exception failure) { result.completeExceptionally(failure); }
        return result;
    }

    private void deliver(String message) {
        if (logResponses) logger.info("MCP response: {}", message);
        handler.onMessage(message);
    }
    private void readEvents(ResponseBody body, CompletableFuture<String> result) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        // Okio handles UTF-8 characters and CRLF split across arbitrary network chunks.
        while (!result.isDone() && (line = body.source().readUtf8Line()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    data.setLength(data.length() - 1);
                    deliver(data.toString()); data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                data.append(value).append('\n');
            }
        }
    }
    @Override public void checkHealth() { if (closed) throw new IllegalStateException("MCP transport closed"); }
    @Override public void onFailure(Runnable callback) { /* No automatic reconnect or replay. */ }
    @Override public boolean requiresCancellationNotification() { return true; }
    @Override public void close() {
        closed = true;
        calls.keySet().forEach(future -> future.completeExceptionally(new IllegalStateException("MCP transport closed")));
        if (ownsClient) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String url;
        private OkHttpClient httpClient;
        private McpHeadersSupplier headers;
        private Duration timeout;
        private boolean logRequests, logResponses, followRedirects, httpVersion1, subsidiaryChannel;
        private Logger logger;
        public Builder url(String url) { this.url = url; return this; }
        /** Caller owns the supplied client's executor and connection pool; configure TLS here. */
        public Builder httpClient(OkHttpClient client) { this.httpClient = client; return this; }
        public Builder customHeaders(Map<String, String> headers) { this.headers = context -> headers; return this; }
        public Builder customHeaders(Supplier<Map<String, String>> headers) { this.headers = context -> headers.get(); return this; }
        public Builder customHeaders(McpHeadersSupplier headers) { this.headers = headers; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder logRequests(boolean enabled) { logRequests = enabled; return this; }
        public Builder logResponses(boolean enabled) { logResponses = enabled; return this; }
        public Builder logger(Logger logger) { this.logger = logger; return this; }
        public Builder followRedirects(boolean enabled) { followRedirects = enabled; return this; }
        public Builder setHttpVersion1_1() { httpVersion1 = true; return this; }
        public Builder subsidiaryChannel(boolean enabled) { subsidiaryChannel = enabled; return this; }
        public StreamableHttpMcpTransport build() { return new StreamableHttpMcpTransport(this); }
    }
    @Deprecated @Override public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) { return McpJson.map(sendInitializeRequest(request), McpJson::parse); }
    @Deprecated @Override public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage request) { return McpJson.map(sendRequest(request), McpJson::parse); }
    @Deprecated @Override public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) { return McpJson.map(sendRequest(context), McpJson::parse); }
    @Deprecated @Override public void executeOperationWithoutResponse(McpClientMessage request) { sendMessage(request); }
    @Deprecated @Override public void executeOperationWithoutResponse(McpCallContext context) { sendMessage(context); }
}
