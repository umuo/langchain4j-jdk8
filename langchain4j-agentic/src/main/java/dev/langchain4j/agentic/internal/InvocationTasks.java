package dev.langchain4j.agentic.internal;

import dev.langchain4j.Internal;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Resources owned by one root invocation, independent of output-key replacement. */
@Internal
public final class InvocationTasks {
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private final DefaultAgenticScope scope;
    private final Set<Registration> tasks = new HashSet<>();
    private final List<Computation> computations = new ArrayList<>();
    private final java.util.concurrent.CompletableFuture<Void> computationFailure = new java.util.concurrent.CompletableFuture<>();
    private volatile boolean cancelled;
    private volatile boolean finished;
    private final dev.langchain4j.internal.CancellationContext.Registrar registrar =
            new dev.langchain4j.internal.CancellationContext.Registrar() {
                @Override public dev.langchain4j.internal.CancellationContext.Registration register(Runnable cancel) {
                    return InvocationTasks.this.register(cancel);
                }
                @Override public <T> T inContext(Supplier<T> action) { return withBinding(action); }
            };

    public InvocationTasks(DefaultAgenticScope scope) { this.scope = scope; }
    public boolean finished() { return finished || cancelled; }
    public void finish() { finished = true; }
    public static InvocationTasks bound(DefaultAgenticScope scope) {
        for (Binding b = CURRENT.get(); b != null; b = b.previous) if (b.tasks.scope == scope) return b.tasks;
        return null;
    }
    public static void check(DefaultAgenticScope scope) {
        InvocationTasks tasks = bound(scope);
        if (tasks != null && tasks.cancelled) throw new CancellationException("Root invocation cancelled");
    }
    public static void mutate(DefaultAgenticScope scope, Runnable action) {
        InvocationTasks tasks = bound(scope);
        if (tasks == null) { action.run(); return; }
        synchronized (tasks) { check(scope); action.run(); }
    }
    public <T> T call(Supplier<T> action) {
        return dev.langchain4j.internal.CancellationContext.call(registrar, () -> { check(scope); return action.get(); });
    }
    private <T> T withBinding(Supplier<T> action) {
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(this, previous));
        // Cancelled callbacks must still close responses and deliver failure signals.
        // Their scope mutations are rejected by check/mutate using this captured generation.
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    /** Register a framework-owned computation before dispatch, independently of state keys. */
    public void trackComputation(java.util.concurrent.CompletableFuture<?> source) {
        Computation computation = new Computation(source);
        computation.observed = source.handle((value, error) -> {
            if (error != null) {
                computation.error = dev.langchain4j.internal.Exceptions.unwrapCompletionException(error);
                if (!(computation.error instanceof dev.langchain4j.agentic.scope.AgenticSystemSuspendedException)) {
                    computationFailure.completeExceptionally(computation.error);
                }
            }
            return null;
        });
        synchronized (this) { if (!cancelled) computations.add(computation); }
    }

    /** A settled control-flow pause belongs to the preceding attempt, not its resumed work. */
    public synchronized void resumeComputations() {
        computations.removeIf(Computation::controlledSuspension);
    }

    /** Successful roots settle dispatched computations; ordinary failure wins over pending siblings. */
    public void awaitComputations() {
        dev.langchain4j.agentic.scope.AgenticSystemSuspendedException suspended = null;
        for (;;) {
            if (computationFailure.isDone()) DelayedResponse.join(computationFailure);
            List<Computation> batch;
            synchronized (this) { batch = new ArrayList<>(computations); computations.clear(); }
            if (batch.isEmpty()) {
                if (suspended != null) throw suspended;
                return;
            }
            java.util.concurrent.CompletableFuture<?>[] observed = batch.stream()
                    .map(computation -> computation.observed).toArray(java.util.concurrent.CompletableFuture<?>[]::new);
            DelayedResponse.join(java.util.concurrent.CompletableFuture.anyOf(
                    java.util.concurrent.CompletableFuture.allOf(observed), computationFailure));
            if (computationFailure.isDone()) DelayedResponse.join(computationFailure);
            for (Computation computation : batch) {
                if (suspended == null && computation.error instanceof dev.langchain4j.agentic.scope.AgenticSystemSuspendedException) {
                    suspended = (dev.langchain4j.agentic.scope.AgenticSystemSuspendedException) computation.error;
                }
            }
        }
    }

    private static final class Computation {
        final java.util.concurrent.CompletableFuture<?> source;
        java.util.concurrent.CompletableFuture<Void> observed;
        volatile Throwable error;
        Computation(java.util.concurrent.CompletableFuture<?> source) { this.source = source; }
        boolean controlledSuspension() {
            if (!source.isDone()) return false;
            try { source.join(); return false; }
            catch (java.util.concurrent.CompletionException failure) {
                return dev.langchain4j.internal.Exceptions.unwrapCompletionException(failure)
                        instanceof dev.langchain4j.agentic.scope.AgenticSystemSuspendedException;
            } catch (CancellationException cancelled) { return false; }
        }
    }

    public Registration register(Runnable cancel) {
        Registration registration = new Registration(cancel);
        boolean immediately;
        synchronized (this) { immediately = cancelled; if (!immediately) tasks.add(registration); }
        if (immediately) registration.cancel();
        return registration;
    }
    public void cancel(Throwable primary) {
        List<Registration> pending;
        synchronized (this) { cancelled = true; pending = new ArrayList<>(tasks); tasks.clear(); computations.clear(); }
        computationFailure.completeExceptionally(new CancellationException("Root invocation cancelled"));
        for (Registration task : pending) AgenticJdk8.suppressCleanupFailure(primary, task::cancel);
    }
    public final class Registration implements dev.langchain4j.internal.CancellationContext.Registration {
        private final Runnable cancel;
        private boolean closed;
        private Registration(Runnable cancel) { this.cancel = cancel; }
        public void close() { synchronized (InvocationTasks.this) { closed = true; tasks.remove(this); } }
        private void cancel() {
            synchronized (InvocationTasks.this) { if (closed) return; closed = true; tasks.remove(this); }
            cancel.run();
        }
    }
    private static final class Binding {
        private final InvocationTasks tasks;
        private final Binding previous;
        private Binding(InvocationTasks tasks, Binding previous) { this.tasks = tasks; this.previous = previous; }
    }
}
