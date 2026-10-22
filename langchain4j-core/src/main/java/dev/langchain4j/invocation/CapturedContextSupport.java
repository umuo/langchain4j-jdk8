package dev.langchain4j.invocation;

import dev.langchain4j.Internal;
import dev.langchain4j.internal.DefaultExecutorProvider;
import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.spi.CapturedContext;
import dev.langchain4j.spi.ExecutorProvider;
import java.util.concurrent.Executor;

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
        builder.capturedContext = owner == null ? provided : task -> {
            // Preserve the provider's wrapping time and its own per-task restoration contract.
            Runnable wrapped = provided == null ? task : provided.wrap(task);
            return () -> CancellationContext.run(owner, wrapped);
        };
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
        return task -> executor.execute(capturedContext.wrap(task));
    }
}
