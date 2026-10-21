package dev.langchain4j.agentic.patterns.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.patterns.bdi.*;
import dev.langchain4j.agentic.patterns.blackboard.*;
import dev.langchain4j.agentic.patterns.debate.*;
import dev.langchain4j.agentic.patterns.decisionrouter.*;
import dev.langchain4j.agentic.patterns.goap.*;
import dev.langchain4j.agentic.patterns.p2p.*;
import dev.langchain4j.agentic.patterns.voting.*;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.scope.AgenticScopePersister;
import dev.langchain4j.service.V;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PatternsFoundationJdk8Test {
    @Before public void java8() { assertTrue(System.getProperty("java.version").startsWith("1.8.")); AgenticScopePersister.setStore(null); }
    public interface ApprovalRouter extends dev.langchain4j.agentic.scope.AgenticScopeAccess { @Agent dev.langchain4j.agentic.scope.ResultWithAgenticScope<String> route(@dev.langchain4j.service.MemoryId String id, @V("request") String request); }
    public interface Router { @Agent String route(@V("request") String request); }
    public static class First {
        @Agent(outputKey="mid") public String first(@V("input") String input) { return input + ":first"; }
    }
    public static class Last {
        @Agent(outputKey="answer") public String last(@V("mid") String mid) { return mid + ":last"; }
    }
    private void dependencyWorkflow(Supplier<Planner> planner) {
        UntypedAgent system = AgenticServices.plannerBuilder().subAgents(new First(), new Last()).planner(planner).outputKey("answer").build();
        assertEquals("start:first:last", system.invoke(Collections.<String,Object>singletonMap("input", "start")));
    }
    @Test(timeout=5000) public void blackboardExecutesDataDependencies() { dependencyWorkflow(BlackboardPlanner::new); }
    @Test(timeout=5000) public void goapFindsPathToOutput() { dependencyWorkflow(GoalOrientedPlanner::new); }
    @Test(timeout=5000) public void p2pActivatesDownstreamFromNewState() { dependencyWorkflow(() -> new P2PPlanner(scope -> scope.hasState("answer"))); }
    @Test(timeout=5000) public void bdiCompletesIntention() {
        Desire desire = Desire.of("answer", 1, scope -> true, scope -> scope.hasState("answer"), First.class, Last.class);
        dependencyWorkflow(() -> new BDIPlanner(Collections.singletonList(desire)));
    }
    @Test(timeout=5000) public void votingAggregatesParallelOutputs() {
        Object one = AgenticServices.nonAiAgentBuilder(scope -> 2).name("one").outputKey("one").build();
        Object two = AgenticServices.nonAiAgentBuilder(scope -> 4).name("two").outputKey("two").build();
        UntypedAgent system = AgenticServices.plannerBuilder().subAgents(one,two).planner(() -> new VotingPlanner(VotingStrategy.average())).build();
        assertEquals(3.0, (Double)system.invoke(Collections.emptyMap()), 0.0);
    }
    @Test(timeout=5000) public void debateConvergesAndCallsJudgeWithContext() {
        AtomicInteger judges = new AtomicInteger();
        Object left = AgenticServices.nonAiAgentBuilder(scope -> "ACCEPT").name("left").outputKey("left").build();
        Object right = AgenticServices.nonAiAgentBuilder(scope -> "ACCEPT").name("right").outputKey("right").build();
        Object judge = AgenticServices.nonAiAgentBuilder(scope -> {
            judges.incrementAndGet(); String context = scope.readState("debateContext", "");
            assertTrue(context.contains("left")); assertTrue(context.contains("right")); return "verdict";
        }).name("judge").outputKey("answer").build();
        UntypedAgent system = AgenticServices.plannerBuilder().subAgents(left,right,judge).planner(DebatePlanner::new).build();
        assertEquals("verdict",system.invoke(Collections.emptyMap())); assertEquals(1,judges.get());
    }
    @Test(timeout=5000) public void decisionRouterSingleCandidateAvoidsModelCall() {
        Object candidate = AgenticServices.nonAiAgentBuilder(scope -> "selected").name("candidate").outputKey("answer").build();
        Router system = AgenticServices.plannerBuilder(Router.class).subAgents(candidate).planner(() -> new DecisionRouterPlanner(request -> { throw new AssertionError("single candidate must not call model"); })).build();
        assertEquals("selected",system.route("request"));
    }
    @Test(timeout=5000) public void decisionRouterRecomputesHumanResultAfterSuspension() {
        AtomicInteger asks = new AtomicInteger();
        Object human = AgenticServices.humanInTheLoopBuilder().outputKey("approval").responseProvider(scope -> {
            asks.incrementAndGet(); return new dev.langchain4j.agentic.internal.SuspendedResponse<String>("approval");
        }).build();
        ApprovalRouter router = AgenticServices.plannerBuilder(ApprovalRouter.class).subAgents(human)
                .planner(() -> new DecisionRouterPlanner(request -> { throw new AssertionError("single candidate must not call model"); })).build();
        dev.langchain4j.agentic.scope.ResultWithAgenticScope<String> paused = router.route("session", "request");
        assertTrue(paused.suspended());
        dev.langchain4j.agentic.scope.ResultWithAgenticScope<String> done = paused.completePendingResponse("approval","approved");
        assertFalse(done.suspended()); assertEquals("approved",done.result()); assertEquals(1,asks.get());
    }
    @Test public void desireRetainsRecordValueAndFactoryContracts() {
        java.util.function.Predicate<dev.langchain4j.agentic.scope.AgenticScope> yes = scope -> true;
        Desire first = Desire.of("goal",3,yes,yes,First.class);
        Desire second = new Desire("goal",3,yes,yes,Collections.<Class<?>>singletonList(First.class));
        assertEquals(first,second); assertEquals(first.hashCode(),second.hashCode());
        try { first.agentTypes().add(Last.class); fail("factory list must be immutable"); } catch (UnsupportedOperationException expected) {}
        try { new Desire("empty",0,yes,yes,Collections.emptyList()); fail("empty intention must be rejected"); } catch (IllegalArgumentException expected) {}
    }
}
