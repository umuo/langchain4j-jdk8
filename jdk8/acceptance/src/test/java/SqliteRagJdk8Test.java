import dev.langchain4j.data.document.*;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.*;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.sqlite.SqliteEmbeddingStore;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.Assert.*;

public class SqliteRagJdk8Test {
    final EmbeddingOpenAiJdk8Test fixture=new EmbeddingOpenAiJdk8Test();
    Path directory,database;
    SqliteEmbeddingStore store;
    OpenAiEmbeddingModel embeddings;
    @Before public void start() throws Exception {
        fixture.start();directory=Files.createTempDirectory(Paths.get("target"),"sqlite-rag-");database=directory.resolve("vectors.db");
        embeddings=fixture.builder().maxSegmentsPerBatch(2).build();
        try(SqliteEmbeddingStore initial=SqliteEmbeddingStore.builder().databasePath(database).build()) {
            EmbeddingStoreIngestor.builder().documentSplitter(new DocumentByParagraphSplitter(30,0))
                    .embeddingModel(embeddings).embeddingStore(initial).build().ingest(
                            Document.from(RagJdk8Test.APPLE+"\n\n"+RagJdk8Test.PEAR,Metadata.from("tenant","public")),
                            Document.from("apple secret price is 1 yuan.",Metadata.from("tenant","private")));
        }
        store=SqliteEmbeddingStore.builder().databasePath(database).build();
    }
    @After public void stop() throws Exception {
        if(store!=null) store.close();fixture.stop();
        try(java.util.stream.Stream<Path> files=Files.walk(directory)) {
            for(Path file:files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(file);
        }
    }
    EmbeddingStoreContentRetriever retriever() {
        return EmbeddingStoreContentRetriever.builder().embeddingModel(embeddings).embeddingStore(store).offloadBlocking(true)
                .maxResults(1).minScore(0.9).filter(metadataKey("tenant").isEqualTo("public")).build();
    }
    void context(String text) throws Exception {
        String prompt=fixture.http.request.get().path("messages").toString();
        assertTrue(prompt,prompt.contains(text));assertFalse(prompt,prompt.contains("secret"));fixture.http.awaitReleased();
    }
    @Test public void reopenedSqliteCompletesSyncAndAsyncHttpRagWithSources() throws Exception {
        RagJdk8Test.Answer sync=AiServices.builder(RagJdk8Test.Answer.class).chatModel(fixture.http.model()).contentRetriever(retriever()).build();
        Result<String> result=sync.chat("apple?");assertEquals("你好",result.content());
        assertEquals(RagJdk8Test.APPLE,result.sources().get(0).textSegment().text());context(RagJdk8Test.APPLE);
        RagJdk8Test.AsyncAnswer async=AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(fixture.http.model()).contentRetriever(retriever()).build();
        assertEquals("你好",async.chat("pear?").get(5,TimeUnit.SECONDS).content());context(RagJdk8Test.PEAR);
    }
    @Test public void reopenedSqliteCompletesCallbackAndReactiveSseRag() throws Exception {
        AiServicesJdk8Test.Streaming callback=AiServices.builder(AiServicesJdk8Test.Streaming.class)
                .streamingChatModel(fixture.http.streaming()).contentRetriever(retriever()).build();
        CompletableFuture<String> result=new CompletableFuture<>();
        callback.chat("apple?").onPartialResponse(t -> {}).onCompleteResponse(r -> result.complete(r.aiMessage().text()))
                .onError(result::completeExceptionally).start();
        assertEquals("你好",result.get(5,TimeUnit.SECONDS));context(RagJdk8Test.APPLE);
        AiServicesJdk8Test.Reactive reactive=AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(fixture.http.streaming()).contentRetriever(retriever()).build();
        ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();
        reactive.chat("pear?").subscribe(probe);probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));assertNull(probe.error);
        assertEquals("你好",String.join("",probe.values));assertEquals(1,probe.completed.get());context(RagJdk8Test.PEAR);
    }
    @Test public void cancellationBeforeSqliteSearchReleasesEmbeddingAndAvoidsChat() throws Exception {
        fixture.mode="hold";fixture.arrived=new CountDownLatch(1);
        RagJdk8Test.AsyncAnswer async=AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(fixture.http.model()).contentRetriever(retriever()).build();
        CompletableFuture<Result<String>> result=async.chat("apple?");
        assertTrue(fixture.arrived.await(5,TimeUnit.SECONDS));result.cancel(true);fixture.http.awaitReleased();
        assertEquals(0,fixture.http.requests.get());assertEquals(3,store.size());
    }
}
