import dev.langchain4j.agent.tool.*;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.*;
import dev.langchain4j.service.tool.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ToolFanoutCancellationJdk8Test {
    OpenAiJdk8Test fixture; ThreadPoolExecutor pool;
    final Map<String, CompletableFuture<ToolExecutionResult>> sources = new ConcurrentHashMap<>();
    final CountDownLatch firstEntered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger interrupts = new AtomicInteger();
    final RuntimeException cleanup = new IllegalStateException("first provider cancellation failed");
    volatile boolean mixed, throwing;
    @Before public void prepare() throws Exception { assertTrue(System.getProperty("java.version").startsWith("1.8.")); fixture = new OpenAiJdk8Test(); fixture.start(); fixture.mode = "toolPair"; pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1); }
    @After public void close() throws Exception { release.countDown(); for (CompletableFuture<ToolExecutionResult> f : sources.values()) f.complete(ToolExecutionResult.builder().resultText("late").build()); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); fixture.stop(); }
    interface Check { boolean ready(); }
    static void await(String message, Check check) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); while (!check.ready() && System.nanoTime() < end) Thread.sleep(2); assertTrue(message, check.ready()); }
    ToolProvider provider() { return request -> ToolProviderResult.builder().add(ToolSpecification.builder().name("weather").build(), new ToolExecutor() {
        public String execute(ToolExecutionRequest r, Object id) { throw new AssertionError("sync fallback"); }
        public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest r, InvocationContext context) {
            CompletableFuture<ToolExecutionResult> result = throwing && r.id().equals("call1") ? new CompletableFuture<ToolExecutionResult>() { @Override public boolean cancel(boolean interrupt) { throw cleanup; } } : new CompletableFuture<>();
            sources.put(r.id(), result);
            if (r.id().equals("call1")) { firstEntered.countDown(); if (mixed) try { release.await(); } catch (InterruptedException e) { interrupts.incrementAndGet(); } }
            return result;
        }
    }).build(); }
    static boolean contains(Throwable error, Throwable target) { if (error == target) return true; for (Throwable child : error.getSuppressed()) if (child == target) return true; return false; }
    void cancel(boolean reactive, boolean runningAndQueued, boolean failCancellation) throws Exception {
        mixed = runningAndQueued; throwing = failCancellation;
        CompletableFuture<String> response = null; ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>(); Runnable cancel;
        if (reactive) { AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(fixture.streaming()).executeToolsConcurrently(pool).toolProvider(provider()).build().chat("pair").subscribe(probe); probe.subscription.request(Long.MAX_VALUE); cancel = probe.subscription::cancel; }
        else { response = AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(fixture.model()).executeToolsConcurrently(pool).toolProvider(provider()).build().chat("pair"); CompletableFuture<String> target = response; cancel = () -> assertTrue(target.cancel(true)); }
        assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
        if (mixed) await("second tool not queued", () -> !pool.getQueue().isEmpty()); else await("second tool not started", () -> sources.size() == 2);
        cancel.run();
        if (mixed) { await("running tool not interrupted", () -> interrupts.get() == 1); pool.submit(() -> {}).get(2, TimeUnit.SECONDS); assertEquals(Collections.singleton("call1"), sources.keySet()); assertTrue(sources.get("call1").isCancelled()); }
        else { await("second provider Future not cancelled", () -> sources.get("call2").isCancelled()); if (!throwing) assertTrue(sources.get("call1").isCancelled()); }
        if (throwing) { assertFalse(sources.get("call1").isDone()); if (!reactive) { try { response.get(); fail(); } catch (CancellationException expected) { assertTrue("provider cleanup failure lost across caller Future wrappers", contains(expected, cleanup)); } } sources.get("call1").complete(ToolExecutionResult.builder().resultText("late").build()); }
        pool.submit(() -> {}).get(2, TimeUnit.SECONDS); fixture.awaitReleased(); assertEquals(1, fixture.requests.get()); assertFalse(pool.isShutdown());
        if (reactive) { assertNull(probe.error); assertEquals(0, probe.completed.get()); assertEquals(1, probe.terminal.getCount()); }
    }
    @Test(timeout=10000) public void futureCancelsBothPendingToolsInSameResponse() throws Exception { cancel(false, false, false); }
    @Test(timeout=10000) public void publisherCancelsBothPendingToolsInSameResponse() throws Exception { cancel(true, false, false); }
    @Test(timeout=10000) public void futureInterruptsRunningToolAndSkipsQueuedSibling() throws Exception { cancel(false, true, false); }
    @Test(timeout=10000) public void publisherInterruptsRunningToolAndSkipsQueuedSibling() throws Exception { cancel(true, true, false); }
    @Test(timeout=10000) public void futureKeepsCleanupFailureAndCancelsOtherProvider() throws Exception { cancel(false, false, true); }
    @Test(timeout=10000) public void publisherCleanupFailureDoesNotPreventOtherProviderCancellation() throws Exception { cancel(true, false, true); }
    final Map<String, CompletableFuture<String>> nativeSources = new ConcurrentHashMap<>();
    final List<String> rollbackOrder = new CopyOnWriteArrayList<>();
    public class CompensatedTools {
        @Tool public CompletableFuture<String> weather(@P("city") String city) { CompletableFuture<String> f = new CompletableFuture<>(); nativeSources.put(city, f); return f; }
        @CompensateFor("weather") public void undo(String city) { rollbackOrder.add(city); }
    }
    void completePair(boolean reactive, boolean compensated) throws Exception {
        ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>(); CompletableFuture<String> result = null; Runnable cancel;
        if (reactive) { AiServices.builder(AiServicesJdk8Test.Reactive.class).streamingChatModel(fixture.streaming()).executeToolsConcurrently(pool).tools(new CompensatedTools()).compensateOnToolErrors(compensated).build().chat("pair").subscribe(probe); probe.subscription.request(Long.MAX_VALUE); cancel = probe.subscription::cancel; }
        else { result = AiServices.builder(AiServicesJdk8Test.Async.class).chatModel(fixture.model()).executeToolsConcurrently(pool).tools(new CompensatedTools()).compensateOnToolErrors(compensated).build().chat("pair"); CompletableFuture<String> target = result; cancel = () -> target.cancel(true); }
        try {
            await("pair not started", () -> nativeSources.size() == 2); if (compensated) { cancel.run(); for (CompletableFuture<?> f : nativeSources.values()) assertFalse(f.isCancelled()); }
            nativeSources.get("Beijing").complete("north"); nativeSources.get("Shanghai").complete("east");
            if (compensated) { await("pair not compensated", () -> rollbackOrder.size() == 2); assertEquals(Arrays.asList("Beijing", "Shanghai"), rollbackOrder); assertEquals(1, fixture.requests.get()); }
            else {
                if (reactive) { assertTrue(probe.terminal.await(3, TimeUnit.SECONDS)); assertNull(probe.error); assertEquals(1, probe.completed.get()); assertEquals("你好", String.join("", probe.values)); } else assertEquals("你好", result.get(3, TimeUnit.SECONDS));
                assertEquals(2, fixture.requests.get()); Map<String,String> sent = new HashMap<>(); for (com.fasterxml.jackson.databind.JsonNode m : fixture.request.get().path("messages")) if (m.has("tool_call_id")) sent.put(m.get("tool_call_id").asText(), m.get("content").asText()); assertEquals("east", sent.get("call1")); assertEquals("north", sent.get("call2")); assertTrue(rollbackOrder.isEmpty());
            }
            pool.submit(() -> {}).get(2, TimeUnit.SECONDS); fixture.awaitReleased(); if (compensated && reactive) { assertNull(probe.error); assertEquals(0, probe.completed.get()); }
        } finally { for (CompletableFuture<String> f : nativeSources.values()) f.complete("cleanup"); }
    }
    @Test(timeout=10000) public void futurePairResultsKeepRequestIdsDespiteCompletionOrder() throws Exception { completePair(false, false); }
    @Test(timeout=10000) public void publisherPairResultsKeepRequestIdsDespiteCompletionOrder() throws Exception { completePair(true, false); }
    @Test(timeout=10000) public void futurePairDrainRollsBackInReverseRequestOrder() throws Exception { completePair(false, true); }
    @Test(timeout=10000) public void publisherPairDrainRollsBackInReverseRequestOrder() throws Exception { completePair(true, true); }
}
