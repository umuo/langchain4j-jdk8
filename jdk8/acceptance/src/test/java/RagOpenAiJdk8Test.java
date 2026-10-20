import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real HTTP/SSE transport with local fixtures, deterministic retrieval and synthetic credentials. */
public class RagOpenAiJdk8Test {
    private final OpenAiJdk8Test http = new OpenAiJdk8Test();
    @Before public void start() throws Exception { http.start(); }
    @After public void stop() { http.stop(); }
    private void assertPrompt(String expected) throws Exception {
        String messages = http.request.get().path("messages").toString();
        assertTrue(messages, messages.contains(expected));
        assertFalse(messages, messages.contains("secret"));
        http.awaitReleased();
    }
    @Test public void synchronousRagReachesOpenAiHttpAndReturnsSources() throws Exception {
        RagJdk8Test.Answer service = AiServices.builder(RagJdk8Test.Answer.class).chatModel(http.model())
                .contentRetriever(RagJdk8Test.retriever(RagJdk8Test.store())).build();
        Result<String> result = service.chat("apple?");
        assertEquals("你好", result.content());
        assertEquals(1, result.sources().size());
        assertPrompt(RagJdk8Test.APPLE);
    }
    @Test public void asynchronousRagReachesOpenAiHttp() throws Exception {
        RagJdk8Test.AsyncAnswer service = AiServices.builder(RagJdk8Test.AsyncAnswer.class).chatModel(http.model())
                .contentRetriever(RagJdk8Test.retriever(RagJdk8Test.store())).build();
        assertEquals("你好", service.chat("pear?").get(5, TimeUnit.SECONDS).content());
        assertPrompt(RagJdk8Test.PEAR);
    }
    @Test public void callbackRagReachesSseAndReportsSources() throws Exception {
        AiServicesJdk8Test.Streaming service = AiServices.builder(AiServicesJdk8Test.Streaming.class)
                .streamingChatModel(http.streaming()).contentRetriever(RagJdk8Test.retriever(RagJdk8Test.store())).build();
        CompletableFuture<String> result = new CompletableFuture<String>();
        java.util.concurrent.atomic.AtomicInteger sources = new java.util.concurrent.atomic.AtomicInteger();
        service.chat("apple?").onPartialResponse(t -> {}).onRetrieved(contents -> sources.set(contents.size()))
                .onCompleteResponse(r -> result.complete(r.aiMessage().text())).onError(result::completeExceptionally).start();
        assertEquals("你好", result.get(5, TimeUnit.SECONDS));
        assertEquals(1, sources.get());
        assertPrompt(RagJdk8Test.APPLE);
    }
    @Test public void reactiveRagReachesSseWithDemandAndSingleCompletion() throws Exception {
        AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class)
                .streamingChatModel(http.streaming()).contentRetriever(RagJdk8Test.retriever(RagJdk8Test.store())).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<String>();
        service.chat("pear?").subscribe(probe);
        assertTrue(probe.values.isEmpty());
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS));
        assertNull(probe.error);
        assertEquals("你好", String.join("", probe.values));
        assertEquals(1, probe.completed.get());
        assertPrompt(RagJdk8Test.PEAR);
    }
}
