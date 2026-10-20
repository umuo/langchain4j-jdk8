package dev.langchain4j.mcp.client.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpHeaderEncoding;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java 8 MCP 2025-11-25 HTTP transport. Session generations isolate cursors and in-flight work.
 * Recovery only sends GETs and new initialize handshakes; no original POST is replayed.
 */
public final class StreamableHttpMcpTransport implements McpTransport {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final Object lifecycle = new Object();
    private final String url;
    private final OkHttpClient client;
    private final OkHttpClient streamClient;
    private final boolean ownsClient;
    private final McpHeadersSupplier headers;
    private final Logger logger;
    private final boolean logRequests, logResponses, subsidiaryEnabled;
    private final long timeoutMillis, closeTimeoutMillis, retryMillis;
    private final int maxReconnectAttempts;
    private final ScheduledThreadPoolExecutor timer;
    private final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private final Set<Call> closingCalls = ConcurrentHashMap.newKeySet();
    private final Map<String, String> knownSessions = new ConcurrentHashMap<>();
    private final AtomicLong recoveryIds = new AtomicLong(-1);
    private final AtomicBoolean closeFinished = new AtomicBoolean();
    private volatile McpOperationHandler handler;
    private volatile Consumer<String> recoveryListener = response -> {};
    private volatile Runnable invalidationListener = () -> {};
    private volatile boolean closed;
    private volatile Session session;
    private volatile boolean modernProtocol;
    private volatile String modernVersion = "2026-07-28";
    private volatile McpInitializeParams initializeParams;
    private CompletableFuture<Void> closeFuture;

    private static final class Session {
        final CompletableFuture<String> ready = new CompletableFuture<>();
        boolean modern;
        volatile String id;
        volatile String version = "2025-11-25";
        volatile Stream subsidiary;
    }

    public StreamableHttpMcpTransport(Builder builder) {
        HttpUrl parsed = HttpUrl.parse(Objects.requireNonNull(builder.url, "url"));
        if (parsed == null) throw new IllegalArgumentException("Invalid MCP HTTP URL");
        url = parsed.toString();
        timeoutMillis = positiveMillis(builder.timeout, Duration.ofSeconds(60), "timeout");
        closeTimeoutMillis = positiveMillis(builder.closeTimeout, Duration.ofSeconds(3), "closeTimeout");
        Duration retry = builder.reconnectInterval == null ? Duration.ofSeconds(1) : builder.reconnectInterval;
        if (retry.isNegative()) throw new IllegalArgumentException("Negative reconnectInterval");
        retryMillis = retry.toMillis();
        if (builder.maxReconnectAttempts < 0) throw new IllegalArgumentException("Negative maxReconnectAttempts");
        maxReconnectAttempts = builder.maxReconnectAttempts;
        subsidiaryEnabled = builder.subsidiaryChannel;
        ownsClient = builder.httpClient == null;
        OkHttpClient base = ownsClient ? new OkHttpClient.Builder()
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .protocols(builder.httpVersion1 ? Collections.singletonList(Protocol.HTTP_1_1)
                        : Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)).build() : builder.httpClient;
        // OkHttp can otherwise resend POSTs after connection failures, redirects or authentication challenges.
        client = base.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build();
        // Long-lived streams use a separate, explicit per-host budget, not OkHttp's five unary slots.
        if (builder.maxConcurrentStreams <= 0) throw new IllegalArgumentException("maxConcurrentStreams must be positive");
        Dispatcher streamDispatcher = new Dispatcher();
        streamDispatcher.setMaxRequests(builder.maxConcurrentStreams);
        streamDispatcher.setMaxRequestsPerHost(builder.maxConcurrentStreams);
        streamClient = client.newBuilder().dispatcher(streamDispatcher).callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    // OkHttp follows 503 Retry-After:0 even with retryOnConnectionFailure(false).
                    // Return this failure to our scheduler before its follow-up interceptor can resend GET.
                    String retryAfter = response.header("Retry-After");
                    if (response.code() == 503 && retryAfter != null && !retryAfter.isEmpty()
                            && retryAfter.chars().allMatch(c -> c == '0')) {
                        response.close();
                        throw new IOException("MCP GET HTTP 503; retry is controlled by the transport");
                    }
                    return response;
                }).build();
        headers = builder.headers == null ? context -> Collections.emptyMap() : builder.headers;
        logger = builder.logger == null ? LoggerFactory.getLogger(getClass()) : builder.logger;
        logRequests = builder.logRequests; logResponses = builder.logResponses;
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "langchain4j-mcp-recovery"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }
    private static long positiveMillis(Duration value, Duration fallback, String name) {
        long millis = (value == null ? fallback : value).toMillis();
        if (millis <= 0) throw new IllegalArgumentException(name + " must be at least 1ms");
        return millis;
    }
    @Override public void start(McpOperationHandler handler) {
        synchronized (lifecycle) {
            assertOpen(); this.handler = Objects.requireNonNull(handler, "handler");
        }
    }
    @Override public void setModernProtocol(boolean modern) {
        synchronized (lifecycle) {
            assertOpen();
            if (modernProtocol == modern) return;
            if (hasActiveRequests()) throw new IllegalStateException("Cannot switch protocol with active requests");
            modernProtocol = modern;
            session = null;
            invalidationListener.run();
            if (modern) {
                Session stateless = new Session(); stateless.modern = true; stateless.version = modernVersion;
                stateless.ready.complete(null); session = stateless;
            }
        }
    }
    @Override public void setProtocolVersion(String version) {
        if (!"2026-07-28".equals(version) && !"2025-11-25".equals(version) && !"2024-11-05".equals(version))
            throw new UnsupportedOperationException("Unported MCP protocol: " + version);
        synchronized (lifecycle) {
            assertOpen();
            if (session != null && version.equals(session.version)) { modernVersion = version; return; }
            if (hasActiveRequests()) throw new IllegalStateException("Cannot change protocol with active requests");
            modernVersion = version;
            if (session != null) session.version = version;
        }
    }
    private boolean hasActiveRequests() {
        for (Exchange exchange : exchanges) if (!exchange.result.isDone()) return true;
        for (Stream stream : streams) {
            if (!stream.result.isDone() && (stream.exchange == null || !stream.exchange.result.isDone())) return true;
        }
        return false;
    }
    @Override public void onSessionReinitialized(Consumer<String> listener) {
        recoveryListener = Objects.requireNonNull(listener, "listener");
    }
    @Override public void onSessionInvalidated(Runnable listener) {
        invalidationListener = Objects.requireNonNull(listener, "listener");
    }
    public String getMcpSessionId() { Session current = session; return current == null ? null : current.id; }
    /** Explicit external session replacement is only permitted while no request or stream is active. */
    public void setMcpSessionId(String id) {
        synchronized (lifecycle) {
            assertOpen();
            if (modernProtocol) throw new IllegalStateException("Modern MCP does not use session IDs");
            if (!exchanges.isEmpty() || !streams.isEmpty()) throw new IllegalStateException("MCP transport is busy");
            if (session == null) throw new IllegalStateException("Initialize the transport first");
            validateSessionId(id);
            session.id = id;
            if (id != null) knownSessions.put(id, session.version);
        }
    }

    @Override public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        Session created;
        Session previous;
        synchronized (lifecycle) {
            assertOpen();
            if (modernProtocol) throw new IllegalStateException("Modern MCP uses server/discover");
            if (session != null && !session.ready.isDone()) return session.ready;
            initializeParams = McpJson.deserialize(McpJson.serialize(request.getParams()), McpInitializeParams.class);
            previous = session;
            created = new Session(); session = created;
        }
        invalidationListener.run();
        if (previous != null) abandon(previous, new IllegalStateException("MCP session replaced"));
        initialize(created, request, false);
        return created.ready;
    }
    private void initialize(Session created, McpInitializeRequest request, boolean recovering) {
        final ScheduledFuture<?> deadline;
        try { deadline = timer.schedule(() -> created.ready.completeExceptionally(new TimeoutException("MCP initialization timed out")), timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException failure) { created.ready.completeExceptionally(failure); return; }
        created.ready.whenComplete((value, error) -> {
            deadline.cancel(false);
            if (error != null) {
                exchanges.forEach(exchange -> { if (exchange.session == created) exchange.result.completeExceptionally(error); });
            }
        });
        Exchange initial = new Exchange(new McpCallContext(null, request), created, true);
        initial.result.whenComplete((response, failure) -> {
            if (failure != null) { created.ready.completeExceptionally(failure); return; }
            if (!current(created) || created.ready.isDone()) return;
            try {
                JsonNode root = McpJson.parse(response);
                if (root.has("error")) throw new IllegalStateException("MCP initialization failed: " + response);
                String version = root.path("result").path("protocolVersion").asText();
                if (!"2025-11-25".equals(version) && !"2024-11-05".equals(version)) throw new IllegalStateException("Unsupported MCP version: " + version);
                created.version = version;
                if (created.id != null) knownSessions.put(created.id, version);
                Exchange notification = new Exchange(new McpCallContext(null, new McpInitializationNotification()), created, false);
                notification.result.whenComplete((ignored, error) -> {
                    if (error != null) { created.ready.completeExceptionally(error); return; }
                    if (!current(created) || created.ready.isDone()) return;
                    try {
                        if (recovering) recoveryListener.accept(response);
                        // Install the channel before completing the gate so health checks see this generation.
                        if (subsidiaryEnabled) created.subsidiary = new Stream(created, null);
                        if (created.ready.complete(response)) {
                            if (created.subsidiary != null) created.subsidiary.get();
                        } else if (created.subsidiary != null) created.subsidiary.result.complete(null);
                    } catch (Exception listenerError) { created.ready.completeExceptionally(listenerError); }
                });
                notification.post();
            } catch (Exception error) { created.ready.completeExceptionally(error); }
        });
        initial.post();
    }

    private Session readySession() {
        Session created;
        boolean retry = false;
        synchronized (lifecycle) {
            assertOpen();
            created = session;
            if (created == null) throw new IllegalStateException("MCP transport not initialized");
            if (created.ready.isCompletedExceptionally()) {
                created = new Session(); session = created; retry = true;
            }
        }
        if (retry) { invalidationListener.run(); initialize(created, recoveryRequest(), true); }
        return created;
    }
    private McpInitializeRequest recoveryRequest() {
        McpInitializeRequest request = new McpInitializeRequest(recoveryIds.getAndDecrement());
        request.setParams(McpJson.deserialize(McpJson.serialize(initializeParams), McpInitializeParams.class));
        return request;
    }
    private boolean current(Session expected) { return !closed && session == expected; }
    private void expired(Session expired) {
        Session replacement;
        synchronized (lifecycle) {
            if (!current(expired)) return;
            replacement = new Session(); session = replacement;
            if (expired.id != null) knownSessions.remove(expired.id); // The server explicitly reported that this session no longer exists.
        }
        invalidationListener.run();
        HttpException error = new HttpException(404, "MCP session expired; original request was not replayed");
        abandon(expired, error);
        // Do not wait for the handshake on the OkHttp response thread.
        initialize(replacement, recoveryRequest(), true);
    }

    private void abandon(Session old, Throwable error) {
        old.ready.completeExceptionally(error);
        streams.forEach(stream -> { if (stream.session == old) stream.result.completeExceptionally(error); });
        exchanges.forEach(exchange -> { if (exchange.session == old) exchange.result.completeExceptionally(error); });
    }
    private static void validateSessionId(String id) {
        if (id != null && (id.isEmpty() || !id.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)))
            throw new IllegalArgumentException("MCP session ID must contain visible ASCII");
    }
    @Override public CompletableFuture<String> sendRequest(McpClientMessage message) { return sendRequest(new McpCallContext(null, message)); }
    @Override public CompletableFuture<String> sendRequest(McpCallContext context) { return submit(context, true); }
    @Override public void sendMessage(McpClientMessage message) { sendMessage(new McpCallContext(null, message)); }
    @Override public void sendMessage(McpCallContext context) {
        // Cancellation belongs to the session on which the request was sent. Exchange handles it before cleanup.
        submit(context, false).whenComplete((ignored, failure) -> {
            if (failure != null && !closed) logger.warn("MCP notification failed", failure);
        });
    }
    private CompletableFuture<String> submit(McpCallContext context, boolean responseExpected) {
        try {
            Session expected = readySession();
            Exchange exchange = new Exchange(context, expected, responseExpected);
            expected.ready.whenComplete((ignored, failure) -> {
                if (failure != null) exchange.result.completeExceptionally(failure);
                else if (!exchange.result.isDone()) exchange.post();
            });
            return exchange.result;
        } catch (Exception error) { return dev.langchain4j.internal.Jdk8.failedFuture(error); }
    }
    private Request.Builder request(Session expected, McpCallContext context, boolean initializing) {
        Request.Builder builder = new Request.Builder().url(url);
        Map<String, String> supplied = headers.apply(context);
        if (supplied != null) supplied.forEach(builder::header);
        // Session/cursor headers are controlled by the transport, never a stale supplier value.
        builder.removeHeader("MCP-Session-Id").removeHeader("MCP-Protocol-Version").removeHeader("Last-Event-ID")
                .removeHeader("Mcp-Method").removeHeader("Mcp-Name");
        for (String name : new ArrayList<>(builder.build().headers().names())) {
            if (name.regionMatches(true, 0, "Mcp-Param-", 0, 10)) builder.removeHeader(name);
        }
        if (expected.modern) {
            builder.header("MCP-Protocol-Version", expected.version);
            if (context != null) {
                JsonNode body = McpJson.parse(McpJson.serialize(context.message()));
                String method = body.path("method").asText(null);
                if (method != null) builder.header("Mcp-Method", method);
                JsonNode params = body.path("params");
                String name = "resources/read".equals(method) ? params.path("uri").asText(null)
                        : ("tools/call".equals(method) || "prompts/get".equals(method)) ? params.path("name").asText(null) : null;
                if (name != null) builder.header("Mcp-Name", McpHeaderEncoding.encode(name));
                if (context.mcpParamHeaders() != null) context.mcpParamHeaders().forEach((k,v) -> builder.header("Mcp-Param-" + k, v));
            }
        } else if (!initializing) {
            if (expected.id != null) builder.header("MCP-Session-Id", expected.id);
            builder.header("MCP-Protocol-Version", expected.version);
        }
        return builder;
    }
    private static RequestBody oneShotBody(String json) {
        RequestBody delegate = RequestBody.create(json, JSON);
        return new RequestBody() {
            @Override public MediaType contentType() { return delegate.contentType(); }
            @Override public long contentLength() throws IOException { return delegate.contentLength(); }
            @Override public void writeTo(okio.BufferedSink sink) throws IOException { delegate.writeTo(sink); }
            @Override public boolean isOneShot() { return true; }
        };
    }
    private final class Exchange {
        final McpCallContext context;
        final Session session;
        final boolean responseExpected;
        final CompletableFuture<String> result = new CompletableFuture<>();
        final AtomicBoolean posted = new AtomicBoolean();
        volatile boolean sent;
        volatile Call call;
        volatile Stream stream;
        volatile ScheduledFuture<?> deadline;
        Exchange(McpCallContext context, Session session, boolean responseExpected) {
            this.context = context; this.session = session; this.responseExpected = responseExpected;
            synchronized (lifecycle) {
                if (closed) { result.completeExceptionally(new IllegalStateException("MCP transport closed")); return; }
                exchanges.add(this);
                deadline = timer.schedule(() -> result.completeExceptionally(new TimeoutException("MCP request timed out")), timeoutMillis, TimeUnit.MILLISECONDS);
            }
            result.whenComplete((value, failure) -> {
                if (deadline != null) deadline.cancel(false); exchanges.remove(this);
                Call active = call; if (active != null && (failure != null || stream != null)) active.cancel();
                Stream activeStream = stream; if (activeStream != null) activeStream.result.complete(null);
                if (!session.modern && sent && responseExpected && !(context.message() instanceof McpInitializeRequest)
                        && (result.isCancelled() || failure instanceof TimeoutException) && current(session)) {
                    Exchange cancellation = new Exchange(new McpCallContext(context.invocationContext(),
                            new McpCancellationNotification(context.message().getId(), result.isCancelled() ? "Cancelled" : "Timeout")), session, false);
                    cancellation.post();
                }
            });
        }
        void post() {
            if (result.isDone() || !posted.compareAndSet(false, true)) return;
            try {
                if (!current(session)) throw new IllegalStateException("MCP session changed before request was sent");
                if (handler == null) throw new IllegalStateException("MCP transport not started");
                McpClientMessage message = context.message();
                if (responseExpected && message.getId() == null) throw new IllegalArgumentException("MCP request requires an id");
                String json = McpJson.serialize(message);
                Request outgoing = request(session, context, message instanceof McpInitializeRequest)
                        .header("Accept", "application/json, text/event-stream")
                        .post(oneShotBody(json)).build();
                if (logRequests) logger.info("MCP request: {}", json);
                call = (session.modern && message instanceof McpSubscriptionsListenRequest ? streamClient : client).newCall(outgoing);
                if (responseExpected) handler.expectResponse(message.getId(), result);
                if (result.isDone() || !current(session)) { call.cancel(); result.completeExceptionally(new IllegalStateException("MCP request stopped")); return; }
                sent = true;
                call.enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException failure) { result.completeExceptionally(failure); }
                    @Override public void onResponse(Call call, Response response) {
                        try (Response received = response) {
                            if (!current(session)) throw new IOException("Stale MCP session response");
                            if (!session.modern && received.code() == 404 && outgoing.header("MCP-Session-Id") != null) { expired(session); return; }
                            if (!received.isSuccessful()) throw new HttpException(received.code(), "MCP HTTP " + received.code());
                            if (!responseExpected) {
                                if (received.code() != 202) throw new IOException("MCP notification requires HTTP 202");
                                Exchange.this.call = null; result.complete(null); return;
                            }
                            if (received.code() != 200 || received.body() == null) throw new IOException("MCP response requires HTTP 200 and a body");
                            if (message instanceof McpInitializeRequest) {
                                String id = received.header("MCP-Session-Id");
                                validateSessionId(id);
                                synchronized (lifecycle) {
                                    if (!current(session)) throw new IOException("MCP session closed during initialization");
                                    session.id = id;
                                    if (id != null) knownSessions.put(id, session.version);
                                }
                            }
                            String contentType = received.header("Content-Type", "").toLowerCase(Locale.ROOT);
                            if (contentType.startsWith("text/event-stream")) {
                                stream = new Stream(session, Exchange.this);
                                stream.call = call; Exchange.this.call = null;
                                if (result.isDone()) { stream.result.complete(null); call.cancel(); }
                                else stream.read(received.body());
                            } else if (contentType.startsWith("application/json")) {
                                String body = received.body().string();
                                Exchange.this.call = null;
                                deliver(body, session, Exchange.this);
                                if (!result.isDone()) throw new IOException("MCP response ended without the matching JSON-RPC result");
                            } else throw new IOException("Unsupported MCP content type: " + contentType);
                        } catch (Exception failure) { Exchange.this.call = null; result.completeExceptionally(failure); }
                    }
                });
            } catch (Exception failure) { result.completeExceptionally(failure); }
        }
    }
    private void deliver(String message, Session expected, Exchange owner) {
        if (!current(expected)) throw new IllegalStateException("Stale MCP session event");
        JsonNode parsed = McpJson.parse(message);
        if (owner != null && parsed.has("id") && (parsed.has("result") || parsed.has("error"))
                && !Objects.equals(parsed.path("id").asText(), String.valueOf(owner.context.message().getId()))) {
            throw new IllegalArgumentException("MCP response id does not match its HTTP request");
        }
        if (logResponses) logger.info("MCP response: {}", message);
        handler.onMessage(message);
    }

    private final class Stream implements SseEventReader.Listener {
        final Session session;
        final Exchange exchange;
        final CompletableFuture<String> result = new CompletableFuture<>();
        volatile Call call;
        volatile ScheduledFuture<?> retry;
        volatile String lastEventId;
        volatile long retryDelay = retryMillis;
        int attempts;
        Stream(Session session, Exchange exchange) {
            this.session = session; this.exchange = exchange; streams.add(this);
            result.whenComplete((value, failure) -> {
                streams.remove(this);
                ScheduledFuture<?> scheduled = retry; if (scheduled != null) scheduled.cancel(false);
                Call active = call; if (active != null) active.cancel();
                if (failure != null && exchange != null) exchange.result.completeExceptionally(failure);
                if (failure != null && exchange == null && current(session)) logger.warn("MCP GET SSE channel stopped", failure);
            });
        }
        boolean stopped() { return result.isDone() || !current(session) || (exchange != null && exchange.result.isDone()); }
        @Override public void retry(long millis) { retryDelay = millis; }
        @Override public void event(String type, String data, String id, boolean hasId) {
            if (stopped()) return;
            boolean duplicate = hasId && id != null && !id.isEmpty() && id.equals(lastEventId);
            if (!duplicate && "message".equals(type) && !data.isEmpty()) {
                JsonNode parsed = McpJson.parse(data);
                if (session.modern && exchange != null && exchange.context.message() instanceof McpSubscriptionsListenRequest
                        && "notifications/subscriptions/acknowledged".equals(parsed.path("method").asText())) {
                    JsonNode idNode = parsed.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId");
                    if (!exchange.context.message().getId().toString().equals(idNode.asText()))
                        throw new IllegalArgumentException("Subscription acknowledgement does not match its HTTP stream");
                    if (exchange.deadline != null) exchange.deadline.cancel(false);
                }
                // An unsolicited GET stream cannot carry responses for other POST requests.
                if (exchange == null && parsed.has("id") && (parsed.has("result") || parsed.has("error")))
                    throw new IllegalArgumentException("Unexpected JSON-RPC response on unsolicited GET SSE");
                deliver(data, session, exchange);
            }
            if (hasId) lastEventId = id;
            if (!duplicate && (!data.isEmpty() || (hasId && !id.isEmpty()))) attempts = 0;
        }
        void read(ResponseBody body) {
            Call reading = call;
            try {
                SseEventReader.read(body.byteStream(), this::stopped, this);
                if (call == reading) call = null;
                if (!stopped()) resume(new IOException("MCP SSE disconnected"));
            } catch (IOException failure) {
                if (call == reading) call = null;
                if (!stopped()) resume(failure);
            } catch (Exception malformed) { result.completeExceptionally(malformed); }
        }
        void resume(Exception failure) {
            if (stopped()) return;
            // Stateless modern subscriptions are new application requests, never legacy session GET recovery.
            if (session.modern) { result.completeExceptionally(failure); return; }
            if ((exchange != null && (lastEventId == null || lastEventId.isEmpty())) || attempts >= maxReconnectAttempts) {
                result.completeExceptionally(failure); return;
            }
            attempts++;
            try {
                ScheduledFuture<?> scheduled = timer.schedule(this::get, retryDelay, TimeUnit.MILLISECONDS);
                retry = scheduled;
                if (stopped()) scheduled.cancel(false);
            } catch (RejectedExecutionException rejected) { result.completeExceptionally(rejected); }
        }
        void get() {
            if (stopped()) return;
            try {
                Request.Builder builder = request(session, exchange == null ? null : exchange.context, false)
                        .header("Accept", "text/event-stream").get();
                if (lastEventId != null && !lastEventId.isEmpty()) builder.header("Last-Event-ID", lastEventId);
                call = streamClient.newCall(builder.build());
                if (stopped()) { call.cancel(); return; }
                call.enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException failure) {
                        if (Stream.this.call == call) Stream.this.call = null;
                        resume(failure);
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try (Response received = response) {
                            if (stopped()) return;
                            if (received.code() == 404 && session.id != null) { expired(session); return; }
                            if (received.code() == 405 && exchange == null) { Stream.this.call = null; result.complete(null); return; }
                            if (!received.isSuccessful()) {
                                Stream.this.call = null;
                                HttpException failure = new HttpException(received.code(), "MCP GET HTTP " + received.code());
                                if (received.code() == 429 || received.code() >= 500) resume(failure);
                                else result.completeExceptionally(failure);
                                return;
                            }
                            if (received.code() != 200 || received.body() == null
                                    || !received.header("Content-Type", "").toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                                Stream.this.call = null; result.completeExceptionally(new IOException("MCP GET requires HTTP 200 text/event-stream")); return;
                            }
                            read(received.body());
                        } catch (Exception failure) { result.completeExceptionally(failure); }
                    }
                });
            } catch (Exception failure) { result.completeExceptionally(failure); }
        }
    }
    /** Observe the current shared handshake without taking ownership of its cancellation. */
    public CompletableFuture<String> sessionInitialization() {
        Session current = session;
        return current == null ? dev.langchain4j.internal.Jdk8.failedFuture(new IllegalStateException("Not initialized")) : observe(current.ready);
    }
    /** Completion/failure of the current optional GET channel; 405 is a normal completion. */
    public CompletableFuture<String> subsidiaryChannelCompletion() {
        Session current = session;
        return current == null || current.subsidiary == null ? CompletableFuture.completedFuture(null) : observe(current.subsidiary.result);
    }
    private static <T> CompletableFuture<T> observe(CompletableFuture<T> source) {
        CompletableFuture<T> view = new CompletableFuture<>();
        source.whenComplete((value, failure) -> { if (failure != null) view.completeExceptionally(failure); else view.complete(value); });
        return view;
    }
    @Override public void checkHealth() {
        assertOpen();
        Session current = session;
        if (current != null && current.ready.isCompletedExceptionally()) current.ready.join();
        if (current != null && current.subsidiary != null && current.subsidiary.result.isCompletedExceptionally()) current.subsidiary.result.join();
    }
    @Override public void onFailure(Runnable callback) { /* Session recovery is owned by the transport, not an original-request replay callback. */ }
    @Override public boolean requiresCancellationNotification() { return !modernProtocol; }
    @Override public boolean handlesRequestCancellation() { return true; }
    private void assertOpen() { if (closed) throw new IllegalStateException("MCP transport closed"); }

    /** Stops local work and attempts bounded DELETEs for all known sessions. Idempotent; errors are observable. */
    public CompletableFuture<Void> terminateSession() {
        Map<String, String> releasing;
        synchronized (lifecycle) {
            if (closeFuture != null) return observe(closeFuture);
            closed = true; closeFuture = new CompletableFuture<>();
            releasing = new HashMap<>(knownSessions);
        }
        IllegalStateException failure = new IllegalStateException("MCP transport closed");
        Session current = session; if (current != null) current.ready.completeExceptionally(failure);
        streams.forEach(stream -> stream.result.complete(null));
        exchanges.forEach(exchange -> exchange.result.completeExceptionally(failure));
        ScheduledFuture<?> deadline = timer.schedule(() -> finishClose(new TimeoutException("MCP session DELETE timed out")), closeTimeoutMillis, TimeUnit.MILLISECONDS);
        List<CompletableFuture<Void>> results = new ArrayList<>();
        for (Map.Entry<String, String> known : releasing.entrySet()) {
            CompletableFuture<Void> deleted = new CompletableFuture<>(); results.add(deleted);
            try {
                Session deleting = new Session(); deleting.id = known.getKey(); deleting.version = known.getValue();
                Call call = client.newCall(request(deleting, null, false).delete().build());
                call.timeout().timeout(closeTimeoutMillis, TimeUnit.MILLISECONDS);
                synchronized (lifecycle) {
                    if (closeFinished.get()) throw new IllegalStateException("MCP release already finished");
                    closingCalls.add(call);
                    call.enqueue(new Callback() {
                        @Override public void onFailure(Call call, IOException error) {
                            closingCalls.remove(call); deleted.completeExceptionally(error);
                        }
                        @Override public void onResponse(Call call, Response response) {
                            Throwable error = null;
                            try (Response received = response) {
                                if (!received.isSuccessful() && received.code() != 404 && received.code() != 405)
                                    error = new HttpException(received.code(), "MCP DELETE HTTP " + received.code());
                            } finally { closingCalls.remove(call); }
                            if (error == null) deleted.complete(null); else deleted.completeExceptionally(error);
                        }
                    });
                }
            } catch (Exception error) { deleted.completeExceptionally(error); }
        }
        CompletableFuture.allOf(results.toArray(new CompletableFuture[0])).whenComplete((ignored, error) -> {
            deadline.cancel(false); finishClose(error);
        });
        return observe(closeFuture);
    }
    private void finishClose(Throwable failure) {
        synchronized (lifecycle) {
            if (!closeFinished.compareAndSet(false, true)) return;
            closingCalls.forEach(Call::cancel); closingCalls.clear();
            timer.shutdownNow();
            streamClient.dispatcher().executorService().shutdown();
            if (ownsClient) { client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll(); }
            knownSessions.clear();
        }
        if (failure == null) closeFuture.complete(null); else closeFuture.completeExceptionally(failure);
    }
    @Override public void close() {
        try { terminateSession().get(closeTimeoutMillis + 1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        catch (Exception error) { logger.warn("MCP session release failed; local transport stopped", error); }
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String url;
        private OkHttpClient httpClient;
        private McpHeadersSupplier headers;
        private Duration timeout, closeTimeout, reconnectInterval;
        private int maxConcurrentStreams = 64;
        private int maxReconnectAttempts = 5;
        private boolean logRequests, logResponses, httpVersion1, subsidiaryChannel;
        private Logger logger;
        public Builder url(String url) { this.url = url; return this; }
        /** Caller owns executor/pool. Automatic retries, redirects and authenticators are disabled in the derived client. */
        public Builder httpClient(OkHttpClient client) { this.httpClient = client; return this; }
        public Builder customHeaders(Map<String, String> headers) { this.headers = context -> headers; return this; }
        public Builder customHeaders(Supplier<Map<String, String>> headers) { this.headers = context -> headers.get(); return this; }
        /** Context is null for GET/DELETE control traffic without an originating invocation. */
        public Builder customHeaders(McpHeadersSupplier headers) { this.headers = headers; return this; }
        /** Transport request/initialization deadline and default connection/read timeout, including all SSE resumes. */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder closeTimeout(Duration timeout) { this.closeTimeout = timeout; return this; }
        public Builder reconnectInterval(Duration interval) { this.reconnectInterval = interval; return this; }
        public Builder maxReconnectAttempts(int attempts) { this.maxReconnectAttempts = attempts; return this; }
        public Builder logRequests(boolean enabled) { logRequests = enabled; return this; }
        public Builder logResponses(boolean enabled) { logResponses = enabled; return this; }
        public Builder logger(Logger logger) { this.logger = logger; return this; }
        /** Redirects cannot be enabled because they can replay a tool POST. Configure the final endpoint URL. */
        public Builder followRedirects(boolean enabled) {
            if (enabled) throw new IllegalArgumentException("MCP redirects may replay POST; use the final endpoint URL");
            return this;
        }
        public Builder setHttpVersion1_1() { httpVersion1 = true; return this; }
        public Builder maxConcurrentStreams(int maximum) { maxConcurrentStreams = maximum; return this; }
        public Builder subsidiaryChannel(boolean enabled) { subsidiaryChannel = enabled; return this; }
        public StreamableHttpMcpTransport build() { return new StreamableHttpMcpTransport(this); }
    }
    @Deprecated @Override public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) { return McpJson.map(sendInitializeRequest(request), McpJson::parse); }
    @Deprecated @Override public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage request) { return McpJson.map(sendRequest(request), McpJson::parse); }
    @Deprecated @Override public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) { return McpJson.map(sendRequest(context), McpJson::parse); }
    @Deprecated @Override public void executeOperationWithoutResponse(McpClientMessage request) { sendMessage(request); }
    @Deprecated @Override public void executeOperationWithoutResponse(McpCallContext context) { sendMessage(context); }
}
