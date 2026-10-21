package dev.langchain4j.agentic.observability;

import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import java.util.Map;

public final class AgentRequest {

    public String agentName() {
        return agent.name();
    }

    public String agentId() {
        return agent.agentId();
    }

    private final AgenticScope agenticScope;

    public AgenticScope agenticScope() {
        return agenticScope;
    }

    private final AgentInstance agent;

    public AgentInstance agent() {
        return agent;
    }

    private final Map<String, Object> inputs;

    public Map<String, Object> inputs() {
        return inputs;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentRequest(@com.fasterxml.jackson.annotation.JsonProperty("agenticScope") AgenticScope agenticScope, @com.fasterxml.jackson.annotation.JsonProperty("agent") AgentInstance agent, @com.fasterxml.jackson.annotation.JsonProperty("inputs") Map<String, Object> inputs) {
        this.agenticScope = agenticScope;
        this.agent = agent;
        this.inputs = inputs;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agenticScope, ((AgentRequest) obj).agenticScope) && java.util.Objects.equals(agent, ((AgentRequest) obj).agent) && java.util.Objects.equals(inputs, ((AgentRequest) obj).inputs));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agenticScope)) + java.util.Objects.hashCode(agent)) + java.util.Objects.hashCode(inputs));
    }

    public final String toString() {
        return "AgentRequest[" + "agenticScope=" + agenticScope + ", agent=" + agent + ", inputs=" + inputs + "]";
    }
}
