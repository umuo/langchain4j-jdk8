package dev.langchain4j.agentic.patterns.debate;

import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.InitPlanningContext;
import dev.langchain4j.agentic.planner.PlanningContext;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import dev.langchain4j.agentic.internal.DelayedResponse;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.AgenticSystemSuspendedException;
import java.util.stream.Collectors;

/**
 * A debate planner that orchestrates iterative adversarial rounds among debater agents.
 * <p>
 * Debaters generate independent positions in parallel, then enter critique rounds where they see
 * each other's reasoning (via a {@code "debateContext"} scope key) and can revise their positions.
 * Rounds continue until the {@link ConvergenceStrategy} reports convergence or {@code maxRounds}
 * is reached, at which point the judge agent (the last registered subagent) renders a final verdict.
 */
public class DebatePlanner implements Planner {

    private static final Logger LOG = LoggerFactory.getLogger(DebatePlanner.class);

    public static final String DEBATE_CONTEXT_KEY = "debateContext";

    private static final int DEFAULT_MAX_ROUNDS = 3;

    private final int maxRounds;
    private final ConvergenceStrategy convergenceStrategy;

    private List<AgentInstance> debaters;
    private AgentInstance judge;

    private int currentRound = 1;
    private final Set<String> completedInRound = new LinkedHashSet<>();
    private final Map<String, Object> lastDebatersMessages = new HashMap<>();
    private boolean judgePhase = false;
    private boolean judgeCompleted;
    private Object judgeOutput;

    public DebatePlanner() {
        this(DEFAULT_MAX_ROUNDS, ConvergenceStrategy.unanimous());
    }

    public DebatePlanner(int maxRounds) {
        this(maxRounds, ConvergenceStrategy.unanimous());
    }

    public DebatePlanner(ConvergenceStrategy convergenceStrategy) {
        this(DEFAULT_MAX_ROUNDS, convergenceStrategy);
    }

    public DebatePlanner(int maxRounds, ConvergenceStrategy convergenceStrategy) {
        this.maxRounds = maxRounds;
        this.convergenceStrategy = convergenceStrategy;
    }

    @Override
    public void init(InitPlanningContext initPlanningContext) {
        List<AgentInstance> subagents = initPlanningContext.subagents();
        if (subagents.size() < 3) {
            throw new IllegalArgumentException(
                    "DebatePlanner requires at least 3 subagents (2 debaters + 1 judge), got " + subagents.size());
        }
        this.debaters = new ArrayList<>(subagents.subList(0, subagents.size() - 1));
        this.judge = subagents.get(subagents.size() - 1);
    }

    @Override
    public Action firstAction(PlanningContext planningContext) {
        AgenticScope scope = planningContext.agenticScope();
        if (judgePhase) return judgeCompleted ? done(resolveOutput(judge, judgeOutput, scope)) : call(judge);
        if (currentRound == 1 && completedInRound.isEmpty() && lastDebatersMessages.isEmpty()) {
            scope.writeState(DEBATE_CONTEXT_KEY, "");
        }
        List<AgentInstance> pending = new ArrayList<>();
        debaters.stream().filter(agent -> !completedInRound.contains(agent.agentId())).forEach(pending::add);
        return pending.isEmpty() ? finishRound(scope) : call(pending);
    }

    @Override
    public Action nextAction(PlanningContext planningContext) {
        AgentInvocation invocation = planningContext.previousAgentInvocation();
        AgenticScope scope = planningContext.agenticScope();
        if (judgePhase) {
            judgeOutput = dev.langchain4j.agentic.internal.AgenticJdk8.invocationOutput(scope, invocation);
            judgeCompleted = true;
            return done(resolveOutput(judge, judgeOutput, scope));
        }
        // A skipped debater retains its previous position, but has completed this round.
        if (!invocation.skipped()) {
            Object value = dev.langchain4j.agentic.internal.AgenticJdk8.invocationOutput(scope, invocation);
            lastDebatersMessages.put(invocation.agentName(), value);
        }
        completedInRound.add(invocation.agentId());
        return completedInRound.size() < debaters.size() ? noOp() : finishRound(scope);
    }

    private Action finishRound(AgenticScope scope) {
        Map<String, Object> resolved = new HashMap<>();
        for (AgentInstance debater : debaters) {
            if (lastDebatersMessages.containsKey(debater.name())) {
                resolved.put(debater.name(), resolveOutput(debater, lastDebatersMessages.get(debater.name()), scope));
            }
        }
        lastDebatersMessages.putAll(resolved);
        if (convergenceStrategy.hasConverged(lastDebatersMessages.values())) {
            LOG.info("Convergence reached after {} rounds", currentRound);
            judgePhase = true;
        }
        if (currentRound >= maxRounds) {
            LOG.info("Max rounds ({}) reached without convergence, invoking judge", maxRounds);
            judgePhase = true;
        }
        if (judgePhase) {
            writeDebateContext(scope);
            return call(judge);
        }
        currentRound++;
        completedInRound.clear();
        writeDebateContext(scope);
        LOG.info("Starting debate round {}", currentRound);
        return call(debaters);
    }

    private Object resolveOutput(AgentInstance agent, Object value, AgenticScope scope) {
        if (value instanceof DelayedResponse<?> && agent.outputKey() != null && scope.hasState(agent.outputKey())) {
            value = scope.readState(agent.outputKey());
        }
        while (value instanceof DelayedResponse<?>) {
            if (value instanceof SuspendedResponse<?> && !((DelayedResponse<?>) value).isDone()) {
                throw new AgenticSystemSuspendedException(scope);
            }
            Object next = ((DelayedResponse<?>) value).blockingGet();
            if (next == value) throw new IllegalStateException("Self-referencing debate output: " + agent.agentId());
            value = next;
        }
        return value;
    }

    private void writeDebateContext(AgenticScope scope) {
        String debatersContext = lastDebatersMessages.entrySet().stream()
                .map(e -> e.getKey() + ": \"" + e.getValue() + "\"")
                .collect(Collectors.joining("\n"));
        scope.writeState(DEBATE_CONTEXT_KEY, debatersContext);
    }

    @Override
    public Map<String, Object> executionState() {
        Map<String, Object> state = new HashMap<>();
        state.put(dev.langchain4j.agentic.internal.AgenticJdk8.PLANNER_MANAGES_RESUME, true);
        state.put("currentRound", currentRound);
        state.put("lastDebatersMessages", new HashMap<>(lastDebatersMessages));
        state.put("completedInRound", new ArrayList<>(completedInRound));
        state.put("judgePhase", judgePhase);
        state.put("judgeCompleted", judgeCompleted);
        if (judgeOutput != null) state.put("judgeOutput", judgeOutput);
        return dev.langchain4j.internal.Jdk8.copyMap(state);
    }

    @Override
    public void restoreExecutionState(Map<String, Object> state) {
        Object savedRound = state.get("currentRound");
        if (savedRound instanceof Number) this.currentRound = ((Number) savedRound).intValue();
        Object messages = state.get("lastDebatersMessages");
        if (messages instanceof Map<?, ?>) ((Map<?, ?>) messages).forEach((name, value) -> lastDebatersMessages.put(name.toString(), value));
        Object completed = state.get("completedInRound");
        if (completed instanceof List<?>) ((List<?>) completed).forEach(id -> completedInRound.add(id.toString()));
        judgePhase = Boolean.TRUE.equals(state.get("judgePhase"));
        judgeCompleted = Boolean.TRUE.equals(state.get("judgeCompleted"));
        judgeOutput = state.get("judgeOutput");
    }

    @Override
    public AgenticSystemTopology topology() {
        return AgenticSystemTopology.STAR;
    }
}
