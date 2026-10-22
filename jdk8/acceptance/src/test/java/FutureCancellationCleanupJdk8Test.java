import dev.langchain4j.internal.CompletableFutureUtils;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class FutureCancellationCleanupJdk8Test {
    @Before public void runtime() { assertTrue(System.getProperty("java.version").startsWith("1.8.")); }
    CompletableFuture<String> refusing(RuntimeException failure) { return new CompletableFuture<String>() { @Override public boolean cancel(boolean interrupt) { throw failure; } }; }
    Throwable cancellation(CompletableFuture<?> future) { try { future.join(); fail(); return null; } catch (CancellationException expected) { return expected; } }
    @Test public void directCleanupFailureIsAttachedToRootCancellation() {
        RuntimeException primary = new IllegalStateException("cleanup"); CompletableFuture<String> root = new CompletableFuture<>(); CompletableFutureUtils.propagateCancellation(root, refusing(primary));
        assertTrue(root.cancel(true)); assertArrayEquals(new Throwable[]{primary}, cancellation(root).getSuppressed());
    }
    @Test public void nestedCancellationCopiesLeafCleanupFailureToCaller() {
        RuntimeException primary = new IllegalStateException("leaf cleanup"); CompletableFuture<String> root = new CompletableFuture<>(), middle = new CompletableFuture<>();
        CompletableFutureUtils.propagateCancellation(root, middle); CompletableFutureUtils.propagateCancellation(middle, refusing(primary));
        assertTrue(root.cancel(true)); assertTrue(middle.isCancelled()); assertArrayEquals(new Throwable[]{primary}, cancellation(root).getSuppressed()); assertArrayEquals(new Throwable[]{primary}, cancellation(middle).getSuppressed());
    }
    @Test public void repeatedRoutesDoNotDuplicateSameCleanupFailure() {
        RuntimeException primary = new IllegalStateException("shared leaf"); CompletableFuture<String> root = new CompletableFuture<>(), middle = new CompletableFuture<>(); CompletableFuture<String> leaf = refusing(primary);
        CompletableFutureUtils.propagateCancellation(root, middle); CompletableFutureUtils.propagateCancellation(middle, leaf); CompletableFutureUtils.propagateCancellation(root, leaf);
        root.cancel(true); assertArrayEquals(new Throwable[]{primary}, cancellation(root).getSuppressed());
    }
    @Test public void normalCompletionAndNullRootLeaveLeafUntouched() {
        CompletableFuture<String> root = new CompletableFuture<>(), leaf = new CompletableFuture<>(); CompletableFutureUtils.propagateCancellation(null, leaf); CompletableFutureUtils.propagateCancellation(root, leaf); root.complete("done"); assertFalse(leaf.isDone()); assertEquals("done", root.join());
    }
}
