import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.AiServices;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.reactivestreams.*;
import static org.junit.Assert.*;

/**
 * Full Java 8 proxy -> model -> HTTP/SSE -> automatic tool -> model loop, without external services.
 */
public class AiServicesOpenAiJdk8Test {

    private OpenAiJdk8Test fixture;

    @Before
    public void start() throws Exception {
        fixture = new OpenAiJdk8Test();
        fixture.start();
    }

    @After
    public void stop() {
        fixture.stop();
    }

    @Test
    public void proxyAndSessionMemoryReachHttp() throws Exception {
        AiServicesJdk8Test.Sessions service = AiServices.builder(AiServicesJdk8Test.Sessions.class).chatModel(fixture.model()).chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(10).build()).build();
        assertEquals("你好", service.chat("a", "first"));
        assertEquals("你好", service.chat("a", "second"));
        assertEquals(3, fixture.request.get().path("messages").size());
        assertEquals("Bearer synthetic", fixture.auth.get());
        assertEquals("你好", service.chat("b", "other"));
        assertEquals(1, fixture.request.get().path("messages").size());
        fixture.awaitReleased();
    }

    @Test
    public void httpPojoAndFutureOutputsAreParsed() throws Exception {
        fixture.mode = "structured";
        AiServicesJdk8Test.Structured structured = AiServices.create(AiServicesJdk8Test.Structured.class, fixture.model());
        assertEquals("Alice", structured.extract("person").name);
        AiServicesJdk8Test.AsyncStructured async = AiServices.create(AiServicesJdk8Test.AsyncStructured.class, fixture.model());
        assertEquals(30, async.extract("person").get(5, TimeUnit.SECONDS).age);
        fixture.awaitReleased();
    }

    @Test
    public void synchronousHttpToolsExecuteAndReturnToModel() throws Exception {
        fixture.mode = "toolOnce";
        AiServicesJdk8Test.Tools tools = new AiServicesJdk8Test.Tools();
        AiServicesJdk8Test.Basic service = AiServices.builder(AiServicesJdk8Test.Basic.class).chatModel(fixture.model()).tools(tools).build();
        assertEquals("你好", service.chat("weather"));
        assertEquals(1, tools.invocations.get());
        assertEquals(2, fixture.requests.get());
        assertEquals("call1", fixture.request.get().path("messages").get(2).path("tool_call_id").asText());
        fixture.awaitReleased();
    }

    @Test
    public void asynchronousHttpToolsExecuteAndReturnToModel() throws Exception {
        fixture.mode = "toolOnce";
        AiServicesJdk8Test.Tools tools = new AiServicesJdk8Test.Tools();
        AiServicesJdk8Test.Async service = AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(fixture.model()).chatMemory(MessageWindowChatMemory.withMaxMessages(10)).tools(tools).build();
        assertEquals("你好", service.chat("weather").get(5, TimeUnit.SECONDS));
        assertEquals(1, tools.invocations.get());
        assertEquals(2, fixture.requests.get());
        fixture.awaitReleased();
    }

    @Test
    public void callbackSseToolsExecuteAndContinueStreaming() throws Exception {
        fixture.mode = "toolOnce";
        AiServicesJdk8Test.Tools tools = new AiServicesJdk8Test.Tools();
        AiServicesJdk8Test.Streaming service = AiServices.builder(AiServicesJdk8Test.Streaming.class).streamingChatModel(fixture.streaming()).tools(tools).build();
        CompletableFuture<ChatResponse> complete = new CompletableFuture<ChatResponse>();
        StringBuffer text = new StringBuffer();
        service.chat("weather").onPartialResponse(text::append).onCompleteResponse(complete::complete).onError(complete::completeExceptionally).start();
        assertEquals("你好", complete.get(5, TimeUnit.SECONDS).aiMessage().text());
        assertEquals("你好", text.toString());
        assertEquals(1, tools.invocations.get());
        assertEquals(2, fixture.requests.get());
        fixture.awaitReleased();
    }

    @Test
    public void reactiveSseToolsExecuteAndContinueStreaming() throws Exception {
        fixture.mode = "toolOnce";
        AiServicesJdk8Test.Tools tools = new AiServicesJdk8Test.Tools();
        AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(fixture.streaming()).tools(tools).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<String>();
        service.chat("weather").subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS));
        assertNull(probe.error);
        assertEquals("你好", String.join("", probe.values));
        assertEquals(1, probe.completed.get());
        assertEquals(1, tools.invocations.get());
        assertEquals(2, fixture.requests.get());
        fixture.awaitReleased();
    }

    @Test
    public void cancellingProxyPublisherReleasesTheHttpCall() throws Exception {
        fixture.mode = "long";
        AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(fixture.streaming()).build();
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger terminalSignals = new AtomicInteger();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<String>() {

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
                terminalSignals.incrementAndGet();
            }

            public void onComplete() {
                terminalSignals.incrementAndGet();
            }
        };
        service.chat("cancel").subscribe(probe);
        assertTrue(first.await(5, TimeUnit.SECONDS));
        fixture.awaitReleased();
        assertEquals(1, probe.values.size());
        assertEquals(0, terminalSignals.get());
    }

    @Test
    public void cancellingProxyFutureAbortsTheHttpCall() throws Exception {
        fixture.mode = "slow";
        AiServicesJdk8Test.Async service = AiServices.create(AiServicesJdk8Test.Async.class, fixture.model());
        CompletableFuture<String> future = service.chat("cancel");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.request.get() == null && System.nanoTime() < end) Thread.sleep(5);
        assertNotNull(fixture.request.get());
        assertTrue(future.cancel(true));
        assertThrows(CancellationException.class, future::join);
        fixture.awaitReleased();
    }

    @Test
    public void httpFailureCompletesProxyFutureExceptionally() throws Exception {
        fixture.mode = "error";
        AiServicesJdk8Test.Async service = AiServices.create(AiServicesJdk8Test.Async.class, fixture.model());
        assertThrows(ExecutionException.class, () -> service.chat("fail").get(5, TimeUnit.SECONDS));
        assertEquals(1, fixture.requests.get());
        fixture.awaitReleased();
    }
}
