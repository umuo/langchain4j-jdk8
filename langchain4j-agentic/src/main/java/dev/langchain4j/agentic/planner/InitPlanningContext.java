package dev.langchain4j.agentic.planner;

import dev.langchain4j.agentic.scope.AgenticScope;
import java.util.List;

public final class InitPlanningContext {

    private final AgenticScope agenticScope;

    public AgenticScope agenticScope() {
        return agenticScope;
    }

    private final AgentInstance plannerAgent;

    public AgentInstance plannerAgent() {
        return plannerAgent;
    }

    private final List<AgentInstance> subagents;

    public List<AgentInstance> subagents() {
        return subagents;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public InitPlanningContext(@com.fasterxml.jackson.annotation.JsonProperty("agenticScope") AgenticScope agenticScope, @com.fasterxml.jackson.annotation.JsonProperty("plannerAgent") AgentInstance plannerAgent, @com.fasterxml.jackson.annotation.JsonProperty("subagents") List<AgentInstance> subagents) {
        this.agenticScope = agenticScope;
        this.plannerAgent = plannerAgent;
        this.subagents = subagents;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agenticScope, ((InitPlanningContext) obj).agenticScope) && java.util.Objects.equals(plannerAgent, ((InitPlanningContext) obj).plannerAgent) && java.util.Objects.equals(subagents, ((InitPlanningContext) obj).subagents));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agenticScope)) + java.util.Objects.hashCode(plannerAgent)) + java.util.Objects.hashCode(subagents));
    }

    public final String toString() {
        return "InitPlanningContext[" + "agenticScope=" + agenticScope + ", plannerAgent=" + plannerAgent + ", subagents=" + subagents + "]";
    }
}
