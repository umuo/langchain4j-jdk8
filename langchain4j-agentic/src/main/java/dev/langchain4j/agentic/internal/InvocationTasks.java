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
    private volatile boolean cancelled;
    private volatile boolean finished;

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
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(this, previous));
        try { check(scope); return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
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
        synchronized (this) { cancelled = true; pending = new ArrayList<>(tasks); tasks.clear(); }
        for (Registration task : pending) AgenticJdk8.suppressCleanupFailure(primary, task::cancel);
    }
    public final class Registration implements AutoCloseable {
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
