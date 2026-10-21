package dev.langchain4j.agentic.observability;

import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.tool.ToolExecution;

public final class AfterAgentToolExecution {

    public AgenticScope agenticScope() {
        return (AgenticScope) toolExecution.invocationContext().managedParameters().get(AgenticScope.class);
    }

    private final AgentInstance agentInstance;

    public AgentInstance agentInstance() {
        return agentInstance;
    }

    private final ToolExecution toolExecution;

    public ToolExecution toolExecution() {
        return toolExecution;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AfterAgentToolExecution(@com.fasterxml.jackson.annotation.JsonProperty("agentInstance") AgentInstance agentInstance, @com.fasterxml.jackson.annotation.JsonProperty("toolExecution") ToolExecution toolExecution) {
        this.agentInstance = agentInstance;
        this.toolExecution = toolExecution;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentInstance, ((AfterAgentToolExecution) obj).agentInstance) && java.util.Objects.equals(toolExecution, ((AfterAgentToolExecution) obj).toolExecution));
    }

    public final int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(agentInstance)) + java.util.Objects.hashCode(toolExecution));
    }

    public final String toString() {
        return "AfterAgentToolExecution[" + "agentInstance=" + agentInstance + ", toolExecution=" + toolExecution + "]";
    }
}
