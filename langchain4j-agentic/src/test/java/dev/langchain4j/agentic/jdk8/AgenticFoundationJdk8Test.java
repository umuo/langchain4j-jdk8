package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.internal.AgenticJdk8;
import dev.langchain4j.agentic.internal.UserMessageTransformer;
import dev.langchain4j.agentic.observability.HtmlReportGenerator;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeSerializer;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.V;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AgenticFoundationJdk8Test {
    public interface Greeter {
        @Agent(outputKey = "greeting")
        @dev.langchain4j.service.UserMessage("Greet {{name}}")
        String greet(@V("name") String name);
    }

    private static Map<String, Object> input(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    @Test(timeout=5000) public void nonAiInvocationRegistrationDoesNotEvaluateFailedAsyncOutput() throws Exception {
        dev.langchain4j.agentic.scope.DefaultAgenticScope scope = dev.langchain4j.agentic.scope.DefaultAgenticScope.ephemeralAgenticScope();
        dev.langchain4j.agentic.internal.AsyncResponse<Object> failed = new dev.langchain4j.agentic.internal.AsyncResponse<>(() -> {throw new IllegalStateException("already failed");});
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!failed.isDone() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(failed.isDone());
        scope.registerAgentInvocation(new dev.langchain4j.agentic.scope.AgentInvocation(String.class,"non-ai","non-ai",java.util.Collections.emptyMap(),failed),new Object());
        assertEquals(1,scope.agentInvocations().size());
        try {scope.agentInvocations().get(0).output();fail("failure must remain available to its consumer");}
        catch (IllegalStateException expected) {assertEquals("already failed",expected.getMessage());}
    }

    @Test public void runsOnActualJava8() {
        assertTrue(System.getProperty("java.version"), System.getProperty("java.version").startsWith("1.8."));
    }

    @Test(timeout = 10000) public void modelAgentReceivesBoundPrompt() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("你好，Ada")).build();
            }
        };
        Greeter greeter = AgenticServices.agentBuilder(Greeter.class).chatModel(model).build();
        UntypedAgent sequence = AgenticServices.sequenceBuilder().subAgents(greeter).outputKey("greeting").build();
        assertEquals("你好，Ada", sequence.invoke(input("name", "Ada")));
        assertTrue(captured.get().messages().toString().contains("Greet Ada"));
    }

    private static UntypedAgent numberSequence() {
        Object doubleIt = AgenticServices.nonAiAgentBuilder(scope -> scope.readState("n", 0) * 2)
                .name("doubleIt").inputKey(Integer.class, "n").outputKey("doubled").build();
        Object format = AgenticServices.nonAiAgentBuilder(scope -> "结果=" + scope.readState("doubled", 0))
                .name("format").inputKey(Integer.class, "doubled").outputKey("answer").build();
        return AgenticServices.sequenceBuilder().subAgents(doubleIt, format).outputKey("answer").build();
    }

    @Test(timeout = 10000) public void sequenceConvertsInputAndPassesState() {
        ResultWithAgenticScope<String> result = numberSequence().invokeWithAgenticScope(input("n", "21"));
        assertEquals("结果=42", result.result());
        assertEquals(21, result.agenticScope().readState("n"));
        assertEquals(42, result.agenticScope().readState("doubled"));
        assertEquals(2, result.agenticScope().agentInvocations().size());
        assertEquals("doubleIt", result.agenticScope().agentInvocations().get(0).agentName());
        assertEquals("format", result.agenticScope().agentInvocations().get(1).agentName());
    }

    @Test(timeout = 10000) public void completedScopeRoundTripsWithInvocationRecords() {
        ResultWithAgenticScope<String> result = numberSequence().invokeWithAgenticScope(input("n", "21"));
        DefaultAgenticScope original = (DefaultAgenticScope) result.agenticScope();
        DefaultAgenticScope restored = AgenticScopeSerializer.fromJson(AgenticScopeSerializer.toJson(original));
        assertEquals(original.memoryId(), restored.memoryId());
        assertEquals("结果=42", restored.readState("answer"));
        assertEquals(original.agentInvocations(), restored.agentInvocations());
    }

    @Test(timeout = 10000) public void loopTerminatesFromStateAndIterationLimit() {
        Object increment = AgenticServices.nonAiAgentBuilder(scope -> scope.readState("count", 0) + 1)
                .outputKey("count").build();
        UntypedAgent loop = AgenticServices.loopBuilder().subAgents(increment).maxIterations(20)
                .exitCondition(scope -> scope.readState("count", 0) >= 3).outputKey("count").build();
        assertEquals(3, loop.invoke(input("count", 0)));
        UntypedAgent bounded = AgenticServices.loopBuilder().subAgents(increment).maxIterations(2)
                .exitCondition(scope -> false).outputKey("count").build();
        assertEquals(2, bounded.invoke(input("count", 0)));
    }

    @Test(timeout = 10000) public void conditionalRunsOnlyMatchingBranch() {
        AtomicInteger rejectedCalls = new AtomicInteger();
        Object yes = AgenticServices.nonAiAgentBuilder(scope -> "yes").outputKey("answer").build();
        Object no = AgenticServices.nonAiAgentBuilder(scope -> { rejectedCalls.incrementAndGet(); return "no"; })
                .outputKey("answer").build();
        UntypedAgent conditional = AgenticServices.conditionalBuilder()
                .subAgents(scope -> scope.readState("allowed", false), yes)
                .subAgents(scope -> !scope.readState("allowed", false), no).outputKey("answer").build();
        assertEquals("yes", conditional.invoke(input("allowed", true)));
        assertEquals(0, rejectedCalls.get());
        assertEquals("no", conditional.invoke(input("allowed", false)));
        assertEquals(1, rejectedCalls.get());
    }

    @Test(timeout = 10000) public void parallelBranchesOverlapAndDoNotCloseCallerExecutor() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        try {
            Object left = AgenticServices.nonAiAgentBuilder(scope -> parallelValue(entered, "left"))
                    .outputKey("left").build();
            Object right = AgenticServices.nonAiAgentBuilder(scope -> parallelValue(entered, "right"))
                    .outputKey("right").build();
            UntypedAgent parallel = AgenticServices.parallelBuilder().subAgents(left, right).executor(executor)
                    .output(scope -> scope.readState("left", "") + "/" + scope.readState("right", "")).build();
            assertEquals("left/right", parallel.invoke(Collections.emptyMap()));
            assertEquals("still alive", executor.submit(() -> "still alive").get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static String parallelValue(CountDownLatch entered, String value) {
        entered.countDown();
        try {
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("branches did not overlap");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        return value;
    }

    @Test(timeout = 10000) public void errorRecoveryResultContinuesDataflow() {
        Object fail = AgenticServices.nonAiAgentBuilder(scope -> { throw new IllegalStateException("synthetic"); })
                .name("failure").outputKey("value").build();
        Object next = AgenticServices.nonAiAgentBuilder(scope -> scope.readState("value", "") + "!")
                .inputKey(String.class, "value").outputKey("answer").build();
        UntypedAgent sequence = AgenticServices.sequenceBuilder().subAgents(fail, next).outputKey("answer")
                .errorHandler(error -> ErrorRecoveryResult.result("recovered")).build();
        assertEquals("recovered!", sequence.invoke(Collections.emptyMap()));
        assertEquals("same", ErrorRecoveryResult.result("same").result());
        assertEquals(ErrorRecoveryResult.result("same"), ErrorRecoveryResult.result("same"));
    }

    @Test(timeout = 10000) public void missingInputDoesNotInvokeAgent() {
        AtomicInteger calls = new AtomicInteger();
        Object agent = AgenticServices.nonAiAgentBuilder(scope -> calls.incrementAndGet())
                .inputKey(String.class, "required").outputKey("answer").build();
        UntypedAgent sequence = AgenticServices.sequenceBuilder().subAgents(agent).outputKey("answer").build();
        try { sequence.invoke(Collections.emptyMap()); fail("expected missing input"); }
        catch (MissingArgumentException expected) { assertTrue(expected.getMessage().contains("required")); }
        assertEquals(0, calls.get());
    }

    @Test public void deferredResponseSurvivesPersistenceAndCompletesOnce() {
        DefaultAgenticScope scope = DefaultAgenticScope.ephemeralAgenticScope();
        scope.writeState("approval", new dev.langchain4j.agentic.internal.PendingResponse<String>("approval-1"));
        DefaultAgenticScope restored = AgenticScopeSerializer.fromJson(AgenticScopeSerializer.toJson(scope));
        assertEquals(Collections.singleton("approval-1"), restored.pendingResponseIds());
        assertTrue(restored.completePendingResponse("approval-1", "approved"));
        assertEquals("approved", restored.readState("approval"));
        assertFalse(restored.completePendingResponse("approval-1", "duplicate"));
        assertTrue(restored.pendingResponseIds().isEmpty());
    }

    @Test public void formerRecordRetainsJsonConstructorAndValueSemantics() {
        ErrorRecoveryResult original = ErrorRecoveryResult.result("recovered");
        String json = dev.langchain4j.internal.Json.toJson(original);
        ErrorRecoveryResult restored = dev.langchain4j.internal.Json.fromJson(json, ErrorRecoveryResult.class);
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
        assertEquals("recovered", restored.result());
        assertEquals(ErrorRecoveryResult.Type.RETURN_RESULT, restored.type());
    }

    @Test public void messageTransformerChangesOnlyLatestUserMessage() {
        UserMessage first = UserMessage.from("first");
        UserMessage last = UserMessage.from("last");
        ChatRequest request = ChatRequest.builder().messages(Arrays.<ChatMessage>asList(SystemMessage.from("system"), first, AiMessage.from("reply"), last)).build();
        UserMessageTransformer transformer = (text, memoryId) -> memoryId + ":" + text;
        ChatRequest changed = transformer.apply(request, "session");
        assertSame(first, changed.messages().get(1));
        assertEquals("session:last", ((UserMessage) changed.messages().get(3)).singleText());
        assertSame(last, request.messages().get(3));
    }

    @Test public void scopeBlankAndNullStateSemantics() {
        AgenticScope scope = DefaultAgenticScope.ephemeralAgenticScope();
        scope.writeState("key", "\u2003");
        assertFalse(scope.hasState("key"));
        scope.writeStateIfAbsent("key", "值");
        scope.writeStateIfAbsent("key", "ignored");
        assertEquals("值", scope.readState("key"));
        scope.writeState("key", null);
        assertFalse(scope.hasState("key"));
    }

    @Test public void topologyReportWritesUtf8AndTruncates() throws Exception {
        Path file = Files.createTempFile("agentic-report-", ".html");
        try {
            UntypedAgent agent = numberSequence();
            HtmlReportGenerator.generateTopology(agent, file);
            assertEquals(HtmlReportGenerator.generateTopology(agent), new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            AgenticJdk8.writeString(file, "中文");
            assertArrayEquals("中文".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            try { AgenticJdk8.writeString(file, "\uD800"); fail("malformed UTF-16 must fail"); }
            catch (java.nio.charset.CharacterCodingException expected) { }
            assertArrayEquals("中文".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        } finally { Files.deleteIfExists(file); }
    }
}
