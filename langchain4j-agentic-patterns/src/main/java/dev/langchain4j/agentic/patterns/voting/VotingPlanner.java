package dev.langchain4j.agentic.patterns.voting;

import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.InitPlanningContext;
import dev.langchain4j.agentic.planner.PlanningContext;
import dev.langchain4j.agentic.planner.Planner;

import dev.langchain4j.agentic.scope.AgentInvocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.internal.DelayedResponse;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.AgenticSystemSuspendedException;

public class VotingPlanner implements Planner {

    private final VotingStrategy strategy;

    private List<AgentInstance> subagents;
    private final Set<String> completed = new LinkedHashSet<>();
    private final Map<String, Object> votes = new LinkedHashMap<>();
    private final Map<String, AgentInstance> voters = new LinkedHashMap<>();

    public VotingPlanner() {
        this(VotingStrategy.majority());
    }

    public VotingPlanner(VotingStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public void init(InitPlanningContext initPlanningContext) {
        this.subagents = initPlanningContext.subagents();
        subagents.forEach(agent -> voters.put(agent.agentId(), agent));
    }

    @Override
    public Action firstAction(PlanningContext planningContext) {
        List<AgentInstance> pending = new ArrayList<>();
        subagents.stream().filter(agent -> !completed.contains(agent.agentId())).forEach(pending::add);
        return pending.isEmpty() ? aggregate(planningContext.agenticScope()) : call(pending);
    }

    @Override
    public Action nextAction(PlanningContext planningContext) {
        AgentInvocation invocation = planningContext.previousAgentInvocation();
        // Count skipped voters, but retain no vote for them. A controlled async failure is not
        // completed: resolve it before publishing either the vote or the completion marker.
        if (!invocation.skipped()) {
            Object value = dev.langchain4j.agentic.internal.AgenticJdk8.invocationOutput(planningContext.agenticScope(), invocation);
            votes.put(invocation.agentId(), value);
        }
        completed.add(invocation.agentId());
        return completed.size() < subagents.size() ? noOp() : aggregate(planningContext.agenticScope());
    }

    private Action aggregate(AgenticScope scope) {
        if (votes.isEmpty()) return done();
        List<Object> resolved = new ArrayList<>();
        votes.forEach((id, original) -> {
            Object value = original;
            if (value instanceof DelayedResponse<?>) {
                AgentInstance agent = voters.get(id);
                String key = agent == null ? null : agent.outputKey();
                if (key != null && scope.hasState(key)) value = scope.readState(key);
                while (value instanceof DelayedResponse<?>) {
                    if (value instanceof SuspendedResponse<?> && !((DelayedResponse<?>) value).isDone()) {
                        throw new AgenticSystemSuspendedException(scope);
                    }
                    Object next = ((DelayedResponse<?>) value).blockingGet();
                    if (next == value) throw new IllegalStateException("Self-referencing vote: " + id);
                    value = next;
                }
            }
            resolved.add(value);
        });
        return done(strategy.aggregate(resolved));
    }

    @Override
    public Map<String, Object> executionState() {
        return dev.langchain4j.internal.Jdk8.map("completed", new ArrayList<>(completed), "votes", new LinkedHashMap<>(votes));
    }

    @Override
    public void restoreExecutionState(Map<String, Object> state) {
        Object savedCompleted = state.get("completed");
        if (savedCompleted instanceof List<?>) ((List<?>) savedCompleted).forEach(id -> completed.add(id.toString()));
        Object savedVotes = state.get("votes");
        if (savedVotes instanceof Map<?, ?>) ((Map<?, ?>) savedVotes).forEach((id, value) -> votes.put(id.toString(), value));
    }

    @Override
    public AgenticSystemTopology topology() {
        return AgenticSystemTopology.PARALLEL;
    }
}
