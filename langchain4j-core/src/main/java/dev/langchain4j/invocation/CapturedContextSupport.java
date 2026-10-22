package dev.langchain4j.invocation;

import dev.langchain4j.Internal;
import dev.langchain4j.internal.DefaultExecutorProvider;
import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.spi.CapturedContext;
import dev.langchain4j.spi.ExecutorProvider;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.Objects;

/**
 * Carries the context of the thread that started an AI Service invocation, captured with
 * {@link ExecutorProvider#captureContext()}, in the {@link InvocationContext} of the invocation, so that every task
 * the invocation offloads runs with it, whichever thread submits the task.
 */
@Internal
public final class CapturedContextSupport {

    private CapturedContextSupport() {
    }

    /**
     * Captures the registered {@link ExecutorProvider} context and any internal cancellation owner into the
     * given builder. The owner is composed with, rather than replacing, the provider context.
     *
     * @return the given builder
     */
    public static InvocationContext.Builder captureInto(InvocationContext.Builder builder) {
        CapturedContext provided = DefaultExecutorProvider.captureContext();
        CancellationContext.Registrar owner = CancellationContext.current();
        builder.capturedContext = owner == null ? provided : new OwnedCapturedContext(provided, owner);
        return builder;
    }

    /**
     * @return an executor that submits tasks to the given executor, running them with the context captured when the
     *         given invocation started; the given executor itself if nothing was captured
     */
    public static Executor restoringIn(Executor executor, InvocationContext invocationContext) {
        if (executor == null || !(invocationContext instanceof DefaultInvocationContext) || ((DefaultInvocationContext) invocationContext).capturedContext() == null) {
            return executor;
        }
        CapturedContext capturedContext = ((DefaultInvocationContext) invocationContext).capturedContext();
        return new RestoringExecutor(executor, capturedContext);
    }

    /** Submit a supplier with its invocation context and root-owned cancellation, when present.
     * Caller-owned executors remain open; cancelled queued entries become no-ops when dequeued. */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor, InvocationContext context) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(executor);
        CapturedContext captured = context instanceof DefaultInvocationContext
                ? ((DefaultInvocationContext) context).capturedContext() : null;
        if (executor instanceof RestoringExecutor && ((RestoringExecutor) executor).captured == captured) {
            executor = ((RestoringExecutor) executor).delegate;
        }
        if (!(captured instanceof OwnedCapturedContext)) {
            return CompletableFuture.supplyAsync(supplier, restoringIn(executor, context));
        }
        OwnedCapturedContext owned = (OwnedCapturedContext) captured;
        OwnedTask<T> task = new OwnedTask<>(supplier);
        try {
            task.attach(owned.owner.register(task::cancelOwned));
            if (!task.isDone()) {
                Runnable command = owned.wrap(task::invokeSupplier);
                task.prepare(command);
                if (!task.isDone()) executor.execute(task);
            }
        } catch (RuntimeException | Error failure) {
            task.rejected(failure);
            throw failure;
        }
        return task;
    }

    private static final class RestoringExecutor implements Executor {
        private final Executor delegate;
        private final CapturedContext captured;
        RestoringExecutor(Executor delegate, CapturedContext captured) { this.delegate = delegate; this.captured = captured; }
        @Override public void execute(Runnable task) { delegate.execute(captured.wrap(task)); }
    }

    private static final class OwnedCapturedContext implements CapturedContext {
        private final CapturedContext provided;
        private final CancellationContext.Registrar owner;
        OwnedCapturedContext(CapturedContext provided, CancellationContext.Registrar owner) {
            this.provided = provided;
            this.owner = owner;
        }
        @Override public Runnable wrap(Runnable task) {
            Runnable wrapped = provided == null ? task : provided.wrap(task);
            return () -> CancellationContext.run(owner, wrapped);
        }
    }

    private static final class OwnedTask<T> extends CompletableFuture<T> implements Runnable {
        private final Object workerLock = new Object();
        private Supplier<T> supplier;
        private Runnable command;
        private Thread worker;
        private CancellationContext.Registration registration;
        OwnedTask(Supplier<T> supplier) { this.supplier = supplier; }
        void attach(CancellationContext.Registration registration) {
            synchronized (workerLock) { this.registration = registration; }
            if (isDone()) releaseIfIdle();
        }
        void prepare(Runnable command) {
            synchronized (workerLock) { if (!isDone()) this.command = command; }
        }
        @Override public void run() {
            Runnable action;
            synchronized (workerLock) {
                if (isDone() || worker != null || command == null) action = null;
                else { worker = Thread.currentThread(); action = command; command = null; }
            }
            if (action == null) { releaseIfIdle(); return; }
            try { action.run(); }
            catch (Throwable failure) {
                if (!completeExceptionally(failure)) {
                    // A provider cleanup failure after publication belongs to the executor's
                    // uncaught-error channel; do not silently discard it or replace the result.
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    if (failure instanceof Error) throw (Error) failure;
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }
            finally {
                synchronized (workerLock) { worker = null; }
                releaseIfIdle();
            }
        }
        void invokeSupplier() {
            Supplier<T> action;
            synchronized (workerLock) { if (isDone()) return; action = supplier; }
            try { complete(action.get()); }
            catch (Throwable failure) { completeExceptionally(failure); }
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            // Completing a future can invoke arbitrary callbacks; never hold workerLock here.
            boolean cancelled = super.cancel(false);
            if (cancelled) {
                if (mayInterruptIfRunning) interruptWorker();
                releaseIfIdle();
            }
            return cancelled;
        }
        void cancelOwned() {
            // A caller may have logically completed the future while its supplier still runs.
            if (!cancel(true)) { interruptWorker(); releaseIfIdle(); }
        }
        void interruptWorker() {
            synchronized (workerLock) {
                if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            }
        }
        void rejected(Throwable failure) { completeExceptionally(failure); releaseIfIdle(); }
        void releaseIfIdle() {
            CancellationContext.Registration closing;
            synchronized (workerLock) {
                if (worker != null) return;
                command = null;
                supplier = null;
                closing = registration;
                registration = null;
            }
            if (closing != null) closing.close();
        }
    }

}
