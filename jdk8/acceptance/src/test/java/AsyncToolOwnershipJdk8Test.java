import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.invocation.*;
import dev.langchain4j.service.tool.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class AsyncToolOwnershipJdk8Test {
    final OwnedTaskJdk8Test.Owner owner = new OwnedTaskJdk8Test.Owner();
    final ToolService service = new ToolService();
    final ToolExecutionRequest request = ToolExecutionRequest.builder().name("lookup").arguments("{}").build();
    final CompletableFuture<ToolExecutionResult> delegated = new CompletableFuture<>();
    @Before public void runtime() { assertTrue(System.getProperty("java.version").startsWith("1.8.")); }
    CompletableFuture<ToolExecutionResult> start(boolean owned, java.util.function.Consumer<ToolExecution> after) {
        InvocationContext context = owned
                ? CancellationContext.call(owner, () -> CapturedContextSupport.captureInto(InvocationContext.builder()).build())
                : InvocationContext.builder().build();
        ToolExecutor tool = new ToolExecutor() {
            public String execute(ToolExecutionRequest r, Object id) { throw new AssertionError("sync fallback"); }
            public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest r, InvocationContext c) { return delegated; }
        };
        return service.startTool(request, Collections.singletonMap("lookup", tool), context, null, after, Runnable::run);
    }
    @Test public void rootCancelsDelegatedFutureAfterSupplierReturns() {
        AtomicInteger errorHandlers = new AtomicInteger(), after = new AtomicInteger();
        service.executionErrorHandler((error, context) -> { errorHandlers.incrementAndGet(); return ToolErrorHandlerResult.text("retry"); });
        CompletableFuture<ToolExecutionResult> result = start(true, execution -> after.incrementAndGet());
        assertFalse(result.isDone()); owner.cancel();
        assertTrue("delegated async tool remains running", delegated.isCancelled());
        assertTrue("tool waiter remains pending", result.isDone());
        assertTrue("cancellation was converted to a successful tool result", result.isCompletedExceptionally());
        assertEquals(0, errorHandlers.get()); assertEquals(1, after.get()); assertTrue(owner.actions.isEmpty());
    }
    @Test public void delegatedCompletionRestoresOwnerForAfterNotification() throws Exception {
        AtomicReference<CancellationContext.Registrar> seen = new AtomicReference<>();
        CompletableFuture<ToolExecutionResult> result = start(true, execution -> seen.set(CancellationContext.current()));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> { assertNull(CancellationContext.current()); delegated.complete(ToolExecutionResult.builder().resultText("done").build()); assertNull(CancellationContext.current()); }).get(2, TimeUnit.SECONDS);
            assertEquals("done", result.get(2, TimeUnit.SECONDS).resultText()); assertSame(owner, seen.get()); assertTrue(owner.actions.isEmpty());
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)); }
    }
    @Test public void delegatedFailureRestoresOwnerAndPreservesCause() throws Exception {
        RuntimeException primary = new IllegalStateException("delegated failure"); AtomicReference<CancellationContext.Registrar> seen = new AtomicReference<>();
        service.executionErrorHandler((error, context) -> { seen.set(CancellationContext.current()); throw primary; });
        CompletableFuture<ToolExecutionResult> result = start(true, null); delegated.completeExceptionally(primary);
        try { result.get(2, TimeUnit.SECONDS); fail(); } catch (ExecutionException expected) { assertSame(primary, expected.getCause()); }
        assertSame(owner, seen.get()); assertNull(CancellationContext.current()); assertTrue(owner.actions.isEmpty());
    }
    @Test public void unownedToolIsNotCancelledByUnrelatedRoot() throws Exception {
        CompletableFuture<ToolExecutionResult> result = start(false, null); owner.cancel(); assertFalse(delegated.isCancelled());
        delegated.complete(ToolExecutionResult.builder().resultText("unowned").build()); assertEquals("unowned", result.get(2, TimeUnit.SECONDS).resultText());
    }
    @Test public void cancellationDuringProviderReturnCancelsLatePublishedStage() {
        InvocationContext context = CancellationContext.call(owner, () -> CapturedContextSupport.captureInto(InvocationContext.builder()).build());
        ToolExecutor tool = new ToolExecutor() {
            public String execute(ToolExecutionRequest r, Object id) { throw new AssertionError(); }
            public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest r, InvocationContext c) { owner.cancel(); return delegated; }
        };
        CompletableFuture<ToolExecutionResult> result = service.startTool(request, Collections.singletonMap("lookup", tool), context, null, null, Runnable::run);
        assertTrue(delegated.isCancelled()); assertTrue(result.isCompletedExceptionally()); assertTrue(owner.actions.isEmpty());
    }
    @Test public void alreadyCompletedStageDoesNotRetainOwner() throws Exception {
        delegated.complete(ToolExecutionResult.builder().resultText("early").build());
        assertEquals("early", start(true, null).get(2, TimeUnit.SECONDS).resultText()); assertTrue(owner.actions.isEmpty()); owner.cancel(); assertFalse(delegated.isCancelled());
    }
    @Test(timeout=10000) public void httpAsyncToolCancellationDoesNotRequestNextModelRound() throws Exception {
        httpToolLoop(true);
    }
    @Test(timeout=10000) public void httpAsyncToolSuccessContinuesWithToolResult() throws Exception {
        httpToolLoop(false);
    }
    private void httpToolLoop(boolean cancel) throws Exception {
        OpenAiJdk8Test fixture = new OpenAiJdk8Test(); fixture.start(); fixture.mode = "toolOnce";
        CountDownLatch entered = new CountDownLatch(1); AtomicInteger toolCalls = new AtomicInteger(), handlers = new AtomicInteger();
        try {
            ToolExecutor tool = new ToolExecutor() {
                public String execute(ToolExecutionRequest r, Object id) { throw new AssertionError("sync fallback"); }
                public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest r, InvocationContext c) { toolCalls.incrementAndGet(); entered.countDown(); return delegated; }
            };
            AiServicesJdk8Test.Async service = dev.langchain4j.service.AiServices.builder(AiServicesJdk8Test.Async.class)
                    .chatModel(fixture.model()).toolProvider(r -> ToolProviderResult.builder().add(dev.langchain4j.agent.tool.ToolSpecification.builder().name("weather").build(), tool).build())
                    .toolExecutionErrorHandler((error, context) -> { handlers.incrementAndGet(); return ToolErrorHandlerResult.text("retry"); }).build();
            CompletableFuture<String> response = CancellationContext.call(owner, () -> service.chat("weather"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            if (cancel) {
                owner.cancel();
                try { response.get(3, TimeUnit.SECONDS); fail("cancelled tool returned success"); } catch (CancellationException expected) {} catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
                assertTrue(delegated.isCancelled()); assertEquals(1, fixture.requests.get()); assertEquals(0, handlers.get());
            } else {
                delegated.complete(ToolExecutionResult.builder().resultText("sunny").build());
                assertEquals("你好", response.get(3, TimeUnit.SECONDS)); assertEquals(2, fixture.requests.get());
                assertEquals("call1", fixture.request.get().path("messages").get(2).path("tool_call_id").asText());
                assertEquals("sunny", fixture.request.get().path("messages").get(2).path("content").asText());
            }
            fixture.awaitReleased(); assertEquals(1, toolCalls.get());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!owner.actions.isEmpty() && System.nanoTime() < deadline) Thread.yield();
            assertTrue("tool or transport owner retained", owner.actions.isEmpty());
        } finally { delegated.cancel(true); fixture.stop(); }
    }
}
