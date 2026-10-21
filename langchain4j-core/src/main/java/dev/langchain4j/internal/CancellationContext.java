package dev.langchain4j.internal;

import dev.langchain4j.Internal;
import java.util.function.Supplier;

/** Internal resource ownership bridge; no context is inherited by unrelated executor tasks. */
@Internal
public final class CancellationContext {
    private static final ThreadLocal<Registrar> CURRENT = new ThreadLocal<>();
    private static final Registration NOOP = () -> { };
    private CancellationContext() { }

    @FunctionalInterface
    public interface Registration extends AutoCloseable { @Override void close(); }
    @FunctionalInterface
    public interface Registrar { Registration register(Runnable cancel); }

    public static Registrar current() { return CURRENT.get(); }
    public static Registration register(Runnable cancel) {
        Registrar registrar = current();
        return registrar == null ? NOOP : registrar.register(cancel);
    }
    public static <T> T call(Registrar registrar, Supplier<T> action) {
        Registrar previous = CURRENT.get();
        if (registrar == null) CURRENT.remove(); else CURRENT.set(registrar);
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static void run(Registrar registrar, Runnable action) {
        call(registrar, () -> { action.run(); return null; });
    }
}
