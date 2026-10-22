import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.invocation.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.*;
import dev.langchain4j.spi.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class CompletionExecutorJdk8Test {
    OpenAiJdk8Test fixture; ThreadPoolExecutor pool; ExecutorProvider previous;
    final OwnedTaskJdk8Test.Owner owner = new OwnedTaskJdk8Test.Owner();
    final AtomicInteger completions = new AtomicInteger(), errors = new AtomicInteger(), wraps = new AtomicInteger();
    final AtomicReference<CancellationContext.Registrar> callbackOwner = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>(), uncaught = new AtomicReference<>();
    final CountDownLatch terminal = new CountDownLatch(1), fatal = new CountDownLatch(1), releaseBlocker = new CountDownLatch(1);
    final CompletableFuture<ChatResponse> result = new CompletableFuture<>();
    final ThreadLocal<String> host = new ThreadLocal<>();
    final Memory memory = new Memory(); volatile Error handlerFailure;
    class Memory implements ChatMemory {
        final List<ChatMessage> messages = new CopyOnWriteArrayList<>(); final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        volatile boolean gated; volatile Throwable error; final AtomicInteger interrupted = new AtomicInteger();
        public Object id() { return "fixture"; }
        public void add(ChatMessage message) {
            if (message instanceof AiMessage) {
                entered.countDown(); try {
                    if (gated) try { release.await(); } catch (InterruptedException e) { interrupted.incrementAndGet(); throw new CancellationException("memory interrupted"); }
                    if (error instanceof Error) throw (Error) error; if (error instanceof RuntimeException) throw (RuntimeException) error;
                    messages.add(message);
                } finally { stopped.countDown(); }
            } else messages.add(message);
        }
        public List<ChatMessage> messages() { return new ArrayList<>(messages); }
        public void clear() { messages.clear(); }
    }
    @Before public void prepare() throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8.")); previous = ExecutorProvider.get(); ExecutorProvider.set(new ExecutorProvider() { public java.util.concurrent.Executor executor() { return null; } });
        pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, task -> { Thread t = new Thread(task, "completion-fixture"); t.setUncaughtExceptionHandler((thread, error) -> { uncaught.set(error); fatal.countDown(); }); return t; });
        fixture = new OpenAiJdk8Test(); fixture.start(); memory.add(UserMessage.from("fixture"));
    }
    @After public void close() throws Exception { memory.release.countDown(); releaseBlocker.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); ExecutorProvider.set(previous); host.remove(); fixture.stop(); if (handlerFailure == null) assertNull(uncaught.get()); }
    void start(java.util.concurrent.Executor executor) throws Exception {
        UserMessage user = UserMessage.from("fixture"); List<ChatMessage> messages = Collections.singletonList(user);
        AiServiceContext context = AiServiceContext.create(AiServicesJdk8Test.Streaming.class); context.streamingChatModel = fixture.streaming(); context.initChatMemories(memory);
        InvocationContext invocation = CancellationContext.call(owner, () -> CapturedContextSupport.captureInto(InvocationContext.builder().interfaceName(AiServicesJdk8Test.Streaming.class.getName()).methodName("chat").methodArgument("fixture").chatMemoryId("fixture").userMessage(user)).build());
        TokenStream stream = new AiServiceTokenStream(AiServiceTokenStreamParameters.builder().messages(messages).context(context).invocationContext(invocation).toolServiceContext(context.toolService.createContext(invocation, user, messages))
                .toolArgumentsErrorHandler(context.toolService.argumentsErrorHandler()).toolExecutionErrorHandler(context.toolService.executionErrorHandler()).completeResponseProcessingExecutor(executor).methodKey(AiServicesJdk8Test.Streaming.class.getMethod("chat", String.class)).build());
        stream.onCompleteResponse(response -> { callbackOwner.set(CancellationContext.current()); completions.incrementAndGet(); result.complete(response); terminal.countDown(); })
                .onError(error -> { callbackOwner.set(CancellationContext.current()); failure.set(error); errors.incrementAndGet(); result.completeExceptionally(error); terminal.countDown(); if (handlerFailure != null) throw handlerFailure; }).start();
    }
    static void await(String message, java.util.function.BooleanSupplier ready) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2); assertTrue(message, ready.getAsBoolean()); }
    void clean() throws Exception { pool.submit(() -> assertNull(CancellationContext.current())).get(2, TimeUnit.SECONDS); fixture.awaitReleased(); assertTrue(owner.actions.isEmpty()); assertFalse(pool.isShutdown()); }
    @Test(timeout=10000) public void queuedCompletionIsCancelledBeforeBorrowedExecutorIsReleased() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1); AtomicInteger unrelatedInterrupts = new AtomicInteger();
        pool.submit(() -> { blocked.countDown(); try { releaseBlocker.await(); } catch (InterruptedException e) { unrelatedInterrupts.incrementAndGet(); } }); assertTrue(blocked.await(2, TimeUnit.SECONDS));
        start(pool); await("completion was not queued", () -> !pool.getQueue().isEmpty()); fixture.awaitReleased(); owner.cancel(); boolean immediate = terminal.await(200, TimeUnit.MILLISECONDS);
        releaseBlocker.countDown(); clean(); assertTrue("queued cancellation did not notify before release; successes=" + completions.get(), immediate); assertEquals(0, completions.get()); assertEquals(1, errors.get()); assertTrue(failure.get() instanceof CancellationException); assertSame(owner, callbackOwner.get()); assertEquals(1, memory.messages.size()); assertEquals(0, unrelatedInterrupts.get());
    }
    @Test(timeout=10000) public void runningCompletionIsInterruptedAndNotifiesCancellationOnce() throws Exception {
        memory.gated = true; start(pool); assertTrue(memory.entered.await(3, TimeUnit.SECONDS)); fixture.awaitReleased(); owner.cancel();
        boolean stopped = memory.stopped.await(300, TimeUnit.MILLISECONDS); memory.release.countDown(); clean(); assertTrue("completion worker not interrupted", stopped); assertEquals(1, memory.interrupted.get()); assertEquals(0, completions.get()); assertEquals(1, errors.get()); assertTrue(failure.get() instanceof CancellationException); assertSame(owner, callbackOwner.get()); assertEquals(1, memory.messages.size());
    }
    @Test(timeout=10000) public void successfulCompletionWrapsProviderOnceAndRestoresReusedWorker() throws Exception {
        host.set("captured"); ExecutorProvider.set(new ExecutorProvider() { public java.util.concurrent.Executor executor() { return null; } public CapturedContext captureContext() { String value = host.get(); return task -> { wraps.incrementAndGet(); return () -> { String old = host.get(); host.set(value); try { assertEquals("captured", host.get()); task.run(); } finally { host.set(old); } }; }; } });
        pool.submit(() -> host.set("worker")).get(2, TimeUnit.SECONDS); start(pool); assertEquals("你好", result.get(3, TimeUnit.SECONDS).aiMessage().text()); clean(); assertEquals(1, wraps.get()); assertSame(owner, callbackOwner.get()); assertEquals(1, completions.get()); assertEquals(0, errors.get()); assertEquals(2, memory.messages.size()); pool.submit(() -> assertEquals("worker", host.get())).get(2, TimeUnit.SECONDS);
    }
    @Test(timeout=10000) public void rejectedCompletionPreservesOriginalRejectionAndReleasesOwner() throws Exception {
        RejectedExecutionException primary = new RejectedExecutionException("completion rejected"); start(task -> { throw primary; }); assertTrue(terminal.await(3, TimeUnit.SECONDS)); clean(); assertSame(primary, failure.get()); assertSame(owner, callbackOwner.get()); assertEquals(1, errors.get()); assertEquals(0, completions.get());
    }
    @Test(timeout=10000) public void providerWrapFailureNotifiesWithoutReenteringBrokenProvider() throws Exception {
        RuntimeException primary = new IllegalStateException("provider wrap failed"); ExecutorProvider.set(new ExecutorProvider() { public java.util.concurrent.Executor executor() { return null; } public CapturedContext captureContext() { return task -> { throw primary; }; } });
        start(pool); assertTrue(terminal.await(3, TimeUnit.SECONDS)); clean(); assertSame(primary, failure.get()); assertSame(owner, callbackOwner.get()); assertEquals(1, errors.get());
    }
    @Test(timeout=10000) public void providerRunnableFailureNotifiesWithoutRunningCompletionBody() throws Exception {
        RuntimeException primary = new IllegalStateException("provider callback failed"); ExecutorProvider.set(new ExecutorProvider() { public java.util.concurrent.Executor executor() { return null; } public CapturedContext captureContext() { return task -> () -> { throw primary; }; } });
        start(pool); assertTrue("failed provider callback left stream pending", terminal.await(1, TimeUnit.SECONDS)); clean(); assertSame(primary, failure.get()); assertSame(owner, callbackOwner.get()); assertEquals(1, memory.messages.size()); assertEquals(1, errors.get());
    }
    @Test(timeout=10000) public void completionErrorPreservesIdentityAndCallsErrorHandlerOnce() throws Exception {
        AssertionError primary = new AssertionError("memory fixture"); memory.error = primary; start(pool); assertTrue(terminal.await(3, TimeUnit.SECONDS)); clean(); assertSame(primary, failure.get()); assertSame(owner, callbackOwner.get()); assertEquals(1, errors.get()); assertEquals(0, completions.get());
    }
    @Test(timeout=10000) public void errorHandlerErrorReachesBorrowedWorkerUncaughtChannel() throws Exception {
        memory.error = new IllegalStateException("memory failed"); handlerFailure = new AssertionError("error handler failed"); start(pool); assertTrue(terminal.await(3, TimeUnit.SECONDS)); assertTrue(fatal.await(2, TimeUnit.SECONDS)); clean(); assertSame(handlerFailure, uncaught.get()); assertEquals(1, errors.get());
    }
}
