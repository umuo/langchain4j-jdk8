package dev.langchain4j.agentic.agent;

import dev.langchain4j.agentic.scope.AgenticScope;

public final class ErrorContext {

    private final String agentName;

    public String agentName() {
        return agentName;
    }

    private final AgenticScope agenticScope;

    public AgenticScope agenticScope() {
        return agenticScope;
    }

    private final AgentInvocationException exception;

    public AgentInvocationException exception() {
        return exception;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public ErrorContext(@com.fasterxml.jackson.annotation.JsonProperty("agentName") String agentName, @com.fasterxml.jackson.annotation.JsonProperty("agenticScope") AgenticScope agenticScope, @com.fasterxml.jackson.annotation.JsonProperty("exception") AgentInvocationException exception) {
        this.agentName = agentName;
        this.agenticScope = agenticScope;
        this.exception = exception;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentName, ((ErrorContext) obj).agentName) && java.util.Objects.equals(agenticScope, ((ErrorContext) obj).agenticScope) && java.util.Objects.equals(exception, ((ErrorContext) obj).exception));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agentName)) + java.util.Objects.hashCode(agenticScope)) + java.util.Objects.hashCode(exception));
    }

    public final String toString() {
        return "ErrorContext[" + "agentName=" + agentName + ", agenticScope=" + agenticScope + ", exception=" + exception + "]";
    }
}
