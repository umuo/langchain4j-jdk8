package dev.langchain4j.mcp.client;

import java.util.concurrent.*;

/** Java 8 equivalent of orTimeout. Completed requests do not retain timer tasks. */
final class McpTimeouts {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "langchain4j-mcp-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }
    static <T> CompletableFuture<T> orTimeout(CompletableFuture<T> future, long millis) {
        ScheduledFuture<?> timeout = TIMER.schedule(
                () -> future.completeExceptionally(new TimeoutException()), millis, TimeUnit.MILLISECONDS);
        future.whenComplete((value, error) -> timeout.cancel(false));
        return future;
    }
    private McpTimeouts() {}
}
