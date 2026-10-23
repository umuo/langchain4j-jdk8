package dev.langchain4j.agentic.internal;

import dev.langchain4j.Internal;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.internal.Jdk8;
import java.util.function.Supplier;

/** Invocation-local input/output binding while the shared key contains its pending result. */
@Internal
public final class DeferredScopeContext {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private DeferredScopeContext() { }

    public static boolean handles(DefaultAgenticScope scope, String key) {
        Frame frame = CURRENT.get();
        return frame != null && frame.scope == scope && frame.key != null && frame.key.equals(key);
    }

    /** An independent root must publish into shared state, not the calling worker's old slot. */
    public static <T> T withoutScopeBinding(DefaultAgenticScope scope, Supplier<T> action) {
        Frame previous = CURRENT.get();
        if (previous == null || previous.scope != scope) return action.get();
        CURRENT.remove();
        try { return action.get(); }
        finally { CURRENT.set(previous); }
    }

    public static Object read() { return CURRENT.get().value; }
    public static void write(Object value) { CURRENT.get().value = value; }

    @Internal
    public static final class Frame {
        private final DefaultAgenticScope scope;
        private final String key;
        private final InvocationTasks tasks;
        private Object value;

        public Frame(DefaultAgenticScope scope, String key) {
            this.scope = scope;
            this.tasks = scope.invocationTasks();
            this.key = key == null || Jdk8.isBlank(key) ? null : key;
            this.value = this.key == null ? null
                    : handles(scope, this.key) ? read() : scope.state().get(this.key);
        }

        public <T> T call(Supplier<T> action) {
            Frame previous = CURRENT.get();
            CURRENT.set(this);
            try {
                return tasks.call(action);
            } finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
    }
}
