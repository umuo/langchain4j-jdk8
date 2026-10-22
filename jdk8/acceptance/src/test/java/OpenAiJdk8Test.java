import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.openai.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.internal.Jdk8;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.reactivestreams.*;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * All requests stay on loopback; credentials are synthetic test values.
 */
public class OpenAiJdk8Test {

    HttpServer server;

    ExecutorService serverThreads;

    String base;

    okhttp3.Dispatcher dispatcher;

    okhttp3.ConnectionPool pool;

    okhttp3.OkHttpClient.Builder transport;

    final AtomicReference<JsonNode> request = new AtomicReference<JsonNode>();

    final AtomicReference<String> auth = new AtomicReference<String>();

    volatile String mode = "normal";

    final AtomicInteger requests = new AtomicInteger();

    final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/v1/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        dispatcher = new okhttp3.Dispatcher();
        pool = new okhttp3.ConnectionPool();
        transport = new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool);
    }

    @After
    public void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
        dispatcher.cancelAll();
        dispatcher.executorService().shutdownNow();
        pool.evictAll();
    }

    OkHttpClientBuilder http() {
        return new OkHttpClientBuilder().okHttpClientBuilder(transport);
    }

    OpenAiChatModel model() {
        return OpenAiChatModel.builder().apiKey("synthetic").modelName("mock-model").baseUrl(base).httpClientBuilder(http()).maxRetries(0).timeout(Duration.ofSeconds(3)).build();
    }

    OpenAiStreamingChatModel streaming() {
        return OpenAiStreamingChatModel.builder().apiKey("synthetic").modelName("mock-model").baseUrl(base).httpClientBuilder(http()).timeout(Duration.ofSeconds(3)).build();
    }

    void handle(HttpExchange e) throws IOException {
        requests.incrementAndGet();
        auth.set(e.getRequestHeaders().getFirst("Authorization"));
        request.set(mapper.readTree(Jdk8.readAllBytes(e.getRequestBody())));
        try {
            if (mode.equals("error")) {
                respond(e, 400, "{\"error\":{\"message\":\"invalid request\",\"type\":\"invalid_request_error\"}}");
                return;
            }
            if (mode.equals("slow")) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                respond(e, 200, normal("late"));
                return;
            }
            if (request.get().path("stream").asBoolean()) {
                e.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                e.sendResponseHeaders(200, 0);
                OutputStream out = e.getResponseBody();
                if (mode.equals("long")) {
                    for (int i = 0; i < 500; i++) {
                        out.write(chunk("x").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } else if (mode.equals("tool") || (mode.equals("toolOnce") && requests.get() == 1)) {
                    com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
                    payload.put("id", "id").put("model", "mock-model");
                    com.fasterxml.jackson.databind.node.ObjectNode choice = payload.putArray("choices").addObject().put("index", 0);
                    com.fasterxml.jackson.databind.node.ObjectNode call = choice.putObject("delta").putArray("tool_calls").addObject();
                    call.put("index", 0).put("id", "call1").put("type", "function");
                    call.putObject("function").put("name", "weather").put("arguments", "{\"city\":\"Shanghai\"}");
                    String finish = "data: {\"id\":\"id\",\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
                    out.write(("data: " + payload + "\n\n" + finish).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else {
                    byte[] bytes = (chunk("你") + chunk("好") + "data: {\"id\":\"id\",\"object\":\"chat.completion.chunk\",\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    for (byte b : bytes) {
                        out.write(b);
                        out.flush();
                    }
                }
            } else if (mode.equals("structured")) {
                com.fasterxml.jackson.databind.node.ObjectNode payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(normal("placeholder"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("choices").get(0).path("message")).put("content", "{\"name\":\"Alice\",\"age\":30}");
                respond(e, 200, payload.toString());
            } else if (mode.equals("tool") || (mode.equals("toolOnce") && requests.get() == 1))
                respond(e, 200, "{\"id\":\"id\",\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Shanghai\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
            else
                respond(e, 200, normal("你好"));
        } finally {
            e.close();
        }
    }

    static String normal(String text) {
        return "{\"id\":\"id\",\"object\":\"chat.completion\",\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
    }

    static String chunk(String text) {
        return "data: {\"id\":\"id\",\"object\":\"chat.completion.chunk\",\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
    }

    static void respond(HttpExchange e, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, data.length);
        e.getResponseBody().write(data);
    }

    void awaitReleased() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.runningCallsCount() != 0 && System.nanoTime() < end) Thread.sleep(10);
        assertEquals("HTTP calls must be released", 0, dispatcher.runningCallsCount());
    }

    @Test
    public void synchronousAndAsyncCallsSerializeAndParse() throws Exception {
        OpenAiChatModel m = model();
        assertEquals("你好", m.chat("hello"));
        assertEquals("Bearer synthetic", auth.get());
        assertEquals("mock-model", request.get().path("model").asText());
        ChatResponse response = m.chatAsync(ChatRequest.builder().messages(UserMessage.from("async")).build()).get(5, TimeUnit.SECONDS);
        assertEquals("你好", response.aiMessage().text());
        assertEquals(Integer.valueOf(3), response.tokenUsage().totalTokenCount());
        awaitReleased();
    }

    @Test
    public void toolSpecificationAndResultMessagesRoundTrip() {
        mode = "tool";
        ToolSpecification tool = ToolSpecification.builder().name("weather").description("weather").parameters(JsonObjectSchema.builder().addStringProperty("city").required("city").build()).build();
        ChatResponse response = model().chat(ChatRequest.builder().messages(UserMessage.from("weather?")).parameters(DefaultChatRequestParameters.builder().toolSpecifications(tool).build()).build());
        assertEquals("weather", response.aiMessage().toolExecutionRequests().get(0).name());
        assertEquals("city", request.get().path("tools").get(0).path("function").path("parameters").path("required").get(0).asText());
        mode = "normal";
        model().chat(ChatRequest.builder().messages(UserMessage.from("weather?"), response.aiMessage(), new ToolExecutionResultMessage("call1", "weather", "sunny")).build());
        assertEquals("call1", request.get().path("messages").get(2).path("tool_call_id").asText());
    }

    @Test
    public void structuredResponseFormatIsSerialized() {
        ResponseFormat format = ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(JsonSchema.builder().name("Answer").rootElement(JsonObjectSchema.builder().addStringProperty("answer").required("answer").build()).build()).build();
        model().chat(ChatRequest.builder().messages(UserMessage.from("json")).parameters(DefaultChatRequestParameters.builder().responseFormat(format).build()).build());
        assertEquals("json_schema", request.get().path("response_format").path("type").asText());
        assertEquals("Answer", request.get().path("response_format").path("json_schema").path("name").asText());
    }

    @Test
    public void callbackStreamingCombinesUtf8Chunks() throws Exception {
        CompletableFuture<ChatResponse> result = new CompletableFuture<ChatResponse>();
        StringBuffer partial = new StringBuffer();
        streaming().chat("hello", new StreamingChatResponseHandler() {

            public void onPartialResponse(String s) {
                partial.append(s);
            }

            public void onCompleteResponse(ChatResponse r) {
                result.complete(r);
            }

            public void onError(Throwable e) {
                result.completeExceptionally(e);
            }
        });
        assertEquals("你好", result.get(5, TimeUnit.SECONDS).aiMessage().text());
        assertEquals("你好", partial.toString());
        awaitReleased();
    }

    @Test
    public void reactiveStreamingHonorsDemandAndCompletesOnce() throws Exception {
        ReactiveJdk8Test.Probe<ChatModelStreamingEvent> p = new ReactiveJdk8Test.Probe<ChatModelStreamingEvent>();
        streaming().chat(ChatRequest.builder().messages(UserMessage.from("hello")).build()).subscribe(p);
        awaitReleased();
        assertTrue(p.values.isEmpty());
        p.subscription.request(Long.MAX_VALUE);
        assertTrue(p.terminal.await(5, TimeUnit.SECONDS));
        assertNull(p.error);
        assertEquals(1, p.completed.get());
        StringBuilder text = new StringBuilder();
        int completed = 0;
        for (ChatModelStreamingEvent event : p.values) {
            if (event instanceof PartialResponse)
                text.append(((PartialResponse) event).text());
            if (event instanceof CompleteResponse) {
                completed++;
                assertEquals("你好", ((CompleteResponse) event).chatResponse().aiMessage().text());
            }
        }
        assertEquals("你好", text.toString());
        assertEquals(1, completed);
    }

    @Test
    public void cancellingReactiveStreamAbortsHttpCall() throws Exception {
        mode = "long";
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger afterCancel = new AtomicInteger();
        ReactiveJdk8Test.Probe<String> p = new ReactiveJdk8Test.Probe<String>() {

            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                s.request(1);
            }

            public void onNext(String s) {
                super.onNext(s);
                subscription.cancel();
                first.countDown();
            }

            public void onError(Throwable e) {
                afterCancel.incrementAndGet();
            }

            public void onComplete() {
                afterCancel.incrementAndGet();
            }
        };
        streaming().chat("cancel").subscribe(p);
        assertTrue(first.await(5, TimeUnit.SECONDS));
        awaitReleased();
        assertEquals(1, p.values.size());
        assertEquals(0, afterCancel.get());
    }

    @Test
    public void reactiveOverflowAbortsUnderlyingCall() throws Exception {
        mode = "long";
        ReactiveJdk8Test.Probe<ChatModelStreamingEvent> p = new ReactiveJdk8Test.Probe<ChatModelStreamingEvent>();
        OpenAiStreamingChatModel limited = OpenAiStreamingChatModel.builder().apiKey("synthetic").modelName("mock-model").baseUrl(base).httpClientBuilder(http()).streamingBufferSize(1).build();
        limited.chat(ChatRequest.builder().messages(UserMessage.from("overflow")).build()).subscribe(p);
        assertTrue(p.terminal.await(5, TimeUnit.SECONDS));
        assertTrue(p.error instanceof IllegalStateException);
        assertTrue(p.values.isEmpty());
        awaitReleased();
    }

    @Test
    public void callbackCancellationAbortsUnderlyingCall() throws Exception {
        mode = "long";
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger partials = new AtomicInteger(), terminals = new AtomicInteger();
        streaming().chat("cancel", new StreamingChatResponseHandler() {

            public void onPartialResponse(PartialResponse r, PartialResponseContext context) {
                partials.incrementAndGet();
                context.streamingHandle().cancel();
                first.countDown();
            }

            public void onCompleteResponse(ChatResponse r) {
                terminals.incrementAndGet();
            }

            public void onError(Throwable e) {
                terminals.incrementAndGet();
            }
        });
        assertTrue(first.await(5, TimeUnit.SECONDS));
        awaitReleased();
        assertEquals(1, partials.get());
        assertEquals(0, terminals.get());
    }

    @Test
    public void reactiveHttpErrorsReachSubscriber() throws Exception {
        mode = "error";
        ReactiveJdk8Test.Probe<String> p = new ReactiveJdk8Test.Probe<String>();
        streaming().chat("bad").subscribe(p);
        assertTrue(p.terminal.await(5, TimeUnit.SECONDS));
        assertNotNull(p.error);
        assertTrue(p.values.isEmpty());
        assertEquals(0, p.completed.get());
        awaitReleased();
    }

    @Test
    public void errorsAndReadTimeoutArePropagated() throws Exception {
        mode = "error";
        assertThrows(RuntimeException.class, () -> model().chat("bad"));
        assertEquals(1, requests.get());
        CompletableFuture<ChatResponse> f = model().chatAsync(ChatRequest.builder().messages(UserMessage.from("bad async")).build());
        assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        awaitReleased();
        mode = "slow";
        OpenAiChatModel fast = OpenAiChatModel.builder().apiKey("synthetic").modelName("mock-model").baseUrl(base).httpClientBuilder(http()).maxRetries(0).timeout(Duration.ofMillis(50)).build();
        assertThrows(RuntimeException.class, () -> fast.chat("timeout"));
        awaitReleased();
    }
}
