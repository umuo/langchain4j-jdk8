package dev.langchain4j.agentic.internal;

import dev.langchain4j.Internal;
import dev.langchain4j.internal.DefaultExecutorProvider;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class AsyncResponse<T> implements DelayedResponse<T> {
    private final CompletableFuture<T> futureResponse = new CompletableFuture<>();
    private final Object workerLock = new Object();
    private Thread worker;

    public AsyncResponse(Supplier<T> responseSupplier) { this(responseSupplier, null); }

    AsyncResponse(Supplier<T> responseSupplier, InvocationTasks tasks) {
        this(responseSupplier, tasks, DefaultExecutorProvider.getDefaultExecutor());
    }

    AsyncResponse(Supplier<T> responseSupplier, InvocationTasks tasks, java.util.concurrent.Executor executor) {
        Objects.requireNonNull(responseSupplier);
        InvocationTasks.Registration registration = tasks == null ? null : tasks.register(this::cancel);
        if (registration != null) futureResponse.whenComplete((value, failure) -> registration.close());
        if (tasks != null) tasks.trackComputation(futureResponse);
        try {
            executor.execute(() -> {
                synchronized (workerLock) {
                    if (futureResponse.isCancelled()) return;
                    worker = Thread.currentThread();
                }
                try { futureResponse.complete(responseSupplier.get()); }
                catch (Throwable failure) { futureResponse.completeExceptionally(failure); }
                finally { synchronized (workerLock) { worker = null; } }
            });
        } catch (RuntimeException failure) {
            futureResponse.completeExceptionally(failure);
            throw failure;
        }
    }
    CompletableFuture<T> completion() { return futureResponse; }

    private void cancel() {
        synchronized (workerLock) {
            futureResponse.cancel(false);
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        }
    }
    @Internal
    public boolean isCancelled() { return futureResponse.isCancelled(); }
    @Override public boolean isDone() { return futureResponse.isDone(); }
    @Override public T blockingGet() { return DelayedResponse.join(futureResponse); }
    @Override public String toString() { return result().toString(); }
}
