package dev.langchain4j.agentic.observability;

import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.tool.BeforeToolExecution;

public final class BeforeAgentToolExecution {

    public AgenticScope agenticScope() {
        return (AgenticScope) toolExecution.invocationContext().managedParameters().get(AgenticScope.class);
    }

    private final AgentInstance agentInstance;

    public AgentInstance agentInstance() {
        return agentInstance;
    }

    private final BeforeToolExecution toolExecution;

    public BeforeToolExecution toolExecution() {
        return toolExecution;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public BeforeAgentToolExecution(@com.fasterxml.jackson.annotation.JsonProperty("agentInstance") AgentInstance agentInstance, @com.fasterxml.jackson.annotation.JsonProperty("toolExecution") BeforeToolExecution toolExecution) {
        this.agentInstance = agentInstance;
        this.toolExecution = toolExecution;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentInstance, ((BeforeAgentToolExecution) obj).agentInstance) && java.util.Objects.equals(toolExecution, ((BeforeAgentToolExecution) obj).toolExecution));
    }

    public final int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(agentInstance)) + java.util.Objects.hashCode(toolExecution));
    }

    public final String toString() {
        return "BeforeAgentToolExecution[" + "agentInstance=" + agentInstance + ", toolExecution=" + toolExecution + "]";
    }
}
