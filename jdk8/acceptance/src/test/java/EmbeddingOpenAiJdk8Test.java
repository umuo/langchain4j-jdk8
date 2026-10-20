import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpExchange;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.internal.Jdk8;
import dev.langchain4j.model.embedding.listener.*;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.openai.*;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.io.IOException;
import java.nio.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.Assert.*;

/** Uses real OkHttp and OpenAI serialization against loopback only. */
public class EmbeddingOpenAiJdk8Test {
    final OpenAiJdk8Test http = new OpenAiJdk8Test();
    final List<JsonNode> requests = new CopyOnWriteArrayList<JsonNode>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> auth = new AtomicReference<String>();
    final AtomicReference<String> org = new AtomicReference<String>();
    final AtomicReference<String> project = new AtomicReference<String>();
    final AtomicReference<String> customHeader = new AtomicReference<String>();
    final AtomicReference<String> query = new AtomicReference<String>();
    volatile String mode = "normal";
    volatile CountDownLatch arrived = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Before public void start() throws Exception {
        http.start();
        http.server.createContext("/v1/embeddings", this::handle);
    }
    @After public void stop() { release.countDown(); http.stop(); }
    OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder() {
        return OpenAiEmbeddingModel.builder().baseUrl(http.base).apiKey("synthetic")
                .modelName("fixture-embedding").dimensions(3).httpClientBuilder(http.http())
                .timeout(Duration.ofSeconds(3)).maxRetries(0);
    }
    static float[] vector(String text) {
        return text.contains("apple") ? new float[]{1,0,0}
                : text.contains("pear") ? new float[]{0,1,0} : new float[]{0,0,1};
    }
    void handle(HttpExchange exchange) throws IOException {
        int call = calls.incrementAndGet();
        JsonNode request = http.mapper.readTree(Jdk8.readAllBytes(exchange.getRequestBody()));
        requests.add(request);
        auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        org.set(exchange.getRequestHeaders().getFirst("OpenAI-Organization"));
        project.set(exchange.getRequestHeaders().getFirst("OpenAI-Project"));
        customHeader.set(exchange.getRequestHeaders().getFirst("X-Fixture"));
        query.set(exchange.getRequestURI().getQuery());
        arrived.countDown();
        try {
            if (mode.equals("hold") || (mode.equals("failSibling") && request.path("input").get(0).asText().contains("apple"))) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (mode.equals("error") || mode.equals("failSibling") || (mode.equals("retry") && call == 1)) {
                http.respond(exchange, mode.equals("retry") ? 429 : 400,
                        "{\"error\":{\"message\":\"embedding fixture failure\",\"type\":\"invalid_request_error\"}}");
                return;
            }
            if (mode.equals("malformedJson")) { http.respond(exchange, 200, "{invalid"); return; }
            ArrayNode input = (ArrayNode) request.path("input");
            ObjectNode response = http.mapper.createObjectNode().put("object", "list").put("model", "fixture-result");
            ArrayNode data = response.putArray("data");
            for (int n = 0; n < input.size(); n++) {
                if (mode.equals("short") && n == input.size()-1) continue;
                int index = mode.equals("reverse") ? input.size()-1-n : n;
                ObjectNode item = data.addObject().put("object", "embedding");
                if (!mode.equals("noIndex") && !(mode.equals("mixed") && n == 0)) item.put("index", mode.equals("duplicate") ? 0 : mode.equals("badIndex") ? input.size() : index);
                if (mode.equals("missingVector")) continue;
                if (mode.equals("emptyVector")) { item.putArray("embedding"); continue; }
                float[] vector = vector(input.get(index).asText());
                if (request.path("encoding_format").asText().equals("base64")) {
                    ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                    for (float value : vector) buffer.putFloat(value);
                    item.put("embedding", Base64.getEncoder().encodeToString(buffer.array()));
                } else {
                    ArrayNode values = item.putArray("embedding");
                    for (float value : vector) values.add(value);
                }
            }
            response.putObject("usage").put("prompt_tokens", input.size()*2).put("total_tokens", input.size()*2);
            http.respond(exchange, 200, response.toString());
        } finally { exchange.close(); }
    }
    static void assertVectors(List<Embedding> actual, String... inputs) {
        assertEquals(inputs.length, actual.size());
        for (int n=0; n<inputs.length; n++) assertArrayEquals(vector(inputs[n]), actual.get(n).vector(), 0f);
    }
    EmbeddingRequest batch() {
        return EmbeddingRequest.builder().inputs("apple", "pear", "other", "apple again", "pear again").build();
    }
    @Test public void synchronousBatchesPreserveOrderAndAggregateUsage() throws Exception {
        EmbeddingResponse response = builder().maxSegmentsPerBatch(2).build().embed(batch());
        assertVectors(response.embeddings(), "apple", "pear", "other", "apple again", "pear again");
        assertEquals(Integer.valueOf(10), response.metadata().tokenUsage().inputTokenCount());
        assertEquals("fixture-result", response.metadata().modelName());
        assertEquals(3, calls.get());
        assertEquals(2, requests.get(0).path("input").size());
        assertEquals(1, requests.get(2).path("input").size());
        http.awaitReleased();
    }
    @Test public void nativeAsyncBatchesPreserveOrderAndAggregateUsage() throws Exception {
        EmbeddingResponse response = builder().maxSegmentsPerBatch(2).build().embedAsync(batch()).get(5, TimeUnit.SECONDS);
        assertVectors(response.embeddings(), "apple", "pear", "other", "apple again", "pear again");
        assertEquals(Integer.valueOf(10), response.metadata().tokenUsage().totalTokenCount());
        assertEquals(3, calls.get());
        http.awaitReleased();
    }
    @Test public void requestOverridesHeadersBase64AndListenersWorkOnJava8() throws Exception {
        AtomicReference<EmbeddingModelRequestContext> before = new AtomicReference<>();
        AtomicReference<EmbeddingModelResponseContext> after = new AtomicReference<>();
        EmbeddingModelListener listener = new EmbeddingModelListener() {
            public void onRequest(EmbeddingModelRequestContext ctx) { before.set(ctx); }
            public void onResponse(EmbeddingModelResponseContext ctx) { after.set(ctx); }
        };
        OpenAiEmbeddingModel model = builder().organizationId("fixture-org").projectId("fixture-project")
                .customHeaders(Collections.singletonMap("X-Fixture", "configured"))
                .customQueryParams(Collections.singletonMap("version", "fixture"))
                .listeners(Collections.singletonList(listener)).build();
        EmbeddingResponse response = model.embedAsync(EmbeddingRequest.builder().input("apple")
                .parameters(OpenAiEmbeddingRequestParameters.builder().modelName("override-model").dimensions(3)
                        .user("fixture-user").encodingFormat("base64").customParameter("fixture_flag", true).build())
                .build()).get(5, TimeUnit.SECONDS);
        assertVectors(response.embeddings(), "apple");
        JsonNode request = requests.get(0);
        assertEquals("override-model", request.path("model").asText());
        assertEquals(3, request.path("dimensions").asInt());
        assertEquals("fixture-user", request.path("user").asText());
        assertTrue(request.path("fixture_flag").asBoolean());
        assertEquals("Bearer synthetic", auth.get());
        assertEquals("fixture-org", org.get()); assertEquals("fixture-project", project.get());
        assertEquals("configured", customHeader.get()); assertEquals("version=fixture", query.get());
        assertNotNull(before.get()); assertNotNull(after.get());
        assertSame(before.get().attributes(), after.get().attributes());
        assertEquals(3, model.dimension());
        http.awaitReleased();
    }
    @Test public void responseIndicesRestoreInputOrderInSyncAndAsync() throws Exception {
        mode = "reverse";
        OpenAiEmbeddingModel model = builder().maxSegmentsPerBatch(2).build();
        EmbeddingRequest request = EmbeddingRequest.builder().inputs("apple", "pear").build();
        assertVectors(model.embed(request).embeddings(), "apple", "pear");
        assertVectors(model.embedAsync(request).get(5, TimeUnit.SECONDS).embeddings(), "apple", "pear");
    }
    @Test public void invalidResponseCountOrIndicesFailInBothPaths() throws Exception {
        for (String invalid : Arrays.asList("short", "duplicate", "badIndex", "mixed", "emptyVector", "missingVector", "malformedJson")) {
            mode = invalid;
            OpenAiEmbeddingModel model = builder().build();
            EmbeddingRequest request = EmbeddingRequest.builder().inputs("apple", "pear").build();
            assertThrows("sync " + invalid, RuntimeException.class, () -> model.embed(request));
            assertThrows("async " + invalid, ExecutionException.class,
                    () -> model.embedAsync(request).get(5, TimeUnit.SECONDS));
        }
        http.awaitReleased();
    }
    @Test public void legacyResponseWithoutIndicesKeepsWireOrder() throws Exception {
        mode = "noIndex";
        assertVectors(builder().build().embedAsync(batch()).get(5, TimeUnit.SECONDS).embeddings(),
                "apple", "pear", "other", "apple again", "pear again");
    }
    @Test public void nonRetriableErrorsNotifyListenerAndDoNotRetry() throws Exception {
        mode = "error";
        AtomicInteger errors = new AtomicInteger();
        OpenAiEmbeddingModel model = builder().maxRetries(2).listeners(Collections.singletonList(new EmbeddingModelListener() {
            public void onError(EmbeddingModelErrorContext context) { errors.incrementAndGet(); }
        })).build();
        assertThrows(RuntimeException.class, () -> model.embed("apple"));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> model.embedAsync(EmbeddingRequest.builder().input("apple").build()).get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("embedding fixture failure"));
        assertEquals(2, calls.get()); assertEquals(2, errors.get());
        http.awaitReleased();
    }
    @Test public void rateLimitRetriesThroughAsyncTransport() throws Exception {
        mode = "retry";
        assertVectors(builder().maxRetries(1).build().embedAsync(EmbeddingRequest.builder().input("apple").build())
                .get(5, TimeUnit.SECONDS).embeddings(), "apple");
        assertEquals(2, calls.get());
        http.awaitReleased();
    }
    @Test public void synchronousAndAsyncTimeoutReleaseTransport() throws Exception {
        mode = "hold";
        OpenAiEmbeddingModel model = builder().timeout(Duration.ofMillis(100)).build();
        assertThrows(RuntimeException.class, () -> model.embed("apple"));
        assertThrows(ExecutionException.class, () -> model.embedAsync(EmbeddingRequest.builder().input("pear").build())
                .get(5, TimeUnit.SECONDS));
        http.awaitReleased();
    }
    @Test public void cancellingAllBatchesReleasesHttpAndSuppressesErrorListener() throws Exception {
        mode = "hold"; arrived = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger();
        OpenAiEmbeddingModel model = builder().maxSegmentsPerBatch(1).listeners(Collections.singletonList(new EmbeddingModelListener() {
            public void onError(EmbeddingModelErrorContext context) { errors.incrementAndGet(); }
        })).build();
        CompletableFuture<EmbeddingResponse> result = model.embedAsync(EmbeddingRequest.builder().inputs("apple", "pear").build());
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        assertFalse(result.isDone()); assertTrue(result.cancel(true));
        http.awaitReleased(); assertEquals(0, errors.get());
    }
    @Test public void batchFailureCancelsSiblingAndPreservesProviderError() throws Exception {
        mode = "failSibling";
        ExecutionException failure = assertThrows(ExecutionException.class, () -> builder().maxSegmentsPerBatch(1).build()
                .embedAsync(EmbeddingRequest.builder().inputs("apple", "pear").build()).get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("embedding fixture failure"));
        http.awaitReleased();
    }
    InMemoryEmbeddingStore<TextSegment> ingest(OpenAiEmbeddingModel model) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor.builder().documentSplitter(new DocumentByParagraphSplitter(30,0))
                .embeddingModel(model).embeddingStore(store).build().ingest(
                        Document.from(RagJdk8Test.APPLE+"\n\n"+RagJdk8Test.PEAR, Metadata.from("tenant","public")),
                        Document.from("apple secret price is 1 yuan.", Metadata.from("tenant","private")));
        return InMemoryEmbeddingStore.fromJson(store.serializeToJson());
    }
    EmbeddingStoreContentRetriever retriever(OpenAiEmbeddingModel model, InMemoryEmbeddingStore<TextSegment> store) {
        return EmbeddingStoreContentRetriever.builder().embeddingModel(model).embeddingStore(store).maxResults(1)
                .minScore(0.9).filter(metadataKey("tenant").isEqualTo("public")).build();
    }
    void assertChatContext(String expected) throws Exception {
        String prompt = http.request.get().path("messages").toString();
        assertTrue(prompt, prompt.contains(expected)); assertFalse(prompt, prompt.contains("secret"));
        http.awaitReleased();
    }
    @Test public void httpEmbeddingAndChatCompleteSyncAndAsyncRagAfterRestore() throws Exception {
        OpenAiEmbeddingModel model = builder().maxSegmentsPerBatch(2).build();
        InMemoryEmbeddingStore<TextSegment> store = ingest(model);
        RagJdk8Test.Answer sync = AiServices.builder(RagJdk8Test.Answer.class).chatModel(http.model())
                .contentRetriever(retriever(model, store)).build();
        Result<String> result = sync.chat("apple?");
        assertEquals("你好", result.content()); assertEquals(RagJdk8Test.APPLE, result.sources().get(0).textSegment().text());
        assertChatContext(RagJdk8Test.APPLE);
        RagJdk8Test.AsyncAnswer async = AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(http.model())
                .contentRetriever(retriever(model, store)).build();
        assertEquals("你好", async.chat("pear?").get(5, TimeUnit.SECONDS).content());
        assertChatContext(RagJdk8Test.PEAR);
        assertEquals(4, calls.get());
    }
    @Test public void httpEmbeddingFeedsCallbackAndReactiveSseRag() throws Exception {
        OpenAiEmbeddingModel model = builder().build();
        InMemoryEmbeddingStore<TextSegment> store = ingest(model);
        AiServicesJdk8Test.Streaming callback = AiServices.builder(AiServicesJdk8Test.Streaming.class)
                .streamingChatModel(http.streaming()).contentRetriever(retriever(model, store)).build();
        CompletableFuture<String> result = new CompletableFuture<>();
        callback.chat("apple?").onPartialResponse(t -> {}).onCompleteResponse(r -> result.complete(r.aiMessage().text()))
                .onError(result::completeExceptionally).start();
        assertEquals("你好", result.get(5, TimeUnit.SECONDS)); assertChatContext(RagJdk8Test.APPLE);
        AiServicesJdk8Test.Reactive reactive = AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(http.streaming()).contentRetriever(retriever(model, store)).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>();
        reactive.chat("pear?").subscribe(probe); probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS)); assertNull(probe.error);
        assertEquals("你好", String.join("", probe.values)); assertEquals(1, probe.completed.get());
        assertChatContext(RagJdk8Test.PEAR);
    }
    @Test public void cancellingRagDuringHttpEmbeddingAvoidsChatAndReleasesCall() throws Exception {
        OpenAiEmbeddingModel model = builder().build();
        InMemoryEmbeddingStore<TextSegment> store = ingest(model);
        mode = "hold"; arrived = new CountDownLatch(1);
        RagJdk8Test.AsyncAnswer service = AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(http.model())
                .contentRetriever(retriever(model, store)).build();
        CompletableFuture<Result<String>> result = service.chat("apple?");
        assertTrue(arrived.await(5, TimeUnit.SECONDS)); result.cancel(true);
        http.awaitReleased(); assertEquals(0, http.requests.get());
    }
    @Test public void invalidEmbeddingResponseDoesNotPopulateStore() {
        mode = "short";
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        assertThrows(RuntimeException.class, () -> EmbeddingStoreIngestor.builder().embeddingModel(builder().build())
                .embeddingStore(store).build().ingest(Document.from("apple"), Document.from("pear")));
        assertTrue(store.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vector("apple"))).build()).matches().isEmpty());
    }
    @Test public void transportCancellationTerminatesEmbeddingInsteadOfHanging() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        dev.langchain4j.http.client.HttpClient client = new dev.langchain4j.http.client.HttpClient() {
            public dev.langchain4j.http.client.SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
                throw new AssertionError("Async transport required");
            }
            public CompletableFuture<dev.langchain4j.http.client.SuccessfulHttpResponse> executeAsync(
                    dev.langchain4j.http.client.HttpRequest request) {
                attempts.incrementAndGet();
                CompletableFuture<dev.langchain4j.http.client.SuccessfulHttpResponse> cancelled = new CompletableFuture<>();
                cancelled.cancel(true);
                return cancelled;
            }
            public void execute(dev.langchain4j.http.client.HttpRequest request,
                    dev.langchain4j.http.client.sse.ServerSentEventParser parser,
                    dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                throw new AssertionError("No SSE expected");
            }
        };
        dev.langchain4j.http.client.HttpClientBuilder factory = new dev.langchain4j.http.client.HttpClientBuilder() {
            public Duration connectTimeout() { return null; }
            public Duration readTimeout() { return null; }
            public dev.langchain4j.http.client.HttpClientBuilder connectTimeout(Duration value) { return this; }
            public dev.langchain4j.http.client.HttpClientBuilder readTimeout(Duration value) { return this; }
            public dev.langchain4j.http.client.HttpClient build() { return client; }
        };
        CompletableFuture<EmbeddingResponse> result = builder().httpClientBuilder(factory).maxRetries(2).build()
                .embedAsync(EmbeddingRequest.builder().input("apple").build());
        assertTrue("A cancelled transport must not leave the caller waiting", result.isDone());
        try { result.get(5, TimeUnit.SECONDS); fail("transport cancellation expected"); }
        catch (CancellationException expected) { }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
        assertEquals(1, attempts.get());
    }

    @Test public void cancellingReactiveRagDuringHttpEmbeddingReleasesCall() throws Exception {
        OpenAiEmbeddingModel model = builder().build();
        InMemoryEmbeddingStore<TextSegment> store = ingest(model);
        mode = "hold"; arrived = new CountDownLatch(1);
        AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(http.streaming()).contentRetriever(retriever(model, store)).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>();
        service.chat("apple?").subscribe(probe); probe.subscription.request(1);
        assertTrue(arrived.await(5, TimeUnit.SECONDS)); probe.subscription.cancel();
        http.awaitReleased(); assertEquals(0, http.requests.get());
        assertTrue(probe.values.isEmpty()); assertNull(probe.error); assertEquals(0, probe.completed.get());
    }
    @Test public void queryEmbeddingFailureDoesNotStartChat() throws Exception {
        OpenAiEmbeddingModel model = builder().build();
        InMemoryEmbeddingStore<TextSegment> store = ingest(model);
        mode = "error";
        RagJdk8Test.AsyncAnswer service = AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(http.model())
                .contentRetriever(retriever(model, store)).build();
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service.chat("apple?").get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("embedding fixture failure"));
        assertEquals(0, http.requests.get()); http.awaitReleased();
    }
    @Test public void rateLimitRetriesThroughSynchronousTransport() throws Exception {
        mode = "retry";
        Response<Embedding> response = builder().maxRetries(1).build().embed("apple");
        assertArrayEquals(vector("apple"), response.content().vector(), 0f);
        assertEquals(2, calls.get()); http.awaitReleased();
    }

}
