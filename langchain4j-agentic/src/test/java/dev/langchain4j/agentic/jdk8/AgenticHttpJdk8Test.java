package dev.langchain4j.agentic.jdk8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.internal.Jdk8;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;
import org.junit.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.Assert.*;

/** Real Java 8 Agentic -> AI Services -> OpenAI HTTP/SSE, with loopback-only synthetic input. */
public class AgenticHttpJdk8Test {
    public interface Writer {
        @Agent(outputKey = "draft") @dev.langchain4j.service.UserMessage("Write {{topic}}")
        String write(@V("topic") String topic);
    }
    public interface StreamWriter {
        @Agent(outputKey = "draft") @dev.langchain4j.service.UserMessage("Write {{topic}}")
        TokenStream write(@V("topic") String topic);
    }
    public interface StreamWorkflow {
        @Agent TokenStream run(@V("topic") String topic);
    }

    public interface MemoryWriter extends dev.langchain4j.service.memory.ChatMemoryAccess {
        @Agent(outputKey = "draft") @dev.langchain4j.service.UserMessage("Write {{topic}}")
        String write(@dev.langchain4j.service.MemoryId String session, @V("topic") String topic);
    }
    public interface SessionWorkflow extends dev.langchain4j.agentic.scope.AgenticScopeAccess {
        @Agent String run(@dev.langchain4j.service.MemoryId String session, @V("topic") String topic);
    }
    public static class Person { public String name; public int age; }
    public interface Extractor {
        @Agent(outputKey = "person") @dev.langchain4j.service.UserMessage("Extract {{topic}}")
        Person extract(@V("topic") String topic);
    }
    public static class Weather {
        final AtomicInteger calls = new AtomicInteger();
        @dev.langchain4j.agent.tool.Tool("Weather")
        public String weather(@dev.langchain4j.agent.tool.P(name = "city") String city) {
            assertEquals("云城", city); calls.incrementAndGet(); return "晴";
        }
    }

    HttpServer server;
    ExecutorService serverExecutor;
    okhttp3.Dispatcher dispatcher;
    okhttp3.ConnectionPool pool;
    String base;
    volatile String mode;
    final ObjectMapper mapper = new ObjectMapper();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicInteger active = new AtomicInteger(), disconnects = new AtomicInteger();
    CountDownLatch arrived, gate;

    @Before public void start() throws Exception {
        mode = "normal";
        arrived = new CountDownLatch(1); gate = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(); server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", this::handle); server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        dispatcher = new okhttp3.Dispatcher(); pool = new okhttp3.ConnectionPool();
    }

    @After public void stop() throws Exception {
        gate.countDown(); dispatcher.cancelAll(); server.stop(0); serverExecutor.shutdownNow();
        dispatcher.executorService().shutdownNow(); pool.evictAll();
        assertTrue(serverExecutor.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(dispatcher.executorService().awaitTermination(3, TimeUnit.SECONDS));
    }

    OkHttpClientBuilder transport() {
        return new OkHttpClientBuilder().okHttpClientBuilder(new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool));
    }
    OpenAiChatModel model() {
        return OpenAiChatModel.builder().apiKey("synthetic-agentic").modelName("fixture")
                .baseUrl(base).httpClientBuilder(transport()).maxRetries(0).timeout(Duration.ofMillis(700)).build();
    }
    OpenAiStreamingChatModel streaming() {
        return OpenAiStreamingChatModel.builder().apiKey("synthetic-agentic").modelName("fixture")
                .baseUrl(base).httpClientBuilder(transport()).timeout(Duration.ofMillis(700)).build();
    }
    Object formatter() {
        return AgenticServices.nonAiAgentBuilder(scope -> "final:" + scope.readState("draft", ""))
                .inputKey(String.class, "draft").outputKey("answer").build();
    }
    UntypedAgent textWorkflow(Object first) {
        return AgenticServices.sequenceBuilder().subAgents(first, formatter()).outputKey("answer").build();
    }
    TokenStream finalStream() { return finalStream(false); }
    TokenStream finalStream(boolean async) {
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).async(async).build();
        return AgenticServices.sequenceBuilder(StreamWorkflow.class).subAgents(writer).outputKey("draft").build().run("云");
    }

    @Test(timeout = 10000) public void synchronousHttpFeedsNextAgent() throws Exception {
        Writer writer = AgenticServices.agentBuilder(Writer.class).chatModel(model()).build();
        assertEquals("final:你好", textWorkflow(writer).invoke(Collections.singletonMap("topic", "云")));
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).path("messages").toString().contains("Write 云"));
        assertEquals("Bearer synthetic-agentic", authorization.get());
        awaitReleased();
    }

    @Test(timeout = 10000) public void asyncAgentAllowsIndependentNextStepWhileHttpWaits() throws Exception {
        mode = "hold";
        Writer writer = AgenticServices.agentBuilder(Writer.class).chatModel(model()).async(true).build();
        Object independent = AgenticServices.agentAction(scope -> {
            try { assertTrue(arrived.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            gate.countDown();
        });
        UntypedAgent workflow = AgenticServices.sequenceBuilder().subAgents(writer, independent, formatter()).outputKey("answer").build();
        assertEquals("final:你好", workflow.invoke(Collections.singletonMap("topic", "云")));
        assertEquals(1, requests.size()); awaitReleased();
    }

    @Test(timeout = 10000) public void intermediateSseBecomesTextForNextAgent() throws Exception {
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).build();
        assertEquals("final:你好", textWorkflow(writer).invoke(Collections.singletonMap("topic", "云")));
        assertEquals(1, requests.size()); assertTrue(requests.get(0).path("stream").asBoolean()); awaitReleased();
    }

    @Test(timeout = 10000) public void asyncIntermediateSseBecomesTextForNextAgent() throws Exception {
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).async(true).build();
        assertEquals("final:你好", textWorkflow(writer).invoke(Collections.singletonMap("topic", "云")));
        assertEquals(1, requests.size()); awaitReleased();
    }

    @Test(timeout = 10000) public void asyncStreamingDecisionUsesDispatchPositionDespitePlannerAdvance() throws Exception {
        CountDownLatch consumerEntered = new CountDownLatch(1);
        OpenAiStreamingChatModel model = streaming();
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).async(true)
                .streamingChatModel(scope -> {
                    try { assertTrue(consumerEntered.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return model;
                }).build();
        // This last agent has been dispatched before the asynchronous writer can create its stream.
        // The planner is now terminated, but that must not turn the writer's intermediate stream
        // into a terminal TokenStream. Its consumer still requires text.
        Object consumer = AgenticServices.nonAiAgentBuilder(scope -> {
            consumerEntered.countDown(); return "final:" + scope.readState("draft", "");
        }).outputKey("answer").build();
        UntypedAgent workflow = AgenticServices.sequenceBuilder().subAgents(writer, consumer).outputKey("answer").build();
        assertEquals("final:你好", workflow.invoke(Collections.singletonMap("topic", "云")));
        assertEquals(1, requests.size()); awaitReleased();
    }

    @Test(timeout = 10000) public void asyncFinalSseStillReachesCallerAsLazyTokenStream() throws Exception {
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        AtomicInteger terminals = new AtomicInteger(); StringBuffer text = new StringBuffer();
        TokenStream stream = finalStream(true);
        assertEquals(0, requests.size());
        stream.onPartialResponse(text::append).onCompleteResponse(r -> { terminals.incrementAndGet(); result.complete(r); })
                .onError(result::completeExceptionally).start();
        assertEquals("你好", result.get(4, TimeUnit.SECONDS).aiMessage().text());
        awaitReleased(); assertEquals("你好", text.toString()); assertEquals(1, terminals.get());
    }

    @Test(timeout = 10000) public void asyncIntermediateSseErrorDoesNotInvokeDependent() throws Exception {
        mode = "error"; AtomicInteger calls = new AtomicInteger();
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).async(true).build();
        Object dependent = AgenticServices.nonAiAgentBuilder(scope -> { calls.incrementAndGet(); return "wrong"; })
                .inputKey(String.class, "draft").outputKey("answer").build();
        try { AgenticServices.sequenceBuilder().subAgents(writer, dependent).outputKey("answer").build()
                .invoke(Collections.singletonMap("topic", "云")); fail("error expected"); }
        catch (RuntimeException expected) { assertFalse(expected instanceof CompletionException); assertTrue(causes(expected).contains("synthetic rejection")); }
        assertEquals(0, calls.get()); assertEquals(1, requests.size()); awaitReleased();
    }

    @Test(timeout = 10000) public void asyncIntermediateSseTimeoutReleasesCall() throws Exception {
        mode = "timeout";
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).async(true).build();
        try { textWorkflow(writer).invoke(Collections.singletonMap("topic", "云")); fail("timeout expected"); }
        catch (RuntimeException expected) { assertTrue(causes(expected).toLowerCase(Locale.ROOT).contains("timeout")); }
        gate.countDown(); awaitReleased();
    }

    @Test(timeout = 10000) public void finalSsePassesUnicodeAndSingleTerminalCallback() throws Exception {
        AtomicInteger complete = new AtomicInteger(), errors = new AtomicInteger();
        StringBuffer tokens = new StringBuffer(); CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        finalStream().onPartialResponse(tokens::append).onCompleteResponse(r -> { complete.incrementAndGet(); future.complete(r); })
                .onError(t -> { errors.incrementAndGet(); future.completeExceptionally(t); }).start();
        assertEquals("你好", future.get(4, TimeUnit.SECONDS).aiMessage().text());
        awaitReleased(); assertEquals("你好", tokens.toString()); assertEquals(1, complete.get()); assertEquals(0, errors.get());
    }

    @Test(timeout = 10000) public void cancellingFinalSseReleasesCallWithoutTerminalSignals() throws Exception {
        mode = "long"; CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger tokens = new AtomicInteger(), terminals = new AtomicInteger();
        finalStream().onPartialResponseWithContext((part, context) -> {
            tokens.incrementAndGet(); context.streamingHandle().cancel(); cancelled.countDown();
        }).onCompleteResponse(r -> terminals.incrementAndGet()).onError(t -> terminals.incrementAndGet()).start();
        assertTrue(cancelled.await(4, TimeUnit.SECONDS)); awaitReleased();
        assertEquals(1, tokens.get()); assertEquals(0, terminals.get()); assertTrue(disconnects.get() > 0);
        assertFalse(dispatcher.executorService().isShutdown());
    }

    @Test(timeout = 10000) public void intermediateSseTimeoutFailsAndReleasesCall() throws Exception {
        mode = "timeout";
        StreamWriter writer = AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).build();
        try { textWorkflow(writer).invoke(Collections.singletonMap("topic", "云")); fail("timeout expected"); }
        catch (RuntimeException expected) { assertTrue(causes(expected).toLowerCase(Locale.ROOT).contains("timeout")); }
        gate.countDown(); awaitReleased();
    }

    @Test(timeout = 10000) public void asyncHttpErrorPropagatesWithoutCompletionWrapperOrDependentCall() throws Exception {
        mode = "error"; AtomicInteger later = new AtomicInteger();
        Writer writer = AgenticServices.agentBuilder(Writer.class).chatModel(model()).async(true).build();
        Object next = AgenticServices.nonAiAgentBuilder(scope -> { later.incrementAndGet(); return scope.readState("draft"); })
                .inputKey(String.class, "draft").outputKey("answer").build();
        try { AgenticServices.sequenceBuilder().subAgents(writer, next).outputKey("answer").build().invoke(Collections.singletonMap("topic", "云")); fail("error expected"); }
        catch (RuntimeException expected) { assertFalse(expected instanceof CompletionException); assertTrue(causes(expected).contains("synthetic rejection")); }
        assertEquals(0, later.get()); assertEquals(1, requests.size()); awaitReleased();
    }

    @Test(timeout = 10000) public void synchronousHttpToolRoundTrip() throws Exception { tools(false, false); }
    @Test(timeout = 10000) public void asynchronousHttpToolRoundTrip() throws Exception { tools(true, false); }
    @Test(timeout = 10000) public void intermediateSseToolRoundTrip() throws Exception { tools(false, true); }
    @Test(timeout = 10000) public void asyncIntermediateSseToolRoundTrip() throws Exception { tools(true, true); }

    void tools(boolean async, boolean stream) throws Exception {
        mode = "tool"; Weather weather = new Weather();
        Object writer = stream
                ? AgenticServices.agentBuilder(StreamWriter.class).streamingChatModel(streaming()).async(async).tools(weather).build()
                : AgenticServices.agentBuilder(Writer.class).chatModel(model()).async(async).tools(weather).build();
        assertEquals("final:你好", textWorkflow(writer).invoke(Collections.singletonMap("topic", "云")));
        assertEquals(1, weather.calls.get()); assertEquals(2, requests.size());
        assertTrue(requests.get(0).path("tools").toString().contains("weather"));
        JsonNode messages = requests.get(1).path("messages"); boolean found = false;
        for (JsonNode message : messages) if (message.path("role").asText().equals("tool")) {
            assertEquals("call1", message.path("tool_call_id").asText()); assertEquals("晴", message.path("content").asText()); found = true;
        }
        assertTrue("tool result must reach the second model request", found); awaitReleased();
    }

    @Test(timeout = 10000) public void memoryIsIsolatedByAgenticSessionAcrossHttpTurns() throws Exception {
        MemoryWriter writer = AgenticServices.agentBuilder(MemoryWriter.class).chatModel(model())
                .chatMemoryProvider(id -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder().id(id).maxMessages(20).build()).build();
        SessionWorkflow workflow = AgenticServices.sequenceBuilder(SessionWorkflow.class).subAgents(writer).outputKey("draft").build();
        assertEquals("你好", workflow.run("a", "first"));
        assertEquals("你好", workflow.run("a", "second"));
        assertEquals(3, requests.get(1).path("messages").size());
        assertTrue(requests.get(1).path("messages").toString().contains("first"));
        assertEquals("你好", workflow.run("b", "other"));
        assertEquals(1, requests.get(2).path("messages").size());
        assertFalse(requests.get(2).path("messages").toString().contains("first"));
        // Scope eviction and AI Services chat-memory eviction are distinct baseline APIs.
        assertTrue(writer.evictChatMemory("a"));
        assertTrue(workflow.evictAgenticScope("a"));
        assertEquals("你好", workflow.run("a", "fresh"));
        assertEquals(1, requests.get(3).path("messages").size()); awaitReleased();
    }

    @Test(timeout = 10000) public void structuredHttpOutputBecomesTypedStateForNextAgent() throws Exception {
        mode = "structured";
        Extractor extractor = AgenticServices.agentBuilder(Extractor.class).chatModel(model()).build();
        Object format = AgenticServices.nonAiAgentBuilder(scope -> {
            Person person = (Person) scope.readState("person"); return person.name + "/" + person.age;
        }).inputKey(Person.class, "person").outputKey("answer").build();
        assertEquals("Ada/37", AgenticServices.sequenceBuilder().subAgents(extractor, format).outputKey("answer").build()
                .invoke(Collections.singletonMap("topic", "Ada")));
        assertEquals(1, requests.size()); awaitReleased();
    }

    static String causes(Throwable error) {
        StringBuilder value = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) value.append(current).append('\n');
        return value.toString();
    }
    void awaitReleased() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while ((dispatcher.runningCallsCount() != 0 || dispatcher.queuedCallsCount() != 0 || active.get() != 0) && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, dispatcher.runningCallsCount()); assertEquals(0, dispatcher.queuedCallsCount()); assertEquals(0, active.get());
    }
    void handle(HttpExchange exchange) throws IOException {
        active.incrementAndGet();
        try {
            JsonNode request = mapper.readTree(Jdk8.readAllBytes(exchange.getRequestBody())); requests.add(request);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); arrived.countDown();
            if (mode.equals("error")) { json(exchange, 400, "{\"error\":{\"message\":\"synthetic rejection\",\"type\":\"invalid_request_error\"}}"); return; }
            if (mode.equals("hold")) awaitGate();
            boolean hasToolResult = false;
            for (JsonNode message : request.path("messages")) if (message.path("role").asText().equals("tool")) hasToolResult = true;
            if (mode.equals("tool") && !hasToolResult) {
                if (request.path("stream").asBoolean()) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8"); exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    send(out, toolChunk(true, "{\"city\":")); send(out, toolChunk(false, "\"云城\"}"));
                    send(out, "data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");
                } else {
                    com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode().put("id", "fixture").put("model", "fixture");
                    com.fasterxml.jackson.databind.node.ObjectNode choice = payload.putArray("choices").addObject().put("index", 0).put("finish_reason", "tool_calls");
                    com.fasterxml.jackson.databind.node.ObjectNode tool = choice.putObject("message").put("role", "assistant").putArray("tool_calls").addObject().put("id", "call1").put("type", "function");
                    tool.putObject("function").put("name", "weather").put("arguments", "{\"city\":\"云城\"}"); json(exchange, 200, payload.toString());
                }
                return;
            }
            if (mode.equals("structured")) {
                com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode().put("id", "fixture").put("model", "fixture");
                com.fasterxml.jackson.databind.node.ObjectNode choice = payload.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
                choice.putObject("message").put("role", "assistant").put("content", "{\"name\":\"Ada\",\"age\":37}");
                json(exchange, 200, payload.toString()); return;
            }
            if (!request.path("stream").asBoolean()) {
                json(exchange, 200, "{\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}"); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8"); exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody(); send(out, chunk("你"));
            if (mode.equals("timeout")) awaitGate();
            if (mode.equals("long")) {
                for (int i = 0; i < 150; i++) {
                    try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    send(out, chunk("x"));
                }
            } else {
                send(out, chunk("好")); send(out, "data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
            }
        } catch (IOException expectedDisconnect) { disconnects.incrementAndGet(); }
        finally { exchange.close(); active.decrementAndGet(); }
    }
    void awaitGate() {
        try { gate.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    String toolChunk(boolean first, String arguments) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode().put("id", "fixture").put("model", "fixture");
        com.fasterxml.jackson.databind.node.ObjectNode tool = payload.putArray("choices").addObject().put("index", 0).putObject("delta").putArray("tool_calls").addObject().put("index", 0);
        if (first) tool.put("id", "call1").put("type", "function");
        com.fasterxml.jackson.databind.node.ObjectNode function = tool.putObject("function").put("arguments", arguments);
        if (first) function.put("name", "weather");
        return "data: " + payload + "\n\n";
    }
    static String chunk(String text) {
        return "data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }
    static void send(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += 3) { out.write(bytes, offset, Math.min(3, bytes.length - offset)); out.flush(); }
    }
}
