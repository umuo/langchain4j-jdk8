package dev.langchain4j.agentic.observability;

import java.util.Map;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;

public final class AgentInvocationError {

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

    private final Throwable error;

    public Throwable error() {
        return error;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentInvocationError(@com.fasterxml.jackson.annotation.JsonProperty("agenticScope") AgenticScope agenticScope, @com.fasterxml.jackson.annotation.JsonProperty("agent") AgentInstance agent, @com.fasterxml.jackson.annotation.JsonProperty("inputs") Map<String, Object> inputs, @com.fasterxml.jackson.annotation.JsonProperty("error") Throwable error) {
        this.agenticScope = agenticScope;
        this.agent = agent;
        this.inputs = inputs;
        this.error = error;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agenticScope, ((AgentInvocationError) obj).agenticScope) && java.util.Objects.equals(agent, ((AgentInvocationError) obj).agent) && java.util.Objects.equals(inputs, ((AgentInvocationError) obj).inputs) && java.util.Objects.equals(error, ((AgentInvocationError) obj).error));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agenticScope)) + java.util.Objects.hashCode(agent)) + java.util.Objects.hashCode(inputs)) + java.util.Objects.hashCode(error));
    }

    public final String toString() {
        return "AgentInvocationError[" + "agenticScope=" + agenticScope + ", agent=" + agent + ", inputs=" + inputs + ", error=" + error + "]";
    }
}
