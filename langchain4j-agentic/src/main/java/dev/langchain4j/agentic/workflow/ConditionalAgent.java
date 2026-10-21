package dev.langchain4j.agentic.workflow;

import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import java.util.List;
import java.util.function.Predicate;

public final class ConditionalAgent {

    private final String condition;

    public String condition() {
        return condition;
    }

    private final Predicate<AgenticScope> predicate;

    public Predicate<AgenticScope> predicate() {
        return predicate;
    }

    private final List<AgentInstance> agentInstances;

    public List<AgentInstance> agentInstances() {
        return agentInstances;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public ConditionalAgent(@com.fasterxml.jackson.annotation.JsonProperty("condition") String condition, @com.fasterxml.jackson.annotation.JsonProperty("predicate") Predicate<AgenticScope> predicate, @com.fasterxml.jackson.annotation.JsonProperty("agentInstances") List<AgentInstance> agentInstances) {
        this.condition = condition;
        this.predicate = predicate;
        this.agentInstances = agentInstances;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(condition, ((ConditionalAgent) obj).condition) && java.util.Objects.equals(predicate, ((ConditionalAgent) obj).predicate) && java.util.Objects.equals(agentInstances, ((ConditionalAgent) obj).agentInstances));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(condition)) + java.util.Objects.hashCode(predicate)) + java.util.Objects.hashCode(agentInstances));
    }

    public final String toString() {
        return "ConditionalAgent[" + "condition=" + condition + ", predicate=" + predicate + ", agentInstances=" + agentInstances + "]";
    }
}
