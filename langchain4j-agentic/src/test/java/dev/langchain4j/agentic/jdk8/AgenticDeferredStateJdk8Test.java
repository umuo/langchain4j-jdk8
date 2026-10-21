package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.DelayedResponse;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Child JVM boundaries make a self-wait regression fail without retaining a stuck worker. */
public class AgenticDeferredStateJdk8Test {
    public interface SelfWriter {
        @Agent(outputKey = "topic") @UserMessage("Write {{topic}}") String write(@V("topic") String topic);
    }
    public interface SelfStreamingWriter {
        @Agent(outputKey = "topic") @UserMessage("Write {{topic}}") TokenStream write(@V("topic") String topic);
    }
    public interface Suspendable extends AgenticScopeAccess {
        @Agent ResultWithAgenticScope<String> run(@MemoryId String id);
    }

    @Test(timeout = 12000) public void asyncFunctionReadsItsOriginalOutputInput() throws Exception { child("read"); }
    @Test(timeout = 12000) public void asyncFunctionWritesStayPendingUntilItsResult() throws Exception { child("write"); }
    @Test(timeout = 12000) public void asyncHttpRetryReadsSameKeyInputWithoutWaitingOnItself() throws Exception { child("http"); }
    @Test(timeout = 12000) public void intermediateSseRetryReadsSameKeyInputWithoutWaitingOnItself() throws Exception { child("sse"); }
    @Test(timeout = 12000) public void asyncHttpRetryCanUpdateItsOwnInputOutputKey() throws Exception { child("http-update"); }
    @Test(timeout = 12000) public void intermediateSseRetryCanUpdateItsOwnInputOutputKey() throws Exception { child("sse-update"); }
    @Test(timeout = 12000) public void asyncHumanResponseFlattensAndSuspendsThenResumes() throws Exception { child("human"); }

    @Test(timeout = 6000) public void completedOldReadDoesNotOverwriteNewerState() throws Exception { staleRead(false); }
    @Test(timeout = 6000) public void rootDrainDoesNotOverwriteNewerState() throws Exception { staleRead(true); }

    static void staleRead(boolean drain) throws Exception {
        DefaultAgenticScope scope = DefaultAgenticScope.ephemeralAgenticScope();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DelayedResponse<String> old = new DelayedResponse<String>() {
            public boolean isDone() { return false; }
            public String blockingGet() { entered.countDown(); await(release); return "old"; }
        };
        scope.writeState("value", old);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> read = executor.submit(() -> {
                if (drain) { scope.rootCallEnded(new AgenticScopeRegistry("fixture"), null); return null; }
                return scope.readState("value");
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); scope.writeState("value", "new"); release.countDown();
            assertEquals(drain ? null : "old", read.get(2, TimeUnit.SECONDS)); assertEquals("new", scope.readState("value"));
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test(timeout = 6000) public void invocationBindingRestoresNestedContextAndClearsPooledWorkerAfterFailure() throws Exception {
        DefaultAgenticScope scope = DefaultAgenticScope.ephemeralAgenticScope(); scope.writeState("count", 1);
        DefaultAgenticScope other = DefaultAgenticScope.ephemeralAgenticScope(); other.writeState("count", 9);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(2, executor.submit(() -> new dev.langchain4j.agentic.internal.DeferredScopeContext.Frame(scope, "count").call(() -> {
                scope.writeState("count", 2); assertEquals(9, other.readState("count"));
                try {
                    new dev.langchain4j.agentic.internal.DeferredScopeContext.Frame(scope, "count").call(() -> {
                        assertEquals(2, scope.readState("count")); scope.writeState("count", 3); throw new IllegalStateException("nested");
                    });
                    fail("nested failure expected");
                } catch (IllegalStateException expected) { assertEquals("nested", expected.getMessage()); }
                return scope.readState("count");
            })).get(2, TimeUnit.SECONDS));
            assertEquals(1, executor.submit(() -> scope.readState("count")).get(2, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    static void child(String mode) throws Exception {
        Path log = Files.createTempFile(Paths.get("target"), "deferred-state-", ".log");
        Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(), "-Xverify:all", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), AgenticDeferredStateJdk8Test.class.getName(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("child self-wait/timeout: " + mode, process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(log), StandardCharsets.UTF_8), 0, process.exitValue());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS)); }
            Files.deleteIfExists(log);
        }
    }

    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        if (args[0].equals("human")) { human(); return; }
        if (args[0].startsWith("http") || args[0].startsWith("sse")) { retry(args[0].startsWith("sse"), args[0].endsWith("update")); return; }
        boolean write = args[0].equals("write");
        CountDownLatch consumer = new CountDownLatch(1), written = new CountDownLatch(1), release = new CountDownLatch(1);
        Object first = AgenticServices.nonAiAgentBuilder(scope -> {
            await(consumer);
            if (!write) return scope.readState("count", 0) + 1;
            scope.writeState("count", 5); scope.writeStateIfAbsent("count", 9);
            scope.writeStates(Collections.singletonMap("count", 6)); written.countDown(); await(release); return scope.readState("count", 0) + 1;
        }).async(true).outputKey("count").build();
        Object second = AgenticServices.nonAiAgentBuilder(scope -> { consumer.countDown(); return scope.readState("count"); }).outputKey("answer").build();
        UntypedAgent workflow = AgenticServices.sequenceBuilder().subAgents(first, second).outputKey("answer").build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> result = executor.submit(() -> workflow.invoke(Collections.singletonMap("count", 1)));
            if (write) {
                assertTrue(written.await(2, TimeUnit.SECONDS));
                try { result.get(150, TimeUnit.MILLISECONDS); fail("intermediate write escaped the pending operation"); }
                catch (TimeoutException expected) { }
                release.countDown();
            }
            assertEquals(write ? 7 : 2, result.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    static void retry(boolean streaming, boolean update) throws Exception {
        AgenticHttpJdk8Test fixture = new AgenticHttpJdk8Test(); fixture.start();
        try {
            fixture.mode = "failOnce"; AtomicInteger retries = new AtomicInteger();
            Object writer = streaming
                    ? AgenticServices.agentBuilder(SelfStreamingWriter.class).streamingChatModel(fixture.streaming()).build()
                    : AgenticServices.agentBuilder(SelfWriter.class).chatModel(fixture.model()).async(true).build();
            Object consumer = AgenticServices.nonAiAgentBuilder(scope -> "final:" + scope.readState("topic", ""))
                    .inputKey(String.class, "topic").outputKey("answer").build();
            UntypedAgent workflow = AgenticServices.sequenceBuilder().subAgents(writer, consumer).outputKey("answer")
                    .errorHandler(error -> {
                        assertEquals(1, retries.incrementAndGet());
                        if (update) error.agenticScope().writeState("topic", "retry-seed");
                        return dev.langchain4j.agentic.agent.ErrorRecoveryResult.retry();
                    }).build();
            assertEquals("final:你好", workflow.invoke(Collections.singletonMap("topic", "seed")));
            assertEquals(2, fixture.requests.size()); assertTrue(fixture.requests.get(1).toString().contains(update ? "retry-seed" : "Write seed")); fixture.awaitReleased();
        } finally { fixture.stop(); }
    }

    static void human() {
        AtomicInteger asks = new AtomicInteger(); AgenticScopePersister.setStore(null);
        Object human = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("approval")
                .responseProvider(scope -> { asks.incrementAndGet(); return new SuspendedResponse<String>("approval"); }).build();
        Object finish = AgenticServices.nonAiAgentBuilder(scope -> "approved:" + scope.readState("approval", ""))
                .inputKey(String.class, "approval").outputKey("answer").build();
        Suspendable workflow = AgenticServices.sequenceBuilder(Suspendable.class).subAgents(human, finish).outputKey("answer").build();
        ResultWithAgenticScope<String> suspended = workflow.run("session");
        assertTrue(suspended.suspended()); assertEquals(Collections.singleton("approval"), suspended.agenticScope().pendingResponseIds());
        ResultWithAgenticScope<String> done = suspended.completePendingResponse("approval", "yes");
        assertFalse(done.suspended()); assertEquals("approved:yes", done.result()); assertEquals(1, asks.get());
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
