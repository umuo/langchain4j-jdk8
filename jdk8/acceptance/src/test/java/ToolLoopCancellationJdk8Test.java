import dev.langchain4j.agent.tool.*;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.*;
import dev.langchain4j.service.tool.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ToolLoopCancellationJdk8Test {
    OpenAiJdk8Test fixture;
    ThreadPoolExecutor tools;
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), rollback = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger(), interrupts = new AtomicInteger(), rolledBack = new AtomicInteger();
    final CompletableFuture<ToolExecutionResult> delegated = new CompletableFuture<>();
    final CompletableFuture<String> annotated = new CompletableFuture<>();
    volatile boolean blocking;
    @Before public void start() throws Exception { assertTrue(System.getProperty("java.version").startsWith("1.8.")); fixture = new OpenAiJdk8Test(); fixture.start(); fixture.mode = "toolOnce"; tools = (ThreadPoolExecutor) Executors.newFixedThreadPool(1); }
    @After public void stop() throws Exception { release.countDown(); delegated.complete(ToolExecutionResult.builder().resultText("cleanup").build()); annotated.complete("cleanup"); tools.shutdownNow(); assertTrue(tools.awaitTermination(2, TimeUnit.SECONDS)); fixture.stop(); }
    void action() { calls.incrementAndGet(); entered.countDown(); if (blocking) try { release.await(); } catch (InterruptedException e) { interrupts.incrementAndGet(); } }
    public class FutureTool {
        @Tool public CompletableFuture<String> weather(@P("city") String city) { action(); return annotated; }
        @CompensateFor("weather") public void undo(String city) { rolledBack.incrementAndGet(); rollback.countDown(); }
    }
    <T> T service(Class<T> type, boolean nativeTool, boolean compensation) {
        AiServices<T> builder = AiServices.builder(type).executeToolsConcurrently(tools).compensateOnToolErrors(compensation);
        if (type == AiServicesJdk8Test.Async.class) builder.chatModel(fixture.model()); else builder.streamingChatModel(fixture.streaming());
        if (nativeTool) builder.tools(new FutureTool());
        else builder.toolProvider(r -> ToolProviderResult.builder().add(ToolSpecification.builder().name("weather").build(), new ToolExecutor() {
            public String execute(ToolExecutionRequest request, Object id) { throw new AssertionError("sync fallback"); }
            public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request, InvocationContext context) { action(); return delegated; }
        }).build());
        return builder.build();
    }
    interface Check { boolean ready(); }
    static void await(String message, Check check) throws Exception { long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); while (!check.ready() && System.nanoTime() < deadline) Thread.sleep(2); assertTrue(message, check.ready()); }
    Runnable invocation(boolean reactive, boolean nativeTool, boolean compensation, ReactiveJdk8Test.Probe<String> probe) {
        if (reactive) { service(AiServicesJdk8Test.Reactive.class, nativeTool, compensation).chat("weather").subscribe(probe); probe.subscription.request(Long.MAX_VALUE); return probe.subscription::cancel; }
        CompletableFuture<String> response = service(AiServicesJdk8Test.Async.class, nativeTool, compensation).chat("weather");
        return () -> { assertTrue(response.cancel(true)); assertTrue(response.isCancelled()); };
    }
    void cancelLoop(boolean reactive, boolean nativeTool, boolean queued, boolean running) throws Exception {
        blocking = running; CountDownLatch occupied = new CountDownLatch(1); AtomicInteger unrelatedInterrupts = new AtomicInteger();
        if (queued) { tools.submit(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException e) { unrelatedInterrupts.incrementAndGet(); } }); assertTrue(occupied.await(2, TimeUnit.SECONDS)); }
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>(); Runnable cancel = invocation(reactive, nativeTool, false, probe);
        if (queued) await("tool was not queued", () -> !tools.getQueue().isEmpty()); else assertTrue(entered.await(3, TimeUnit.SECONDS));
        cancel.run();
        if (running) await("running tool was not interrupted", () -> interrupts.get() == 1);
        fixture.awaitReleased();
        if (!queued) await("provider Future still running", () -> nativeTool ? annotated.isCancelled() : delegated.isCancelled());
        release.countDown(); tools.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertEquals(queued ? 0 : 1, calls.get()); assertEquals(0, unrelatedInterrupts.get()); assertFalse(tools.isShutdown()); assertEquals(1, fixture.requests.get());
        if (reactive) { assertEquals(0, probe.completed.get()); assertNull(probe.error); assertEquals(1, probe.terminal.getCount()); assertTrue(probe.values.isEmpty()); }
    }
    @Test(timeout=10000) public void futureCancelsPendingProgrammaticTool() throws Exception { cancelLoop(false, false, false, false); }
    @Test(timeout=10000) public void publisherCancelsPendingProgrammaticTool() throws Exception { cancelLoop(true, false, false, false); }
    @Test(timeout=10000) public void futureCancelsAnnotatedFutureTool() throws Exception { cancelLoop(false, true, false, false); }
    @Test(timeout=10000) public void publisherCancelsAnnotatedFutureTool() throws Exception { cancelLoop(true, true, false, false); }
    @Test(timeout=10000) public void futureCancelsQueuedTool() throws Exception { cancelLoop(false, false, true, false); }
    @Test(timeout=10000) public void publisherCancelsQueuedTool() throws Exception { cancelLoop(true, false, true, false); }
    @Test(timeout=10000) public void futureInterruptsRunningTool() throws Exception { cancelLoop(false, false, false, true); }
    @Test(timeout=10000) public void publisherInterruptsRunningTool() throws Exception { cancelLoop(true, false, false, true); }
    void compensation(boolean reactive) throws Exception {
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>(); Runnable cancel = invocation(reactive, true, true, probe); assertTrue(entered.await(3, TimeUnit.SECONDS)); cancel.run();
        assertFalse("compensation lost eventual result", annotated.isCancelled()); assertEquals(0, rolledBack.get()); annotated.complete("success after caller cancellation");
        assertTrue("successful tool was not rolled back", rollback.await(3, TimeUnit.SECONDS)); tools.submit(() -> {}).get(2, TimeUnit.SECONDS); fixture.awaitReleased(); assertEquals(1, rolledBack.get()); assertEquals(1, fixture.requests.get());
        if (reactive) { assertNull(probe.error); assertEquals(0, probe.completed.get()); }
    }
    @Test(timeout=10000) public void futureCancellationKeepsCompensationDrainContract() throws Exception { compensation(false); }
    @Test(timeout=10000) public void publisherCancellationKeepsCompensationDrainContract() throws Exception { compensation(true); }
    void isolation(boolean reactive) throws Exception {
        fixture.mode = "tool"; List<CompletableFuture<ToolExecutionResult>> sources = new CopyOnWriteArrayList<>();
        ToolProvider provider = r -> ToolProviderResult.builder().add(ToolSpecification.builder().name("weather").build(), new ToolExecutor() {
            public String execute(ToolExecutionRequest request, Object id) { throw new AssertionError(); }
            public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request, InvocationContext context) { CompletableFuture<ToolExecutionResult> f = new CompletableFuture<>(); sources.add(f); return f; }
        }).build();
        Runnable first, second;
        if (reactive) {
            AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(fixture.streaming()).executeToolsConcurrently(tools).toolProvider(provider).build();
            ReactiveJdk8Test.Probe<String> a = new ReactiveJdk8Test.Probe<>(), b = new ReactiveJdk8Test.Probe<>(); service.chat("first").subscribe(a); a.subscription.request(Long.MAX_VALUE); await("first tool missing", () -> sources.size() == 1); first = a.subscription::cancel;
            service.chat("second").subscribe(b); b.subscription.request(Long.MAX_VALUE); await("second tool missing", () -> sources.size() == 2); second = b.subscription::cancel;
        } else {
            AiServicesJdk8Test.Async service = AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(fixture.model()).executeToolsConcurrently(tools).toolProvider(provider).build();
            CompletableFuture<String> a = service.chat("first"); await("first tool missing", () -> sources.size() == 1); first = () -> a.cancel(true);
            CompletableFuture<String> b = service.chat("second"); await("second tool missing", () -> sources.size() == 2); second = () -> b.cancel(true);
        }
        try { first.run(); await("first tool not cancelled", () -> sources.get(0).isCancelled()); assertFalse("other invocation cancelled", sources.get(1).isDone()); second.run(); await("second tool not cancelled", () -> sources.get(1).isCancelled()); fixture.awaitReleased(); assertEquals(2, fixture.requests.get()); }
        finally { for (CompletableFuture<?> f : sources) f.cancel(true); }
    }
    @Test(timeout=10000) public void futureCancellationDoesNotCancelAnotherInvocation() throws Exception { isolation(false); }
    @Test(timeout=10000) public void publisherCancellationDoesNotCancelAnotherSubscription() throws Exception { isolation(true); }
    void independentCancellation(boolean reactive) throws Exception {
        AtomicInteger policies = new AtomicInteger();
        if (reactive) {
            AiServicesJdk8Test.Reactive service = AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(fixture.streaming()).tools(new FutureTool()).toolExecutionErrorHandler((error, context) -> { policies.incrementAndGet(); return ToolErrorHandlerResult.text("retry"); }).build();
            ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>(); service.chat("weather").subscribe(probe); probe.subscription.request(Long.MAX_VALUE); assertTrue(entered.await(3, TimeUnit.SECONDS)); annotated.cancel(true);
            assertTrue(probe.terminal.await(3, TimeUnit.SECONDS)); assertTrue("provider cancellation not delivered as error", probe.error instanceof CancellationException); assertEquals(0, probe.completed.get());
        } else {
            AiServicesJdk8Test.Async service = AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(fixture.model()).tools(new FutureTool()).toolExecutionErrorHandler((error, context) -> { policies.incrementAndGet(); return ToolErrorHandlerResult.text("retry"); }).build();
            CompletableFuture<String> response = service.chat("weather"); assertTrue(entered.await(3, TimeUnit.SECONDS)); annotated.cancel(true);
            try { response.get(3, TimeUnit.SECONDS); fail("provider cancellation became successful text"); } catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
            assertFalse("independent provider cancellation marked caller cancelled", response.isCancelled());
        }
        assertEquals(0, policies.get()); fixture.awaitReleased(); assertEquals(1, fixture.requests.get());
    }
    @Test(timeout=10000) public void futureReportsIndependentAnnotatedCancellationWithoutRetry() throws Exception { independentCancellation(false); }
    @Test(timeout=10000) public void publisherReportsIndependentAnnotatedCancellationWithoutRetry() throws Exception { independentCancellation(true); }
    public static class RawFutureTool {
        final CompletableFuture<String> source;
        RawFutureTool(CompletableFuture<String> source) { this.source = source; }
        @Tool public CompletableFuture<String> weather(@P("city") String city) { return source; }
    }
    CompletableFuture<ToolExecutionResult> rawResult(CompletableFuture<String> source) throws Exception {
        return new DefaultToolExecutor(new RawFutureTool(source), RawFutureTool.class.getMethod("weather", String.class)).executeAsync(ToolExecutionRequest.builder().name("weather").arguments("{\"city\":\"Shanghai\"}").build(), InvocationContext.builder().build());
    }
    @Test public void annotatedFutureConversionPreservesCancellationMode() throws Exception {
        List<Boolean> flags = new ArrayList<>(); CompletableFuture<String> source = new CompletableFuture<String>() { @Override public boolean cancel(boolean interrupt) { flags.add(interrupt); return super.cancel(interrupt); } };
        CompletableFuture<ToolExecutionResult> result = rawResult(source); assertTrue(result.cancel(false)); assertTrue(result.cancel(true)); assertEquals(Arrays.asList(false, true), flags); assertTrue(result.isCancelled());
    }
    @Test public void annotatedFutureCancellationFailureKeepsOutwardResultCancelled() throws Exception {
        RuntimeException primary = new IllegalStateException("annotation provider cancel"); CompletableFuture<String> source = new CompletableFuture<String>() { @Override public boolean cancel(boolean interrupt) { throw primary; } };
        CompletableFuture<ToolExecutionResult> result = rawResult(source); try { result.cancel(true); fail(); } catch (RuntimeException expected) { assertSame(primary, expected); }
        assertTrue(result.isCancelled()); assertFalse(source.isDone()); source.complete("late"); assertTrue(result.isCancelled());
    }
}
