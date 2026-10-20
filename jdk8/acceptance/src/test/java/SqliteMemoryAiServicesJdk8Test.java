import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.*;
import dev.langchain4j.store.memory.chat.sqlite.SqliteChatMemoryStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.reactivestreams.Publisher;
import static org.junit.Assert.*;

public class SqliteMemoryAiServicesJdk8Test {
    final OpenAiJdk8Test http=new OpenAiJdk8Test();
    Path directory,database;
    SqliteChatMemoryStore store;
    ExecutorService executor;
    @Before public void start() throws Exception {
        http.start();directory=Files.createTempDirectory(Paths.get("target"),"sqlite-service-memory-");database=directory.resolve("chat.db");
        executor=Executors.newSingleThreadExecutor();reopen();
    }
    @After public void stop() throws Exception {
        executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);store.close();http.stop();
        try(java.util.stream.Stream<Path> files=Files.walk(directory)) {
            for(Path file:files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(file);
        }
    }
    void reopen() { if(store!=null) store.close();store=SqliteChatMemoryStore.builder().databasePath(database).asyncExecutor(executor).build(); }
    ChatMemory memory(Object id) { return MessageWindowChatMemory.builder().id(id).maxMessages(20).chatMemoryStore(store).build(); }
    public interface Streaming { TokenStream chat(@MemoryId String id,@dev.langchain4j.service.UserMessage String question); }
    public interface Reactive { Publisher<String> chat(@MemoryId String id,@dev.langchain4j.service.UserMessage String question); }
    @Test public void synchronousSessionsReopenAndCacheEvictionKeepsDurableHistory() throws Exception {
        AiServicesJdk8Test.Sessions first=AiServices.builder(AiServicesJdk8Test.Sessions.class).chatModel(http.model()).chatMemoryProvider(this::memory).build();
        first.chat("a","first");first.chat("b","isolated");assertEquals(2,store.getMessages("a").size());reopen();
        AiServicesJdk8Test.Sessions next=AiServices.builder(AiServicesJdk8Test.Sessions.class).chatModel(http.model()).chatMemoryProvider(this::memory).build();
        next.chat("a","second");assertEquals(3,http.request.get().path("messages").size());assertEquals(4,store.getMessages("a").size());
        assertEquals(2,store.getMessages("b").size());assertTrue(next.evictChatMemory("a"));
        next.chat("a","third");assertEquals(5,http.request.get().path("messages").size());
        next.getChatMemory("a").clear();assertTrue(store.getMessages("a").isEmpty());assertEquals(2,store.getMessages("b").size());http.awaitReleased();
    }
    @Test public void futureSessionsUsePersistentAsyncMemory() throws Exception {
        AiServicesJdk8Test.AsyncSessions service=AiServices.builder(AiServicesJdk8Test.AsyncSessions.class).chatModel(http.model()).chatMemoryProvider(this::memory).build();
        service.chat("a","first").get(5,TimeUnit.SECONDS);service.chat("a","second").get(5,TimeUnit.SECONDS);
        assertEquals(3,http.request.get().path("messages").size());assertEquals(4,store.getMessages("a").size());
        service.chat("b","other").get(5,TimeUnit.SECONDS);assertEquals(1,http.request.get().path("messages").size());http.awaitReleased();
    }
    @Test public void toolRequestAndResultAreReusedAfterReopeningMemory() throws Exception {
        http.mode="toolOnce";AiServicesJdk8Test.Tools tools=new AiServicesJdk8Test.Tools();
        AiServicesJdk8Test.Async service=AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(http.model()).chatMemory(memory("tools")).tools(tools).build();
        assertEquals("你好",service.chat("weather").get(5,TimeUnit.SECONDS));assertEquals(1,tools.invocations.get());
        List<ChatMessage> before=store.getMessages("tools");assertEquals(4,before.size());assertTrue(before.get(1) instanceof AiMessage);
        assertTrue(before.get(2) instanceof ToolExecutionResultMessage);reopen();assertEquals(before,store.getMessages("tools"));http.mode="normal";
        AiServicesJdk8Test.Async resumed=AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(http.model()).chatMemory(memory("tools")).tools(tools).build();
        resumed.chat("follow up").get(5,TimeUnit.SECONDS);assertEquals(5,http.request.get().path("messages").size());
        assertEquals("call1",http.request.get().path("messages").get(2).path("tool_call_id").asText());
        assertEquals(1,tools.invocations.get());assertEquals(6,store.getMessages("tools").size());http.awaitReleased();
    }
    @Test public void callbackAndPublisherPersistCompleteConversations() throws Exception {
        Streaming callback=AiServices.builder(Streaming.class).streamingChatModel(http.streaming()).chatMemoryProvider(this::memory).build();
        CompletableFuture<String> text=new CompletableFuture<>();
        callback.chat("callback","first").onPartialResponse(t -> {}).onCompleteResponse(r -> text.complete(r.aiMessage().text())).onError(text::completeExceptionally).start();
        assertEquals("你好",text.get(5,TimeUnit.SECONDS));assertEquals(2,store.getMessages("callback").size());
        Reactive reactive=AiServices.builder(Reactive.class).streamingChatModel(http.streaming()).chatMemoryProvider(this::memory).build();
        ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();reactive.chat("reactive","first").subscribe(probe);probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));assertNull(probe.error);assertEquals("你好",String.join("",probe.values));
        assertEquals(1,probe.completed.get());assertEquals(2,store.getMessages("reactive").size());http.awaitReleased();
    }
    @Test public void defaultBlockingMemoryFailsAsyncBeforeCallingModel() throws Exception {
        try(SqliteChatMemoryStore blocking=SqliteChatMemoryStore.builder().databasePath(database).build()) {
            AiServicesJdk8Test.Async service=AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(http.model())
                    .chatMemory(MessageWindowChatMemory.builder().maxMessages(10).chatMemoryStore(blocking).build()).build();
            ExecutionException error=assertThrows(ExecutionException.class,() -> service.chat("question").get(5,TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof dev.langchain4j.exception.UnsupportedFeatureException);assertEquals(0,http.requests.get());
        }
    }
    @Test public void cancellingFutureBeforeMemoryIoDoesNotPersistCancelledQuestion() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try {
            Future<?> block=executor.submit(() -> ReactiveConcurrencyJdk8Test.await(release));
            AiServicesJdk8Test.Async service=AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(http.model()).chatMemory(memory("cancelled")).build();
            CompletableFuture<String> result=service.chat("do not store");assertTrue(result.cancel(true));
            release.countDown();block.get(5,TimeUnit.SECONDS);for(int i=0;i<5;i++) executor.submit(() -> {}).get(5,TimeUnit.SECONDS);
            assertTrue("Cancelled memory prologue must not write a user message",store.getMessages("cancelled").isEmpty());assertEquals(0,http.requests.get());
        } finally { release.countDown(); }
    }
    @Test public void cancellingPublisherBeforeMemoryIoDoesNotPersistCancelledQuestion() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try {
            Future<?> block=executor.submit(() -> ReactiveConcurrencyJdk8Test.await(release));
            Reactive service=AiServices.builder(Reactive.class).streamingChatModel(http.streaming()).chatMemoryProvider(this::memory).build();
            ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();service.chat("cancelled","do not store").subscribe(probe);
            probe.subscription.request(1);probe.subscription.cancel();release.countDown();block.get(5,TimeUnit.SECONDS);for(int i=0;i<5;i++) executor.submit(() -> {}).get(5,TimeUnit.SECONDS);
            assertTrue(store.getMessages("cancelled").isEmpty());assertEquals(0,http.requests.get());assertNull(probe.error);assertEquals(0,probe.completed.get());
        } finally { release.countDown(); }
    }
}
