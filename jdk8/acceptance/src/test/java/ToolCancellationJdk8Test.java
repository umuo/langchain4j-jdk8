import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.invocation.*;
import dev.langchain4j.service.tool.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ToolCancellationJdk8Test {
    final OwnedTaskJdk8Test.Owner owner = new OwnedTaskJdk8Test.Owner();
    final ToolService service = new ToolService();
    final ToolExecutionRequest request = ToolExecutionRequest.builder().name("lookup").arguments("{}").build();
    ExecutorService pool;
    @Before public void prepare() { assertTrue(System.getProperty("java.version").startsWith("1.8.")); pool = Executors.newSingleThreadExecutor(); }
    @After public void close() throws Exception { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); }
    InvocationContext context(boolean owned) { return owned ? CancellationContext.call(owner, () -> CapturedContextSupport.captureInto(InvocationContext.builder()).build()) : InvocationContext.builder().build(); }
    CompletableFuture<ToolExecutionResult> start(boolean owned, java.util.concurrent.Executor executor, java.util.function.Supplier<CompletableFuture<ToolExecutionResult>> action) {
        ToolExecutor tool = new ToolExecutor() {
            public String execute(ToolExecutionRequest r, Object id) { throw new AssertionError("sync fallback"); }
            public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest r, InvocationContext c) { return action.get(); }
        };
        return service.startTool(request, Collections.singletonMap("lookup", tool), context(owned), null, null, executor);
    }
    @Test public void synchronouslyThrownCancellationBypassesErrorTextPolicy() throws Exception {
        AtomicInteger errors = new AtomicInteger(); service.executionErrorHandler((error, c) -> { errors.incrementAndGet(); return ToolErrorHandlerResult.text("retry"); });
        for (boolean owned : new boolean[]{false, true}) {
            CancellationException primary = new CancellationException("provider cancelled");
            CompletableFuture<ToolExecutionResult> result = start(owned, Runnable::run, () -> { throw primary; });
            try { result.get(2, TimeUnit.SECONDS); fail("cancelled tool became success"); } catch (CancellationException expected) { assertSame(primary, expected); } catch (ExecutionException expected) { assertSame(primary, expected.getCause()); }
            assertTrue(owner.actions.isEmpty());
        }
        assertEquals(0, errors.get());
    }
    @Test public void directCancellationReachesPendingProviderWithAndWithoutRoot() {
        for (boolean owned : new boolean[]{false, true}) {
            CompletableFuture<ToolExecutionResult> source = new CompletableFuture<>(); CompletableFuture<ToolExecutionResult> result = start(owned, Runnable::run, () -> source);
            assertTrue(result.cancel(true)); assertTrue("provider Future still pending", source.isCancelled()); assertTrue(owner.actions.isEmpty());
        }
    }
    @Test(timeout=5000) public void cancelledQueuedToolNeverInvokesProviderOrInterruptsBorrowedWorker() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger(), interrupts = new AtomicInteger();
        pool.submit(() -> { blocked.countDown(); try { release.await(); } catch (InterruptedException e) { interrupts.incrementAndGet(); } });
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS)); CompletableFuture<ToolExecutionResult> result = start(false, pool, () -> { calls.incrementAndGet(); return new CompletableFuture<>(); });
            assertTrue(result.cancel(true)); release.countDown(); pool.submit(() -> {}).get(2, TimeUnit.SECONDS); assertEquals(0, calls.get()); assertEquals(0, interrupts.get()); assertFalse(pool.isShutdown());
        } finally { release.countDown(); }
    }
    @Test(timeout=8000) public void cancelTrueInterruptsRunningProviderWithoutClosingCallerExecutor() throws Exception {
        for (boolean owned : new boolean[]{false, true}) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1); AtomicBoolean interrupted = new AtomicBoolean(); CompletableFuture<ToolExecutionResult> source = new CompletableFuture<>();
            try {
                CompletableFuture<ToolExecutionResult> result = start(owned, pool, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { interrupted.set(true); } finally { stopped.countDown(); } return source; });
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(result.cancel(true)); assertTrue("running provider not interrupted", stopped.await(2, TimeUnit.SECONDS)); assertTrue(interrupted.get());
                pool.submit(() -> assertNull(CancellationContext.current())).get(2, TimeUnit.SECONDS); assertTrue("late provider Future was lost", source.isCancelled()); assertTrue(owner.actions.isEmpty()); assertFalse(pool.isShutdown());
            } finally { release.countDown(); }
        }
    }
    @Test(timeout=5000) public void cancelFalseStillCancelsFuturePublishedLaterWithoutInterruptingProvider() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean interrupted = new AtomicBoolean(); CompletableFuture<ToolExecutionResult> source = new CompletableFuture<>();
        try {
            CompletableFuture<ToolExecutionResult> result = start(true, pool, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { interrupted.set(true); } return source; });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(result.cancel(false)); assertFalse(interrupted.get()); release.countDown(); pool.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertFalse(interrupted.get()); assertTrue("late provider Future was lost", source.isCancelled()); assertTrue(owner.actions.isEmpty());
        } finally { release.countDown(); }
    }
    @Test public void cancellationAfterSuccessDoesNotCancelProviderResult() throws Exception {
        CompletableFuture<ToolExecutionResult> source = CompletableFuture.completedFuture(ToolExecutionResult.builder().resultText("ok").build()); CompletableFuture<ToolExecutionResult> result = start(true, Runnable::run, () -> source);
        assertEquals("ok", result.get(2, TimeUnit.SECONDS).resultText()); assertFalse(result.cancel(true)); assertFalse(source.isCancelled()); assertTrue(owner.actions.isEmpty());
    }
    @Test(timeout=5000) public void cancelTrueCanUpgradeEarlierCancelFalseWhileProviderStillRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1); AtomicBoolean interrupted = new AtomicBoolean(); CompletableFuture<ToolExecutionResult> source = new CompletableFuture<>();
        try {
            CompletableFuture<ToolExecutionResult> result = start(false, pool, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { interrupted.set(true); } finally { stopped.countDown(); } return source; });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(result.cancel(false)); assertFalse(stopped.await(100, TimeUnit.MILLISECONDS)); assertFalse(interrupted.get());
            assertTrue(result.cancel(true)); assertTrue(stopped.await(2, TimeUnit.SECONDS)); assertTrue(interrupted.get()); pool.submit(() -> {}).get(2, TimeUnit.SECONDS); assertTrue(source.isCancelled());
        } finally { release.countDown(); }
    }
    @Test public void throwingProviderCancellationStillTerminatesWaiterAndPreservesCleanupError() {
        RuntimeException primary = new IllegalStateException("provider cancel failed");
        CompletableFuture<ToolExecutionResult> source = new CompletableFuture<ToolExecutionResult>() { @Override public boolean cancel(boolean interrupt) { throw primary; } };
        CompletableFuture<ToolExecutionResult> result = start(false, Runnable::run, () -> source);
        try { result.cancel(true); fail("provider cleanup failure hidden"); } catch (RuntimeException expected) { assertSame(primary, expected); }
        assertTrue(result.isCancelled()); assertFalse(source.isDone()); source.complete(ToolExecutionResult.builder().resultText("late").build()); assertTrue(result.isCancelled()); assertFalse(pool.isShutdown());
    }
}
