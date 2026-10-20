import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Jdk8;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.*;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.Test;
import org.reactivestreams.Publisher;
import static org.junit.Assert.*;

public class AiServicesJdk8Test {

    public interface Basic {

        String chat(String input);

        default int answer() {
            return 42;
        }

        default String decorated(String value) {
            return chat(value) + "!";
        }

        default String fail() throws IOException {
            throw new IOException("default failure");
        }
    }

    interface HiddenDefault {

        String chat(String input);

        default String local() {
            return "hidden";
        }
    }

    public interface Prompts {

        @dev.langchain4j.service.SystemMessage("Be brief")
        @dev.langchain4j.service.UserMessage("Hello {{name}}")
        String welcome(@V("name") String name);
    }

    public static class Answer {

        public String name;

        public int age;

        public Answer() {
        }
    }

    public interface Structured {

        Answer extract(String input);
    }

    public interface Async {

        CompletableFuture<String> chat(String input);
    }

    public interface AsyncStructured {

        CompletableFuture<Answer> extract(String input);
    }

    public interface Sessions extends ChatMemoryAccess {

        String chat(@MemoryId String session, @dev.langchain4j.service.UserMessage String input);
    }

    public interface Streaming {

        TokenStream chat(String input);
    }

    public interface Reactive {

        Publisher<String> chat(String input);
    }

    public static class Tools {

        final AtomicInteger invocations = new AtomicInteger();

        @Tool("Look up weather")
        public String weather(@P("city") String city) {
            invocations.incrementAndGet();
            return city + ": sunny";
        }
    }

    public static class FailingTools {

        @Tool("fail")
        public String fail() {
            throw new IllegalStateException("tool failure");
        }
    }

    static ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    static ChatModel model(Function<ChatRequest, AiMessage> fn) {
        return new ChatModel() {

            public ChatResponse doChat(ChatRequest r) {
                return response(fn.apply(r));
            }

            public CompletableFuture<ChatResponse> doChatAsync(ChatRequest r) {
                try {
                    return CompletableFuture.completedFuture(doChat(r));
                } catch (Throwable error) {
                    return Jdk8.failedFuture(error);
                }
            }
        };
    }

    static ToolExecutionRequest weatherCall() {
        return ToolExecutionRequest.builder().id("call-1").name("weather").arguments("{\"city\":\"Shanghai\"}").build();
    }

    @Test
    public void defaultsAreInvokedLocallyAndCanCallTheProxy() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Basic service = AiServices.create(Basic.class, model(r -> {
            calls.incrementAndGet();
            return AiMessage.from("reply");
        }));
        assertEquals(42, service.answer());
        assertEquals(0, calls.get());
        assertEquals("reply!", service.decorated("hello"));
        assertEquals(1, calls.get());
        IOException error = assertThrows(IOException.class, service::fail);
        assertEquals("default failure", error.getMessage());
        assertTrue(service.equals(service));
        assertFalse(service.equals(new Object()));
        assertNotNull(service.toString());
        assertEquals(1, calls.get());
        HiddenDefault hidden = AiServices.create(HiddenDefault.class, model(r -> AiMessage.from("unused")));
        assertEquals("hidden", hidden.local());
    }

    @Test
    public void annotationsAndMetadataRenderPrompts() {
        AtomicReference<ChatRequest> request = new AtomicReference<ChatRequest>();
        Prompts service = AiServices.create(Prompts.class, model(r -> {
            request.set(r);
            return AiMessage.from("ok");
        }));
        assertEquals("ok", service.welcome("Alice"));
        assertEquals("Be brief", ((dev.langchain4j.data.message.SystemMessage) request.get().messages().get(0)).text());
        assertEquals("Hello Alice", ((UserMessage) request.get().messages().get(1)).singleText());
    }

    @Test
    public void structuredPojoOutputsAndMalformedJson() {
        Structured service = AiServices.create(Structured.class, model(r -> AiMessage.from("{\"name\":\"Alice\",\"age\":30}")));
        Answer answer = service.extract("person");
        assertEquals("Alice", answer.name);
        assertEquals(30, answer.age);
        Structured bad = AiServices.create(Structured.class, model(r -> AiMessage.from("not json")));
        assertThrows(RuntimeException.class, () -> bad.extract("bad"));
    }

    @Test
    public void futuresParseResultsAndPreserveFailures() throws Exception {
        Async service = AiServices.create(Async.class, model(r -> AiMessage.from("async")));
        assertEquals("async", service.chat("hello").get(5, TimeUnit.SECONDS));
        AsyncStructured structured = AiServices.create(AsyncStructured.class, model(r -> AiMessage.from("{\"name\":\"Bob\",\"age\":25}")));
        assertEquals("Bob", structured.extract("person").get(5, TimeUnit.SECONDS).name);
        Async failing = AiServices.create(Async.class, model(r -> {
            throw new IllegalStateException("model failure");
        }));
        ExecutionException error = assertThrows(ExecutionException.class, () -> failing.chat("fail").get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalStateException);
    }

    @Test
    public void memorySessionsAreIsolatedAndCanBeEvicted() {
        Map<String, ChatMemory> memories = new ConcurrentHashMap<String, ChatMemory>();
        List<ChatRequest> requests = new ArrayList<ChatRequest>();
        Sessions service = AiServices.builder(Sessions.class).chatModel(model(r -> {
            requests.add(r);
            return AiMessage.from("reply");
        })).chatMemoryProvider(id -> {
            ChatMemory memory = MessageWindowChatMemory.builder().id(id).maxMessages(10).build();
            memories.put((String) id, memory);
            return memory;
        }).build();
        service.chat("a", "first");
        service.chat("a", "second");
        service.chat("b", "isolated");
        assertEquals(3, requests.get(1).messages().size());
        assertEquals(1, requests.get(2).messages().size());
        assertEquals(4, memories.get("a").messages().size());
        assertNotNull(service.getChatMemory("a"));
        assertTrue(service.evictChatMemory("a"));
        assertNull(service.getChatMemory("a"));
        assertFalse(service.evictChatMemory("a"));
    }

    @Test
    public void automaticToolCallingCompletesAndStoresTheConversation() {
        Tools tools = new Tools();
        List<ChatRequest> requests = new ArrayList<ChatRequest>();
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);
        ChatModel chat = model(r -> {
            requests.add(r);
            ChatMessage last = r.messages().get(r.messages().size() - 1);
            return last instanceof ToolExecutionResultMessage ? AiMessage.from(((ToolExecutionResultMessage) last).text()) : AiMessage.from(weatherCall());
        });
        Basic service = AiServices.builder(Basic.class).chatModel(chat).chatMemory(memory).tools(tools).build();
        assertEquals("Shanghai: sunny", service.chat("weather?"));
        assertEquals(1, tools.invocations.get());
        assertEquals(2, requests.size());
        assertEquals(4, memory.messages().size());
        assertEquals("weather", requests.get(0).parameters().toolSpecifications().get(0).name());
    }

    @Test
    public void asynchronousToolCallingCompletes() throws Exception {
        Tools tools = new Tools();
        Async service = AiServices.builder(Async.class).chatModel(model(r -> r.messages().get(r.messages().size() - 1) instanceof ToolExecutionResultMessage ? AiMessage.from("done") : AiMessage.from(weatherCall()))).tools(tools).build();
        assertEquals("done", service.chat("weather").get(5, TimeUnit.SECONDS));
        assertEquals(1, tools.invocations.get());
    }

    @Test
    public void toolFailuresAndRoundTripLimitsAreReported() {
        Basic fail = AiServices.builder(Basic.class).chatModel(model(r -> AiMessage.from(ToolExecutionRequest.builder().id("failure").name("fail").arguments("{}").build()))).tools(new FailingTools()).build();
        assertThrows(RuntimeException.class, () -> fail.chat("fail"));
        Tools tools = new Tools();
        Basic loop = AiServices.builder(Basic.class).chatModel(model(r -> AiMessage.from(weatherCall()))).tools(tools).maxToolCallingRoundTrips(1).build();
        RuntimeException limit = assertThrows(RuntimeException.class, () -> loop.chat("loop"));
        assertTrue(limit.getMessage().contains("round trips"));
        assertEquals(1, tools.invocations.get());
    }

    static StreamingChatModel streamingModel() {
        return new StreamingChatModel() {

            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("hello");
                handler.onCompleteResponse(response(AiMessage.from("hello")));
            }

            public Publisher<ChatModelStreamingEvent> doChat(ChatRequest request) {
                return dev.langchain4j.internal.reactive.ZeroPublisher.create(new dev.langchain4j.internal.reactive.TubeConfiguration(), tube -> {
                    tube.send(new PartialResponse("hello"));
                    tube.send(new CompleteResponse(response(AiMessage.from("hello"))));
                    tube.complete();
                });
            }
        };
    }

    @Test
    public void tokenStreamsAndReactiveProxiesWork() throws Exception {
        Streaming callback = AiServices.builder(Streaming.class).streamingChatModel(streamingModel()).build();
        CompletableFuture<String> result = new CompletableFuture<String>();
        StringBuffer text = new StringBuffer();
        callback.chat("hi").onPartialResponse(text::append).onCompleteResponse(r -> result.complete(r.aiMessage().text())).onError(result::completeExceptionally).start();
        assertEquals("hello", result.get(5, TimeUnit.SECONDS));
        assertEquals("hello", text.toString());
        Reactive reactive = AiServices.builder(Reactive.class).streamingChatModel(streamingModel()).build();
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<String>();
        reactive.chat("hi").subscribe(probe);
        assertTrue(probe.values.isEmpty());
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS));
        assertNull(probe.error);
        assertEquals(Arrays.asList("hello"), probe.values);
        assertEquals(1, probe.completed.get());
    }

    @Test
    public void bundledSentenceModelWorksWithJava8Dependency() {
        List<dev.langchain4j.data.segment.TextSegment> segments = new DocumentBySentenceSplitter(30, 0).split(Document.from("This is a first sentence. This is a second sentence."));
        assertEquals(2, segments.size());
        assertEquals("This is a first sentence.", segments.get(0).text());
    }

    public interface ParentDefaults {

        default String inherited(String value) {
            return value + " inherited";
        }
    }

    public interface ChildDefaults extends Basic, ParentDefaults {
    }

    public interface AsyncSessions extends ChatMemoryAccess {

        CompletableFuture<String> chat(@MemoryId String session, @dev.langchain4j.service.UserMessage String input);
    }

    @Test
    public void inheritedDefaultMethodsPreserveArguments() {
        ChildDefaults service = AiServices.create(ChildDefaults.class, model(r -> {
            throw new AssertionError("No model call expected");
        }));
        assertEquals("value inherited", service.inherited("value"));
        assertEquals(42, service.answer());
    }

    @Test
    public void asynchronousSessionMemoryPreservesIsolation() throws Exception {
        List<ChatRequest> requests = new ArrayList<ChatRequest>();
        AsyncSessions service = AiServices.builder(AsyncSessions.class).chatModel(model(r -> {
            requests.add(r);
            return AiMessage.from("ok");
        })).chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(10).build()).build();
        service.chat("a", "first").get(5, TimeUnit.SECONDS);
        service.chat("a", "second").get(5, TimeUnit.SECONDS);
        service.chat("b", "isolated").get(5, TimeUnit.SECONDS);
        assertEquals(3, requests.get(1).messages().size());
        assertEquals(1, requests.get(2).messages().size());
        assertEquals(4, service.getChatMemory("a").messages().size());
    }

    @Test
    public void concurrentToolsReturnAllResults() throws Exception {
        Tools tools = new Tools();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ToolExecutionRequest second = ToolExecutionRequest.builder().id("call-2").name("weather").arguments("{\"city\":\"Beijing\"}").build();
            Basic service = AiServices.builder(Basic.class).chatModel(model(r -> r.messages().get(r.messages().size() - 1) instanceof ToolExecutionResultMessage ? AiMessage.from("done") : AiMessage.from(weatherCall(), second))).tools(tools).executeToolsConcurrently(executor).build();
            assertEquals("done", service.chat("both"));
            assertEquals(2, tools.invocations.get());
        } finally {
            executor.shutdownNow();
        }
    }

    public static class CustomMetadataFactory implements dev.langchain4j.spi.classloading.ClassMetadataProviderFactory<java.lang.reflect.Method> {

        private final dev.langchain4j.classloading.ReflectionBasedClassMetadataProviderFactory delegate = new dev.langchain4j.classloading.ReflectionBasedClassMetadataProviderFactory();

        public <T extends java.lang.annotation.Annotation> Optional<T> getAnnotation(java.lang.reflect.Method method, Class<T> annotation) {
            return delegate.getAnnotation(method, annotation);
        }

        public <T extends java.lang.annotation.Annotation> Optional<T> getAnnotation(Class<?> type, Class<T> annotation) {
            return delegate.getAnnotation(type, annotation);
        }

        public Iterable<java.lang.reflect.Method> getNonStaticMethodsOnClass(Class<?> type) {
            return delegate.getNonStaticMethodsOnClass(type);
        }
    }

    @Test
    public void metadataSpiSelectsCustomProvider() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory(java.nio.file.Paths.get("target"), "metadata-spi-");
        java.nio.file.Path resource = directory.resolve("META-INF/services/dev.langchain4j.spi.classloading.ClassMetadataProviderFactory");
        java.nio.file.Files.createDirectories(resource.getParent());
        java.nio.file.Files.write(resource, CustomMetadataFactory.class.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] { directory.toUri().toURL() }, original)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertTrue(dev.langchain4j.classloading.ClassMetadataProvider.<java.lang.reflect.Method>getClassMetadataProviderFactory() instanceof CustomMetadataFactory);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            java.nio.file.Files.deleteIfExists(resource);
            java.nio.file.Files.deleteIfExists(resource.getParent());
            java.nio.file.Files.deleteIfExists(resource.getParent().getParent());
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    public interface VarargDefaults extends Basic {

        default String joined(String... values) {
            return values == null ? "null" : String.join(",", values);
        }
    }

    @Test
    public void defaultVarargsPreserveArrayAndNullArguments() {
        VarargDefaults service = AiServices.create(VarargDefaults.class, model(r -> {
            throw new AssertionError("No model call expected");
        }));
        assertEquals("a,b", service.joined("a", "b"));
        assertEquals("", service.joined());
        assertEquals("null", service.joined((String[]) null));
    }
}
