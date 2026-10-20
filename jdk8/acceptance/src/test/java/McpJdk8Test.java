import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.*;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.OkHttpClient;
import org.junit.*;
import static org.junit.Assert.*;

/** Real Java 8, real loopback HTTP/SSE, synthetic MCP and model endpoints only. */
public class McpJdk8Test {
    final ObjectMapper json = new ObjectMapper();
    HttpServer server;
    ExecutorService serverThreads;
    OkHttpClient http;
    DefaultMcpClient client;
    StreamableHttpMcpTransport transport;
    String url;
    volatile String mode = "normal";
    final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    final List<Headers> requestHeaders = new CopyOnWriteArrayList<>();
    final CountDownLatch toolStarted = new CountDownLatch(1);
    final CountDownLatch releaseTool = new CountDownLatch(1);
    final CountDownLatch cancelled = new CountDownLatch(1);
    final AtomicInteger toolsCalled = new AtomicInteger();

    @Before public void start() throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/mcp", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        http = new OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build();
    }
    DefaultMcpClient connect(long timeout) {
        transport = StreamableHttpMcpTransport.builder().url(url).httpClient(http)
                .customHeaders(Collections.singletonMap("X-Test", "synthetic")).build();
        client = DefaultMcpClient.builder().transport(transport).autoHealthCheck(false)
                .initializationTimeout(Duration.ofMillis(timeout)).toolExecutionTimeout(Duration.ofMillis(timeout)).build();
        return client;
    }
    @After public void stop() throws Exception {
        releaseTool.countDown();
        if (client != null) client.close();
        if (transport != null) transport.close();
        server.stop(0);
        serverThreads.shutdownNow();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }
    void handle(HttpExchange exchange) throws IOException {
        try {
            JsonNode request = json.readTree(exchange.getRequestBody());
            messages.add(request); requestHeaders.add(exchange.getRequestHeaders());
            String method = request.path("method").asText();
            if (!request.has("id")) {
                if ("notifications/cancelled".equals(method)) cancelled.countDown();
                exchange.sendResponseHeaders(202, -1); return;
            }
            Object result;
            if ("initialize".equals(method)) {
                if ("initHold".equals(mode)) { releaseTool.await(10, TimeUnit.SECONDS); return; }
                exchange.getResponseHeaders().set("MCP-Session-Id", "synthetic-session");
                result = map("protocolVersion", "badVersion".equals(mode) ? "2099-01-01" : "2025-11-25",
                        "capabilities", map("tools", map()), "serverInfo", map("name", "local", "version", "1"));
            } else if ("tools/list".equals(method)) {
                if ("listError".equals(mode)) { reply(exchange, request, null, map("code", -32601, "message", "no tools")); return; }
                boolean next = request.path("params").has("cursor");
                result = map("tools", Arrays.asList(map("name", next ? "other" : "weather", "description", "weather test",
                        "inputSchema", map("type", "object", "properties", map("city", map("type", "string")), "required", Arrays.asList("city")))));
                if ("pages".equals(mode) && !next) ((Map<String,Object>) result).put("nextCursor", "page2");
            } else if ("tools/call".equals(method)) {
                toolsCalled.incrementAndGet(); toolStarted.countDown();
                if ("hold".equals(mode)) { releaseTool.await(10, TimeUnit.SECONDS); }
                if ("httpError".equals(mode)) { exchange.sendResponseHeaders(503, -1); return; }
                if ("rpcError".equals(mode)) { reply(exchange, request, null, map("code", -32602, "message", "bad tool")); return; }
                result = map("content", Arrays.asList(map("type", "text", "text", "晴天")),
                        "isError", "applicationError".equals(mode));
                if ("attributes".equals(mode)) ((Map<String,Object>) result).put("_meta", map("custom", "value"));
            } else if ("ping".equals(method)) result = map();
            else { reply(exchange, request, null, map("code", -32601, "message", "unknown method")); return; }
            reply(exchange, request, result, null);
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
    }
    void reply(HttpExchange exchange, JsonNode request, Object result, Object error) throws IOException {
        Map<String,Object> response = map("jsonrpc", "2.0", "id", request.path("id").longValue(), error == null ? "result" : "error", error == null ? result : error);
        if ("mismatch".equals(mode) && "tools/call".equals(request.path("method").asText())) response.put("id", -999);
        String body = "malformed".equals(mode) ? "{broken" : json.writeValueAsString(response);
        if ("sse".equals(mode) || "sseEof".equals(mode)) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            String prefix = ": keepalive\r\nevent: message\r\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\r\n\r\n";
            out.write(prefix.getBytes(StandardCharsets.UTF_8)); out.flush();
            if (!"sseEof".equals(mode)) {
                // Multiline data, CRLF and split UTF-8 bytes.
                body = body.replace(",\"result\":", ",\r\ndata: \"result\":");
                byte[] bytes = ("event: message\r\ndata: " + body + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) { out.write(b); out.flush(); }
            }
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
    static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<values.length; i+=2) result.put((String) values[i], values[i+1]);
        return result;
    }
    ToolExecutionRequest tool() { return ToolExecutionRequest.builder().id("call1").name("weather").arguments("{\"city\":\"Shanghai\"}").build(); }
    InvocationContext context() { return InvocationContext.builder().chatMemoryId("test").build(); }
    CompletableFuture<ToolExecutionResult> call() { return client.executeToolAsync(tool(), context()); }
    void released() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((http.dispatcher().runningCallsCount() != 0 || http.dispatcher().queuedCallsCount() != 0) && System.nanoTime()<end) Thread.sleep(5);
        assertEquals(0, http.dispatcher().runningCallsCount()); assertEquals(0, http.dispatcher().queuedCallsCount());
    }
    void assertFailedCall() throws Exception { assertThrows(ExecutionException.class, () -> call().get(5, TimeUnit.SECONDS)); released(); }

    @Test public void initializesWithSessionVersionAndHeaders() throws Exception {
        connect(3000); assertEquals("initialize", messages.get(0).path("method").asText());
        assertEquals("notifications/initialized", messages.get(1).path("method").asText());
        assertNull(requestHeaders.get(0).getFirst("MCP-Session-Id"));
        assertEquals("2025-11-25", requestHeaders.get(1).getFirst("MCP-Protocol-Version"));
        assertEquals("synthetic-session", requestHeaders.get(1).getFirst("MCP-Session-Id"));
        assertEquals("synthetic", requestHeaders.get(1).getFirst("X-Test"));
        assertEquals("晴天", call().get(5, TimeUnit.SECONDS).resultText()); released();
    }
    @Test public void discoversSchemaAndCachesPaginatedTools() throws Exception {
        mode="pages"; connect(3000);
        List<ToolSpecification> tools = client.listTools();
        assertEquals(2, tools.size()); assertEquals("weather", tools.get(0).name());
        assertEquals(Arrays.asList("city"), tools.get(0).parameters().required());
        int count=messages.size(); assertEquals(tools, client.listTools()); assertEquals(count,messages.size()); released();
    }
    @Test public void handlesSegmentedMultilineSseWithNotifications() throws Exception {
        mode="sse"; connect(3000); assertEquals(1,client.listTools().size());
        assertEquals("晴天",call().get(5,TimeUnit.SECONDS).resultText()); released();
    }
    @Test public void synchronousToolCall() throws Exception { connect(3000); assertEquals("晴天",client.executeTool(tool(),context()).resultText()); released(); }
    @Test public void distinguishesApplicationErrors() throws Exception {
        connect(3000); mode="applicationError";
        ExecutionException failure=assertThrows(ExecutionException.class,()->call().get(5,TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof McpApplicationErrorException); released();
    }
    @Test public void propagatesRpcErrors() throws Exception { connect(3000);mode="rpcError";assertFailedCall(); }
    @Test public void propagatesHttpErrors() throws Exception { connect(3000);mode="httpError";assertFailedCall();assertEquals(1,toolsCalled.get()); }
    @Test public void rejectsMalformedResponses() throws Exception { connect(3000);mode="malformed";assertFailedCall(); }
    @Test public void rejectsUnmatchedResponseIdsWithoutHanging() throws Exception { connect(3000);mode="mismatch";assertFailedCall(); }
    @Test public void rejectsPrematureSseEof() throws Exception { connect(3000);mode="sseEof";assertFailedCall(); }
    @Test public void propagatesListErrors() throws Exception { connect(3000);mode="listError";assertThrows(RuntimeException.class,client::listTools);released(); }
    @Test public void rejectsInvalidToolArgumentsBeforeSending() throws Exception {
        connect(3000);ToolExecutionRequest bad=tool().toBuilder().arguments("[]").build();
        assertThrows(ExecutionException.class,()->client.executeToolAsync(bad,context()).get(5,TimeUnit.SECONDS));assertEquals(0,toolsCalled.get());released();
    }
    @Test public void timeoutNotifiesServerAndReleasesRequest() throws Exception {
        connect(300);mode="hold";CompletableFuture<ToolExecutionResult> result=call();
        assertTrue(toolStarted.await(3,TimeUnit.SECONDS));
        // Upstream maps tool timeout into a model-visible text result.
        assertTrue(result.get(5,TimeUnit.SECONDS).resultText().contains("timeout"));
        assertTrue(cancelled.await(3,TimeUnit.SECONDS));released();
    }
    @Test public void callerCancellationNotifiesServerAndReleasesRequest() throws Exception {
        connect(3000);mode="hold";CompletableFuture<ToolExecutionResult> result=call();
        assertTrue(toolStarted.await(3,TimeUnit.SECONDS));assertTrue(result.cancel(true));
        assertTrue(cancelled.await(3,TimeUnit.SECONDS));released();assertEquals(1,toolsCalled.get());
    }
    @Test public void closeFailsInFlightAndKeepsCallerClientUsable() throws Exception {
        connect(3000);mode="hold";CompletableFuture<ToolExecutionResult> result=call();
        assertTrue(toolStarted.await(3,TimeUnit.SECONDS));client.close();
        assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));released();
        assertFalse(http.dispatcher().executorService().isShutdown());
        assertThrows(IllegalStateException.class,()->client.listTools());
    }
    @Test public void unsupportedNegotiatedVersionFailsAndReleases() throws Exception {
        mode="badVersion";assertThrows(RuntimeException.class,()->connect(3000));released();
    }
    @Test public void initializationTimeoutReleasesRequest() throws Exception {
        mode="initHold";assertThrows(RuntimeException.class,()->connect(200));released();
    }
    @Test public void bridgeStripsAttributesByDefaultAndPropagatesCancellation() throws Exception {
        connect(3000);mode="attributes";
        assertEquals("value",call().get(5,TimeUnit.SECONDS).attributes().get("custom"));
        McpToolExecutor executor = new McpToolExecutor(client);
        assertTrue(executor.executeAsync(tool(),context()).get(5,TimeUnit.SECONDS).attributes().isEmpty());
        mode="hold";CompletableFuture<ToolExecutionResult> result=executor.executeAsync(tool(),context());
        assertTrue(toolStarted.await(3,TimeUnit.SECONDS));result.cancel(true);assertTrue(cancelled.await(3,TimeUnit.SECONDS));released();
    }
    @Test public void aiServicesHttpToolLoopSyncAndAsync() throws Exception {
        connect(3000);OpenAiJdk8Test model=new OpenAiJdk8Test();model.start();
        try {
            model.mode="toolOnce";McpToolProvider provider=McpToolProvider.builder().mcpClients(client).build();
            AiServicesJdk8Test.Basic sync=AiServices.builder(AiServicesJdk8Test.Basic.class).chatModel(model.model()).toolProvider(provider).build();
            assertEquals("你好",sync.chat("weather"));assertEquals(1,toolsCalled.get());
            assertEquals("晴天",model.request.get().path("messages").get(2).path("content").asText());
            model.requests.set(0);
            AiServicesJdk8Test.Async async=AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(model.model()).toolProvider(provider).build();
            assertEquals("你好",async.chat("weather").get(5,TimeUnit.SECONDS));assertEquals(2,toolsCalled.get());model.awaitReleased();released();
        } finally { model.stop(); }
    }
    @Test public void concurrentCallsKeepResponseIdsIndependent() throws Exception {
        connect(3000);
        List<CompletableFuture<ToolExecutionResult>> results=new ArrayList<>();
        for(int i=0;i<24;i++) results.add(call());
        for(CompletableFuture<ToolExecutionResult> result:results) assertEquals("晴天",result.get(5,TimeUnit.SECONDS).resultText());
        assertEquals(24,toolsCalled.get());released();
        Set<Long> ids=new HashSet<>();
        for(JsonNode message:messages) if(message.has("id")) assertTrue(ids.add(message.get("id").asLong()));
    }
    @Test public void aiServicesFutureCancellationDrainsStartedToolWithoutNextModelCall() throws Exception {
        connect(3000);mode="hold";OpenAiJdk8Test model=new OpenAiJdk8Test();model.start();
        try {
            model.mode="toolOnce";
            AiServicesJdk8Test.Async service=AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(model.model())
                    .toolProvider(McpToolProvider.builder().mcpClients(client).build()).build();
            CompletableFuture<String> result=service.chat("weather");
            assertTrue(toolStarted.await(3,TimeUnit.SECONDS));result.cancel(true);
            releaseTool.countDown();released();assertEquals(1,model.requests.get());model.awaitReleased();
        } finally {model.stop();}
    }
    @Test public void aiServicesPublisherCancellationDrainsStartedToolWithoutNextModelCall() throws Exception {
        connect(3000);mode="hold";OpenAiJdk8Test model=new OpenAiJdk8Test();model.start();
        try {
            model.mode="toolOnce";
            AiServicesJdk8Test.Reactive service=AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(model.streaming())
                    .toolProvider(McpToolProvider.builder().mcpClients(client).build()).build();
            ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();service.chat("weather").subscribe(probe);probe.subscription.request(1);
            assertTrue(toolStarted.await(3,TimeUnit.SECONDS));probe.subscription.cancel();
            releaseTool.countDown();released();assertEquals(1,model.requests.get());model.awaitReleased();
            assertEquals(0,probe.completed.get());assertNull(probe.error);
        } finally {model.stop();}
    }
    @Test public void formerRecordMetadataKeepsValueAndJsonSemantics() throws Exception {
        McpRoot root=new McpRoot("workspace","file:///synthetic");
        assertEquals(root,json.readValue(json.writeValueAsString(root),McpRoot.class));
        List<String> sizes=new ArrayList<>(Arrays.asList("64x64"));
        McpIcon icon=new McpIcon("image/png",sizes,"synthetic",null);sizes.clear();
        assertEquals(1,icon.sizes().size());assertThrows(UnsupportedOperationException.class,()->icon.sizes().clear());
        assertEquals(icon,json.readValue(json.writeValueAsString(icon),McpIcon.class));
        assertThrows(NullPointerException.class,()->new McpIcon(null,Arrays.asList((String)null),"synthetic",null));
    }
    @Test public void aiServicesCallbackAndPublisherToolLoops() throws Exception {
        connect(3000);OpenAiJdk8Test model=new OpenAiJdk8Test();model.start();
        try {
            model.mode="toolOnce";McpToolProvider provider=McpToolProvider.builder().mcpClients(client).build();
            AiServicesJdk8Test.Streaming service=AiServices.builder(AiServicesJdk8Test.Streaming.class).streamingChatModel(model.streaming()).toolProvider(provider).build();
            CompletableFuture<String> done=new CompletableFuture<>();
            service.chat("weather").onCompleteResponse(response->done.complete(response.aiMessage().text())).onError(done::completeExceptionally).start();
            assertEquals("你好",done.get(5,TimeUnit.SECONDS));
            model.requests.set(0);
            AiServicesJdk8Test.Reactive reactive=AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(model.streaming()).toolProvider(provider).build();
            ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();reactive.chat("weather").subscribe(probe);probe.subscription.request(Long.MAX_VALUE);
            assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));assertNull(probe.error);assertEquals("你好",String.join("",probe.values));
            assertEquals(2,toolsCalled.get());model.awaitReleased();released();
        } finally { model.stop(); }
    }
}
