package dev.langchain4j.agentic.workflow.impl;

import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.PlanningContext;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.workflow.ConditionalAgent;
import dev.langchain4j.agentic.workflow.ConditionalAgentInstance;
import java.util.List;

public final class ConditionalPlanner implements Planner {

    @Override
    public Action firstAction(PlanningContext planningContext) {
        List<AgentInstance> agentsToCall = conditionalSubagents.stream().filter(conditionalAgent -> conditionalAgent.predicate().test(planningContext.agenticScope())).flatMap(conditionalAgent -> conditionalAgent.agentInstances().stream()).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
        return agentsToCall.isEmpty() ? done() : call(agentsToCall);
    }

    @Override
    public Action nextAction(PlanningContext planningContext) {
        return done();
    }

    @Override
    public AgenticSystemTopology topology() {
        return AgenticSystemTopology.ROUTER;
    }

    @Override
    public boolean terminated() {
        return true;
    }

    @Override
    public <T extends AgentInstance> T as(Class<T> agentInstanceClass, AgentInstance agentInstance) {
        if (agentInstanceClass != ConditionalAgentInstance.class) {
            throw new ClassCastException("Cannot cast to " + agentInstanceClass.getName() + ": incompatible type");
        }
        return (T) new DefaultConditionalAgentInstance(agentInstance, this);
    }

    private final List<ConditionalAgent> conditionalSubagents;

    public List<ConditionalAgent> conditionalSubagents() {
        return conditionalSubagents;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public ConditionalPlanner(@com.fasterxml.jackson.annotation.JsonProperty("conditionalSubagents") List<ConditionalAgent> conditionalSubagents) {
        this.conditionalSubagents = conditionalSubagents;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(conditionalSubagents, ((ConditionalPlanner) obj).conditionalSubagents));
    }

    public final int hashCode() {
        return (31 * 0 + java.util.Objects.hashCode(conditionalSubagents));
    }

    public final String toString() {
        return "ConditionalPlanner[" + "conditionalSubagents=" + conditionalSubagents + "]";
    }
}
