import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.*;
import dev.langchain4j.store.memory.chat.sqlite.SqliteChatMemoryStore;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class SqliteMemoryRagJdk8Test {
    final SqliteRagJdk8Test fixture=new SqliteRagJdk8Test();
    ExecutorService executor;
    SqliteChatMemoryStore memories;
    @Before public void start() throws Exception {
        fixture.start();executor=Executors.newSingleThreadExecutor();reopen();
    }
    void reopen() {
        if(memories!=null) memories.close();
        memories=SqliteChatMemoryStore.builder().databasePath(fixture.database).asyncExecutor(executor).build();
    }
    MessageWindowChatMemory memory() { return MessageWindowChatMemory.builder().id("conversation").maxMessages(10).chatMemoryStore(memories).build(); }
    @After public void stop() throws Exception {
        executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);memories.close();fixture.stop();
    }
    @Test public void persistentRagAndMemoryReopenWithoutRetainingInjectedContext() throws Exception {
        RagJdk8Test.Answer first=AiServices.builder(RagJdk8Test.Answer.class).chatModel(fixture.fixture.http.model())
                .chatMemory(memory()).storeRetrievedContentInChatMemory(false).contentRetriever(fixture.retriever()).build();
        Result<String> result=first.chat("apple?");assertEquals("你好",result.content());assertEquals(1,result.sources().size());
        fixture.context(RagJdk8Test.APPLE);assertEquals("apple?",((UserMessage)memories.getMessages("conversation").get(0)).singleText());
        reopen();
        RagJdk8Test.AsyncAnswer resumed=AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(fixture.fixture.http.model())
                .chatMemory(memory()).storeRetrievedContentInChatMemory(false).contentRetriever(fixture.retriever()).build();
        assertEquals("你好",resumed.chat("pear?").get(5,TimeUnit.SECONDS).content());fixture.context(RagJdk8Test.PEAR);
        assertEquals(3,fixture.fixture.http.request.get().path("messages").size());assertEquals(4,memories.getMessages("conversation").size());
        assertEquals("pear?",((UserMessage)memories.getMessages("conversation").get(2)).singleText());assertEquals(3,fixture.store.size());
    }
    @Test public void durableMemoryAndVectorRowsSurviveCallbackThenReactiveRag() throws Exception {
        AiServicesJdk8Test.Streaming callback=AiServices.builder(AiServicesJdk8Test.Streaming.class)
                .streamingChatModel(fixture.fixture.http.streaming()).chatMemory(memory()).contentRetriever(fixture.retriever()).build();
        CompletableFuture<String> result=new CompletableFuture<>();
        callback.chat("apple?").onPartialResponse(t -> {}).onCompleteResponse(r -> result.complete(r.aiMessage().text())).onError(result::completeExceptionally).start();
        assertEquals("你好",result.get(5,TimeUnit.SECONDS));assertEquals(2,memories.getMessages("conversation").size());reopen();
        AiServicesJdk8Test.Reactive reactive=AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(fixture.fixture.http.streaming()).chatMemory(memory()).contentRetriever(fixture.retriever()).build();
        ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();reactive.chat("pear?").subscribe(probe);probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));assertNull(probe.error);assertEquals(1,probe.completed.get());
        assertEquals(4,memories.getMessages("conversation").size());assertEquals(3,fixture.store.size());fixture.fixture.http.awaitReleased();
    }
}
