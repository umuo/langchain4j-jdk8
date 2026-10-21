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

    public interface PlainSuspendable extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    @Test(timeout = 12000) public void terminalHumanDoesNotAskAgainAfterResponse() throws Exception { child("terminal"); }
    @Test(timeout = 12000) public void terminalAsyncHumanSuspendsThenReturnsResponse() throws Exception { child("terminal-async"); }
    @Test(timeout = 12000) public void terminalHumanRepeatedAwaitDoesNotInvokeProviderAgain() throws Exception { child("terminal-repeat"); }
    @Test(timeout = 12000) public void plainTerminalHumanUsesSuspensionExceptionAndResumes() throws Exception { child("terminal-plain"); }
    @Test(timeout = 12000) public void asyncConsumerSuspensionBypassesFailureRecovery() throws Exception { child("async-consumer"); }

    @Test(timeout = 12000) public void terminalAsyncRepeatedAwaitDoesNotAskAgain() throws Exception { child("terminal-async-repeat"); }
    @Test(timeout = 12000) public void asyncSuspensionDoesNotCompensateOrReplayCompletedHttpTool() throws Exception { child("terminal-tools"); }
    @Test(timeout = 15000) public void terminalCheckpointResumesInAnotherActualJava8Process() throws Exception {
        Path directory = Files.createTempDirectory(Paths.get("target"), "terminal-checkpoint-");
        try {
            child("terminal-write", directory.toAbsolutePath().toString());
            child("terminal-read", directory.toAbsolutePath().toString());
            assertEquals(Collections.singletonList("ask"), Files.readAllLines(directory.resolve("calls.txt"), StandardCharsets.UTF_8));
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(file);
            }
        }
    }

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

    static void child(String mode, String... extra) throws Exception {
        Path log = Files.createTempFile(Paths.get("target"), "deferred-state-", ".log");
        List<String> command = new ArrayList<>(Arrays.asList(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(), "-Xverify:all", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), AgenticDeferredStateJdk8Test.class.getName(), mode));
        Collections.addAll(command, extra);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
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
        if (args[0].equals("terminal-tools")) { terminalTools(); return; }
        if (args[0].equals("terminal-write") || args[0].equals("terminal-read")) { terminalDurable(args[0], Paths.get(args[1])); return; }
        if (args[0].startsWith("terminal")) { terminalHuman(args[0]); return; }
        if (args[0].equals("async-consumer")) { asyncConsumer(); return; }
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

    static void terminalHuman(String mode) {
        AgenticScopePersister.setStore(null); AtomicInteger asks = new AtomicInteger(), errors = new AtomicInteger();
        Object human = AgenticServices.humanInTheLoopBuilder().async(mode.contains("async")).outputKey("approval")
                .responseProvider(scope -> { asks.incrementAndGet(); return new SuspendedResponse<String>("approval"); }).build();
        if (mode.contains("plain")) {
            PlainSuspendable workflow = AgenticServices.sequenceBuilder(PlainSuspendable.class).subAgents(human).outputKey("approval").build();
            try { workflow.run("session"); fail("must suspend"); } catch (AgenticSystemSuspendedException expected) { }
            assertTrue(workflow.getAgenticScope("session").completePendingResponse("approval", "yes"));
            assertEquals("yes", workflow.run("session"));
        } else {
            Suspendable workflow = AgenticServices.sequenceBuilder(Suspendable.class).subAgents(human).outputKey("approval")
                    .errorHandler(error -> { errors.incrementAndGet(); return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException(); }).build();
            ResultWithAgenticScope<String> suspended = workflow.run("session"); assertTrue(suspended.suspended());
            if (mode.contains("repeat")) { assertTrue(workflow.run("session").suspended()); assertEquals(1, asks.get()); }
            ResultWithAgenticScope<String> done = suspended.completePendingResponse("approval", "yes");
            assertFalse(done.suspended()); assertEquals("yes", done.result()); assertEquals(0, errors.get());
            assertTrue(done.agenticScope().state().keySet().stream().noneMatch(k -> k.startsWith("__planner_state_")));
        }
        assertEquals(1, asks.get());
    }

    static void asyncConsumer() {
        AgenticScopePersister.setStore(null); AtomicInteger asks = new AtomicInteger(), errors = new AtomicInteger(), attempts = new AtomicInteger();
        CountDownLatch consumer = new CountDownLatch(1);
        Object human = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("approval").responseProvider(scope -> {
            await(consumer); asks.incrementAndGet(); return new SuspendedResponse<String>("approval");
        }).build();
        Object finish = AgenticServices.nonAiAgentBuilder(scope -> {
            attempts.incrementAndGet(); consumer.countDown(); return "approved:" + scope.readState("approval", "");
        }).async(true).outputKey("answer").build();
        Suspendable workflow = AgenticServices.sequenceBuilder(Suspendable.class).subAgents(human, finish).outputKey("answer")
                .errorHandler(error -> { errors.incrementAndGet(); return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException(); }).build();
        ResultWithAgenticScope<String> suspended = workflow.run("session"); assertTrue(suspended.suspended()); assertEquals(0, errors.get());
        ResultWithAgenticScope<String> done = suspended.completePendingResponse("approval", "yes");
        assertFalse(done.suspended()); assertEquals("approved:yes", done.result()); assertEquals(1, asks.get());
        assertEquals(2, attempts.get()); assertEquals(0, errors.get());
    }

    static void terminalDurable(String mode, Path directory) throws Exception {
        AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.FileStore(directory));
        Object human = AgenticServices.humanInTheLoopBuilder().outputKey("approval").responseProvider(scope -> {
            if (mode.equals("terminal-read")) throw new AssertionError("persisted terminal step was replayed");
            try { Files.write(directory.resolve("calls.txt"), "ask\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
            return new SuspendedResponse<String>("approval");
        }).build();
        Suspendable workflow = AgenticServices.sequenceBuilder(Suspendable.class).subAgents(human).outputKey("approval").build();
        if (mode.equals("terminal-write")) {
            ResultWithAgenticScope<String> result = workflow.run("session"); assertTrue(result.suspended());
            assertTrue(result.agenticScope().state().values().stream().anyMatch(v -> v instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) v).get("__terminalAwaiting"))));
        } else {
            AgenticScope scope = workflow.getAgenticScope("session"); assertNotNull(scope);
            assertTrue(scope.completePendingResponse("approval", "yes"));
            ResultWithAgenticScope<String> done = workflow.run("session"); assertFalse(done.suspended()); assertEquals("yes", done.result());
            assertTrue(done.agenticScope().state().keySet().stream().noneMatch(k -> k.startsWith("__planner_state_")));
        }
    }

    static void terminalTools() throws Exception {
        AgenticScopePersister.setStore(null); AgenticHttpJdk8Test fixture = new AgenticHttpJdk8Test(); fixture.start();
        try {
            fixture.mode = "tool"; AgenticHttpJdk8Test.Weather weather = new AgenticHttpJdk8Test.Weather();
            Object writer = AgenticServices.agentBuilder(AgenticHttpJdk8Test.Writer.class).chatModel(fixture.model()).tools(weather).build();
            CountDownLatch consumer = new CountDownLatch(1); AtomicInteger asks = new AtomicInteger(), errors = new AtomicInteger();
            Object human = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("approval").responseProvider(scope -> {
                await(consumer); asks.incrementAndGet(); return new SuspendedResponse<String>("approval");
            }).build();
            Object finish = AgenticServices.nonAiAgentBuilder(scope -> { consumer.countDown(); return "approved:" + scope.readState("approval", ""); })
                    .async(true).outputKey("answer").build();
            Suspendable workflow = AgenticServices.sequenceBuilder(Suspendable.class).subAgents(writer, human, finish).outputKey("answer")
                    .beforeCall(scope -> scope.writeState("topic", "云")).compensateOnError(true)
                    .errorHandler(error -> { errors.incrementAndGet(); return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException(); }).build();
            ResultWithAgenticScope<String> pending = workflow.run("session"); assertTrue(pending.suspended());
            assertEquals(1, weather.calls.get()); assertEquals(0, weather.undos.get()); assertEquals(0, errors.get());
            ResultWithAgenticScope<String> done = pending.completePendingResponse("approval", "yes");
            assertFalse(done.suspended()); assertEquals("approved:yes", done.result());
            assertEquals(1, asks.get()); assertEquals(1, weather.calls.get()); assertEquals(0, weather.undos.get());
            assertEquals(0, errors.get()); assertEquals(2, fixture.requests.size()); fixture.awaitReleased();
        } finally { fixture.stop(); }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
