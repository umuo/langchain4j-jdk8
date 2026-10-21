package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.agentic.supervisor.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class AgenticAsyncPlanningJdk8Test {
    public interface Workflow extends AgenticScopeAccess { @Agent ResultWithAgenticScope<String> run(@MemoryId String id); }
    public interface Supervisor extends AgenticScopeAccess { ResultWithAgenticScope<String> invoke(@MemoryId String id, @V("request") String request); }
    @Test(timeout = 12000) public void parallelResumeRunsOnlyTheSuspendedConsumer() throws Exception { child("parallel"); }
    @Test(timeout = 12000) public void nestedWorkflowResumesAtItsIncompleteAsyncStep() throws Exception { child("nested"); }
    @Test(timeout = 12000) public void asyncSupervisorAwaitsHumanAndPlansWithItsActualAnswer() throws Exception { child("supervisor"); }

    @Test(timeout = 12000) public void repeatedParallelAwaitDoesNotRerunAnyStep() throws Exception { child("parallel-repeat"); }
    @Test(timeout = 12000) public void twoParallelHumanResponsesStayVisibleAcrossPartialCompletion() throws Exception { child("parallel-two"); }
    @Test(timeout = 12000) public void supervisorWaitsForAsyncQueryBeforeNextPlan() throws Exception { child("supervisor-query"); }

    static void child(String mode) throws Exception {
        Path log = Files.createTempFile(Paths.get("target"), "async-planning-", ".log");
        Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(), "-Xverify:all", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), AgenticAsyncPlanningJdk8Test.class.getName(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("child timed out: " + mode, process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(log), StandardCharsets.UTF_8), 0, process.exitValue());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS)); }
            Files.deleteIfExists(log);
        }
    }
    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8.")); AgenticScopePersister.setStore(null);
        if (args[0].startsWith("supervisor")) { supervisor(args[0].equals("supervisor-query")); return; }
        if (args[0].equals("parallel-two")) { twoInputs(); return; }
        AtomicInteger asks = new AtomicInteger(), effects = new AtomicInteger(), attempts = new AtomicInteger(), errors = new AtomicInteger();
        CountDownLatch consumer = new CountDownLatch(1);
        Object human = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("approval").responseProvider(scope -> {
            await(consumer); asks.incrementAndGet(); return new SuspendedResponse<String>("approval");
        }).build();
        Object finish = AgenticServices.nonAiAgentBuilder(scope -> {
            attempts.incrementAndGet(); consumer.countDown(); return "approved:" + scope.readState("approval", "");
        }).name("consumer").async(true).outputKey("reply").build();
        Object effect = AgenticServices.nonAiAgentBuilder(scope -> { effects.incrementAndGet(); return "kept"; }).name("effect").outputKey("side").build();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Workflow workflow;
            if (args[0].startsWith("parallel")) {
                workflow = AgenticServices.parallelBuilder(Workflow.class).subAgents(human, finish, effect).executor(executor)
                        .output(scope -> scope.readState("reply", "") + "|" + scope.readState("side", ""))
                        .errorHandler(error -> { errors.incrementAndGet(); return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException(); }).build();
            } else {
                UntypedAgent nested = AgenticServices.sequenceBuilder().subAgents(human, finish).name("nested").outputKey("reply").build();
                Object tail = AgenticServices.nonAiAgentBuilder(scope -> "done:" + scope.readState("reply", ""))
                        .inputKey(String.class, "reply").outputKey("answer").build();
                workflow = AgenticServices.sequenceBuilder(Workflow.class).subAgents(effect, nested, tail).outputKey("answer")
                        .errorHandler(error -> { errors.incrementAndGet(); return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException(); }).build();
            }
            ResultWithAgenticScope<String> paused = workflow.run("session"); assertTrue(paused.suspended()); assertEquals(0, errors.get());
            if (args[0].contains("repeat")) {
                assertTrue(workflow.run("session").suspended()); assertEquals(1, asks.get()); assertEquals(1, effects.get()); assertEquals(1, attempts.get());
            }
            ResultWithAgenticScope<String> done = paused.completePendingResponse("approval", "yes");
            assertFalse(done.suspended()); assertEquals(args[0].startsWith("parallel") ? "approved:yes|kept" : "done:approved:yes", done.result());
            assertEquals(1, asks.get()); assertEquals(1, effects.get()); assertEquals(2, attempts.get()); assertEquals(0, errors.get());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }
    static void supervisor(boolean asyncQuery) {
        AtomicInteger calls = new AtomicInteger(), asks = new AtomicInteger(), errors = new AtomicInteger();
        List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        List<String> replies = Arrays.asList("{\"agentName\":\"askUser\",\"arguments\":{\"question\":\"Your name?\"}}",
                "{\"agentName\":\"query\",\"arguments\":{}}", "{\"agentName\":\"done\",\"arguments\":{}}");
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                requests.add(request); int index = calls.getAndIncrement(); assertTrue("unexpected planner call", index < replies.size());
                return ChatResponse.builder().aiMessage(AiMessage.from(replies.get(index))).build();
            }
        };
        Object human = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("name").responseProvider(scope -> {
            asks.incrementAndGet(); return new SuspendedResponse<String>("name");
        }).build();
        Object query = AgenticServices.nonAiAgentBuilder(scope -> {
            if (asyncQuery) Thread.sleep(50);
            return "queried:" + scope.readState("name", "");
        }).name("query").async(asyncQuery).outputKey("query").build();
        Supervisor supervisor = AgenticServices.supervisorBuilder(Supervisor.class).chatModel(model)
                .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY).responseStrategy(SupervisorResponseStrategy.LAST)
                .subAgents(human, query).errorHandler(error -> { errors.incrementAndGet(); return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException(); }).build();
        ResultWithAgenticScope<String> paused = supervisor.invoke("session", "Greet the user");
        assertTrue(paused.suspended()); assertEquals(1, calls.get()); assertEquals(1, asks.get());
        assertTrue(supervisor.invoke("session", "Greet the user").suspended()); assertEquals(1, calls.get());
        ResultWithAgenticScope<String> done = paused.completePendingResponse("name", "Alice");
        assertFalse(done.suspended()); assertEquals("queried:Alice", done.result()); assertEquals(3, calls.get()); assertEquals(0, errors.get());
        assertTrue("actual human response must reach resumed planner", requests.get(1).messages().toString().contains("Alice"));
        assertTrue("actual query result must reach the next plan", requests.get(2).messages().toString().contains("queried:Alice"));
    }
    static void twoInputs() throws Exception {
        CountDownLatch startOne = new CountDownLatch(1), startTwo = new CountDownLatch(1);
        AtomicInteger askOne = new AtomicInteger(), askTwo = new AtomicInteger(), readOne = new AtomicInteger(), readTwo = new AtomicInteger(), effects = new AtomicInteger();
        Object humanOne = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("one").responseProvider(scope -> {
            await(startOne); askOne.incrementAndGet(); return new SuspendedResponse<String>("input-one");
        }).build();
        Object humanTwo = AgenticServices.humanInTheLoopBuilder().async(true).outputKey("two").responseProvider(scope -> {
            await(startTwo); askTwo.incrementAndGet(); return new SuspendedResponse<String>("input-two");
        }).build();
        Object one = AgenticServices.nonAiAgentBuilder(scope -> { readOne.incrementAndGet(); startOne.countDown(); return "got:" + scope.readState("one", ""); })
                .name("consume-one").async(true).outputKey("a").build();
        Object two = AgenticServices.nonAiAgentBuilder(scope -> { readTwo.incrementAndGet(); startTwo.countDown(); return "got:" + scope.readState("two", ""); })
                .name("consume-two").async(true).outputKey("b").build();
        Object side = AgenticServices.nonAiAgentBuilder(scope -> { effects.incrementAndGet(); return "kept"; }).name("effect").outputKey("side").build();
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Workflow workflow = AgenticServices.parallelBuilder(Workflow.class).subAgents(humanOne, one, humanTwo, two, side).executor(executor)
                    .output(scope -> scope.readState("a", "") + "|" + scope.readState("b", "")).build();
            ResultWithAgenticScope<String> paused = workflow.run("session"); assertTrue(paused.suspended());
            assertEquals(new HashSet<>(Arrays.asList("input-one", "input-two")), paused.agenticScope().pendingResponseIds());
            ResultWithAgenticScope<String> partly = paused.completePendingResponse("input-one", "A"); assertTrue(partly.suspended());
            assertEquals(Collections.singleton("input-two"), partly.agenticScope().pendingResponseIds());
            assertEquals(1, readOne.get()); assertEquals(1, readTwo.get()); assertEquals(1, effects.get());
            ResultWithAgenticScope<String> done = partly.completePendingResponse("input-two", "B"); assertFalse(done.suspended());
            assertEquals("got:A|got:B", done.result()); assertEquals(1, askOne.get()); assertEquals(1, askTwo.get());
            assertEquals(2, readOne.get()); assertEquals(2, readTwo.get()); assertEquals(1, effects.get());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
