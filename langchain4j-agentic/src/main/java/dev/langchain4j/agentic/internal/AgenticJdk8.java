package dev.langchain4j.agentic.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Java 8 equivalents used by the Agentic implementation. */
@dev.langchain4j.Internal
public final class AgenticJdk8 {
    private AgenticJdk8() {}

    /** Internal checkpoint marker: this planner restores its own phase and dispatch set. */
    public static final String PLANNER_MANAGES_RESUME = "__plannerManagesResume";

    /** Preserve the primary operation error when best-effort failure cleanup also fails. */
    public static void suppressCleanupFailure(Throwable primary, Runnable cleanup) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(cleanup, "cleanup");
        try {
            cleanup.run();
        } catch (Throwable secondary) {
            if (secondary == primary) return;
            for (Throwable existing : primary.getSuppressed()) if (existing == secondary) return;
            primary.addSuppressed(secondary);
        }
    }

    /** Settle the selected computation/stream before a planner consumes its value.
     * External responses remain markers so that the planner can retain its suspension policy. */
    public static Object invocationOutput(dev.langchain4j.agentic.scope.AgenticScope scope,
                                          dev.langchain4j.agentic.scope.AgentInvocation invocation) {
        Object value = scope instanceof dev.langchain4j.agentic.scope.DefaultAgenticScope
                ? ((dev.langchain4j.agentic.scope.DefaultAgenticScope) scope).resolveAsyncState(invocation)
                : invocation.output();
        while (value instanceof DelayedResponse<?> && !(value instanceof DeferredResponse<?>)) {
            Object resolved = ((DelayedResponse<?>) value).blockingGet();
            if (resolved == value) throw new IllegalStateException("Self-referencing planner output: " + invocation.agentId());
            value = resolved;
        }
        return value;
    }

    public static <T> void ifPresentOrElse(Optional<T> value, Consumer<? super T> action, Runnable emptyAction) {
        if (value.isPresent()) {
            action.accept(value.get());
        } else {
            emptyAction.run();
        }
    }

    public static <T> T requireNonNullElse(T value, T fallback) {
        return value != null ? value : Objects.requireNonNull(fallback, "defaultObj");
    }

    public static String packageName(Class<?> type) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive()) {
            return "java.lang";
        }
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    public static Path writeString(Path path, CharSequence text) throws IOException {
        // The encoder reports malformed surrogate pairs, as Files.writeString does.
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return Files.write(path, bytes);
    }
}
