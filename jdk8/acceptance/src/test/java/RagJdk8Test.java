import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.Assert.*;

/** Deterministic vectors validate plumbing, not semantic embedding quality. No external model. */
public class RagJdk8Test {
    static final String APPLE = "apple price is 7 yuan.";
    static final String PEAR = "pear price is 9 yuan.";
    static final EmbeddingModel EMBEDDINGS = new EmbeddingModel() {
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> result = new ArrayList<Embedding>();
            for (TextSegment segment : segments) {
                String text = segment.text();
                result.add(Embedding.from(text.contains("apple") ? new float[]{1,0,0}
                        : text.contains("pear") ? new float[]{0,1,0} : new float[]{0,0,1}));
            }
            return Response.from(result);
        }
        public CompletableFuture<EmbeddingResponse> doEmbedAsync(EmbeddingRequest request) {
            return CompletableFuture.completedFuture(doEmbed(request));
        }
    };
    public interface Answer { Result<String> chat(String question); }
    public interface AsyncAnswer { CompletableFuture<Result<String>> chat(String question); }

    static InMemoryEmbeddingStore<TextSegment> store() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<TextSegment>();
        EmbeddingStoreIngestor.builder().documentSplitter(new DocumentByParagraphSplitter(30, 0))
                .embeddingModel(EMBEDDINGS).embeddingStore(store).build().ingest(
                        Document.from(APPLE + "\n\n" + PEAR, Metadata.from("tenant", "public")),
                        Document.from("apple secret price is 1 yuan.", Metadata.from("tenant", "private")));
        return store;
    }
    static EmbeddingStoreContentRetriever retriever(InMemoryEmbeddingStore<TextSegment> store) {
        return EmbeddingStoreContentRetriever.builder().embeddingModel(EMBEDDINGS).embeddingStore(store)
                .maxResults(1).minScore(0.9).filter(metadataKey("tenant").isEqualTo("public")).build();
    }
    static String prompt(dev.langchain4j.model.chat.request.ChatRequest request) {
        return ((UserMessage) request.messages().get(request.messages().size()-1)).singleText();
    }
    static AiMessage answer(dev.langchain4j.model.chat.request.ChatRequest request) {
        String prompt = prompt(request);
        if (prompt.contains("secret")) throw new AssertionError("private document leaked");
        return AiMessage.from(prompt.contains(APPLE) ? "7 yuan" : prompt.contains(PEAR) ? "9 yuan" : "unknown");
    }
    @Test public void ingestionSplitsMultipleDocumentsAndPreservesMetadata() {
        InMemoryEmbeddingStore<TextSegment> store = store();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(EMBEDDINGS.embed("apple").content()).maxResults(10).build();
        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        assertEquals(3, matches.size());
        Set<Integer> publicIndexes = new HashSet<Integer>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            Metadata metadata = match.embedded().metadata();
            if ("public".equals(metadata.getString("tenant"))) publicIndexes.add(metadata.getInteger("index"));
        }
        assertEquals(new HashSet<Integer>(Arrays.asList(0,1)), publicIndexes);
    }
    @Test public void filteringTopKAndEmptyResultsMatchInSyncAndAsync() throws Exception {
        EmbeddingStoreContentRetriever retriever = retriever(store());
        List<Content> contents = retriever.retrieve(Query.from("apple?"));
        assertEquals(1, contents.size());
        assertEquals(APPLE, contents.get(0).textSegment().text());
        assertEquals(contents, retriever.retrieveAsync(Query.from("apple?")).get(5, TimeUnit.SECONDS));
        assertTrue(retriever.retrieve(Query.from("banana?")).isEmpty());
        assertTrue(retriever.retrieveAsync(Query.from("banana?")).get(5, TimeUnit.SECONDS).isEmpty());
    }
    @Test public void jsonAndFileRestorePreserveVectorsMetadataAndRemoval() throws Exception {
        InMemoryEmbeddingStore<TextSegment> original = store();
        Path file = Files.createTempFile(Paths.get("target"), "rag-store-", ".json");
        try {
            original.serializeToFile(file);
            for (InMemoryEmbeddingStore<TextSegment> restored : Arrays.asList(
                    InMemoryEmbeddingStore.fromJson(original.serializeToJson()), InMemoryEmbeddingStore.fromFile(file))) {
                assertEquals(APPLE, retriever(restored).retrieve(Query.from("apple")).get(0).textSegment().text());
                restored.removeAll(metadataKey("tenant").isEqualTo("public"));
                assertTrue(retriever(restored).retrieve(Query.from("apple")).isEmpty());
                assertEquals(APPLE, retriever(original).retrieve(Query.from("apple")).get(0).textSegment().text());
            }
            assertThrows(RuntimeException.class, () -> InMemoryEmbeddingStore.fromJson("{invalid"));
        } finally { Files.deleteIfExists(file); }
    }
    @Test public void aiServicesInjectRetrievedTextAndReturnSourceAttribution() {
        Answer service = AiServices.builder(Answer.class).chatModel(AiServicesJdk8Test.model(RagJdk8Test::answer))
                .contentRetriever(retriever(store())).build();
        Result<String> result = service.chat("apple?");
        assertEquals("7 yuan", result.content());
        assertEquals(1, result.sources().size());
        assertEquals(APPLE, result.sources().get(0).textSegment().text());
        assertEquals("public", result.sources().get(0).textSegment().metadata().getString("tenant"));
        Result<String> empty = service.chat("banana?");
        assertEquals("unknown", empty.content());
        assertTrue(empty.sources().isEmpty());
    }
    @Test public void asynchronousAiServicesRunTheWholeRetrievalPipeline() throws Exception {
        AsyncAnswer service = AiServices.builder(AsyncAnswer.class).chatModel(AiServicesJdk8Test.model(RagJdk8Test::answer))
                .contentRetriever(retriever(store())).build();
        Result<String> result = service.chat("pear?").get(5, TimeUnit.SECONDS);
        assertEquals("9 yuan", result.content());
        assertEquals(PEAR, result.sources().get(0).textSegment().text());
    }
    @Test public void callbackAndPublisherReceiveAugmentedPrompts() throws Exception {
        AtomicReference<String> prompt = new AtomicReference<String>();
        dev.langchain4j.model.chat.StreamingChatModel model = new dev.langchain4j.model.chat.StreamingChatModel() {
            public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                prompt.set(prompt(request));
                String text = answer(request).text();
                handler.onPartialResponse(text);
                handler.onCompleteResponse(AiServicesJdk8Test.response(AiMessage.from(text)));
            }
            public org.reactivestreams.Publisher<dev.langchain4j.model.chat.response.ChatModelStreamingEvent> doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                return dev.langchain4j.internal.reactive.ZeroPublisher.create(
                        new dev.langchain4j.internal.reactive.TubeConfiguration(), tube -> {
                    prompt.set(prompt(request));
                    String text = answer(request).text();
                    tube.send(new dev.langchain4j.model.chat.response.PartialResponse(text));
                    tube.send(new dev.langchain4j.model.chat.response.CompleteResponse(
                            AiServicesJdk8Test.response(AiMessage.from(text))));
                    tube.complete();
                });
            }
        };
        AiServicesJdk8Test.Streaming callback = AiServices.builder(AiServicesJdk8Test.Streaming.class)
                .streamingChatModel(model).contentRetriever(retriever(store())).build();
        CompletableFuture<String> result = new CompletableFuture<String>();
        callback.chat("apple?").onPartialResponse(t -> {}).onCompleteResponse(r -> result.complete(r.aiMessage().text()))
                .onError(result::completeExceptionally).start();
        assertEquals("7 yuan", result.get(5, TimeUnit.SECONDS));
        assertTrue(prompt.get().contains(APPLE));
        AiServicesJdk8Test.Reactive reactive = AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(model).contentRetriever(retriever(store())).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<String>();
        reactive.chat("pear?").subscribe(probe);
        probe.subscription.request(1);
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS));
        assertNull(probe.error);
        assertEquals(Collections.singletonList("9 yuan"), probe.values);
        assertEquals(1, probe.completed.get());
        assertTrue(prompt.get().contains(PEAR));
    }
    @Test public void loadersSkipBlankAndBrokenDocumentsButSingleLoadFails() throws Exception {
        Path dir = Files.createTempDirectory(Paths.get("target"), "rag-documents-");
        Path good = dir.resolve("good.txt"), blank = dir.resolve("blank.txt"), broken = dir.resolve("broken.txt");
        try {
            Files.write(good, APPLE.getBytes(StandardCharsets.UTF_8));
            Files.write(blank, new byte[0]);
            Files.write(broken, "broken".getBytes(StandardCharsets.UTF_8));
            dev.langchain4j.data.document.DocumentParser parser = input -> {
                Document doc = new TextDocumentParser().parse(input);
                if (doc.text().equals("broken")) throw new IllegalStateException("parser failure");
                return doc;
            };
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(dir, parser);
            assertEquals(1, documents.size());
            assertEquals(APPLE, documents.get(0).text());
            assertEquals("good.txt", documents.get(0).metadata().getString("file_name"));
            RuntimeException failure = assertThrows(RuntimeException.class, () -> FileSystemDocumentLoader.loadDocument(broken, parser));
            assertEquals("parser failure", failure.getCause().getMessage());
            assertThrows(IllegalArgumentException.class, () -> FileSystemDocumentLoader.loadDocument(dir.resolve("missing"), parser));
        } finally {
            Files.deleteIfExists(good); Files.deleteIfExists(blank); Files.deleteIfExists(broken); Files.deleteIfExists(dir);
        }
    }
    @Test public void embeddingFailureDoesNotWritePartialIngestion() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<TextSegment>();
        EmbeddingModel failing = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("embedding failed");
            }
        };
        assertThrows(IllegalStateException.class, () -> EmbeddingStoreIngestor.builder().embeddingModel(failing)
                .embeddingStore(store).build().ingest(Document.from(APPLE)));
        assertTrue(retriever(store).retrieve(Query.from("apple")).isEmpty());
    }
    @Test public void cancellingRetrievalCancelsPendingEmbedding() {
        CompletableFuture<EmbeddingResponse> pending = new CompletableFuture<EmbeddingResponse>();
        EmbeddingModel model = new EmbeddingModel() {
            public CompletableFuture<EmbeddingResponse> doEmbedAsync(EmbeddingRequest request) { return pending; }
        };
        CompletableFuture<List<Content>> result = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(model).embeddingStore(store()).build().retrieveAsync(Query.from("apple"));
        assertFalse(result.isDone());
        assertTrue(result.cancel(true));
        assertTrue("Embedding must not keep running after retrieval cancellation", pending.isCancelled());
    }
    @Test public void blockingEmbeddingRequiresExplicitOffloadOnJava8() throws Exception {
        EmbeddingModel blocking = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) { return EMBEDDINGS.embedAll(segments); }
        };
        EmbeddingStoreContentRetriever strict = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(blocking).embeddingStore(store()).build();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> strict.retrieveAsync(Query.from("apple")).get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof dev.langchain4j.exception.UnsupportedFeatureException);
        EmbeddingStoreContentRetriever offloaded = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(blocking).embeddingStore(store()).offloadBlocking(true)
                .filter(metadataKey("tenant").isEqualTo("public")).minScore(0.9).build();
        assertEquals(APPLE, offloaded.retrieveAsync(Query.from("apple")).get(5, TimeUnit.SECONDS).get(0).textSegment().text());
    }

    @Test public void memoryCanKeepOriginalQuestionWhileModelReceivesRetrievedContext() {
        dev.langchain4j.memory.ChatMemory memory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10);
        Answer service = AiServices.builder(Answer.class).chatModel(AiServicesJdk8Test.model(RagJdk8Test::answer))
                .chatMemory(memory).storeRetrievedContentInChatMemory(false).contentRetriever(retriever(store())).build();
        assertEquals("7 yuan", service.chat("apple?").content());
        assertEquals("apple?", ((UserMessage) memory.messages().get(0)).singleText());
        assertEquals("7 yuan", ((AiMessage) memory.messages().get(1)).text());
    }
    @Test public void cancellingAiServiceDuringRetrievalReachesEmbeddingAndAvoidsModelCall() {
        CompletableFuture<EmbeddingResponse> pending = new CompletableFuture<EmbeddingResponse>();
        EmbeddingModel model = new EmbeddingModel() {
            public CompletableFuture<EmbeddingResponse> doEmbedAsync(EmbeddingRequest request) { return pending; }
        };
        AsyncAnswer service = AiServices.builder(AsyncAnswer.class).chatModel(AiServicesJdk8Test.model(r -> {
            throw new AssertionError("Model should not run before retrieval completes");
        })).contentRetriever(EmbeddingStoreContentRetriever.builder().embeddingModel(model).embeddingStore(store()).build()).build();
        CompletableFuture<Result<String>> result = service.chat("apple?");
        assertFalse(result.isDone());
        assertTrue(result.cancel(true));
        assertTrue("AI Service cancellation must reach embedding", pending.isCancelled());
    }
    @Test public void retrievalFailurePropagatesAndAvoidsCallingChatModel() throws Exception {
        EmbeddingModel model = new EmbeddingModel() {
            public CompletableFuture<EmbeddingResponse> doEmbedAsync(EmbeddingRequest request) {
                return dev.langchain4j.internal.Jdk8.failedFuture(new IllegalStateException("embedding unavailable"));
            }
        };
        AsyncAnswer service = AiServices.builder(AsyncAnswer.class).chatModel(AiServicesJdk8Test.model(r -> {
            throw new AssertionError("Model should not run after retrieval failure");
        })).contentRetriever(EmbeddingStoreContentRetriever.builder().embeddingModel(model).embeddingStore(store()).build()).build();
        ExecutionException error = assertThrows(ExecutionException.class, () -> service.chat("apple?").get(5, TimeUnit.SECONDS));
        assertEquals("embedding unavailable", error.getCause().getMessage());
    }

    @Test public void cancellingReactiveSubscriptionDuringRetrievalCancelsEmbedding() {
        CompletableFuture<EmbeddingResponse> pending = new CompletableFuture<EmbeddingResponse>();
        EmbeddingModel model = new EmbeddingModel() {
            public CompletableFuture<EmbeddingResponse> doEmbedAsync(EmbeddingRequest request) { return pending; }
        };
        AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(AiServicesJdk8Test.streamingModel()).contentRetriever(
                        EmbeddingStoreContentRetriever.builder().embeddingModel(model).embeddingStore(store()).build()).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<String>();
        service.chat("apple?").subscribe(probe);
        probe.subscription.request(1);
        probe.subscription.cancel();
        assertTrue(pending.isCancelled());
        assertTrue(probe.values.isEmpty());
        assertEquals(0, probe.completed.get());
        assertNull(probe.error);
    }
    @Test public void cancellationDuringMemoryReadStopsReadAndDoesNotStartRetrieval() {
        CompletableFuture<List<dev.langchain4j.data.message.ChatMessage>> pending = new CompletableFuture<>();
        dev.langchain4j.memory.ChatMemory memory = new dev.langchain4j.memory.ChatMemory() {
            public Object id() { return "pending-session"; }
            public void add(dev.langchain4j.data.message.ChatMessage message) { throw new AssertionError("No memory write expected"); }
            public List<dev.langchain4j.data.message.ChatMessage> messages() { throw new AssertionError("No blocking read expected"); }
            public void clear() { }
            public CompletableFuture<List<dev.langchain4j.data.message.ChatMessage>> messagesAsync() { return pending; }
        };
        AsyncAnswer service = AiServices.builder(AsyncAnswer.class).chatMemory(memory)
                .chatModel(AiServicesJdk8Test.model(r -> { throw new AssertionError("No model call expected"); }))
                .contentRetriever(query -> { throw new AssertionError("No retrieval expected before memory read"); }).build();
        CompletableFuture<Result<String>> result = service.chat("apple?");
        assertFalse(result.isDone());
        result.cancel(true);
        assertTrue(pending.isCancelled());
    }

}
