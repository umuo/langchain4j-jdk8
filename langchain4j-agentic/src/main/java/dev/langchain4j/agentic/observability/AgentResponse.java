package dev.langchain4j.agentic.observability;

import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Map;

public final class AgentResponse {

    AgentResponse(AgenticScope agenticScope, AgentInstance agent, Map<String, Object> inputs, Object output) {
        this(agenticScope, agent, inputs, output, null, null);
    }

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

    private final Object output;

    public Object output() {
        return output;
    }

    private final ChatRequest chatRequest;

    public ChatRequest chatRequest() {
        return chatRequest;
    }

    private final ChatResponse chatResponse;

    public ChatResponse chatResponse() {
        return chatResponse;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentResponse(@com.fasterxml.jackson.annotation.JsonProperty("agenticScope") AgenticScope agenticScope, @com.fasterxml.jackson.annotation.JsonProperty("agent") AgentInstance agent, @com.fasterxml.jackson.annotation.JsonProperty("inputs") Map<String, Object> inputs, @com.fasterxml.jackson.annotation.JsonProperty("output") Object output, @com.fasterxml.jackson.annotation.JsonProperty("chatRequest") ChatRequest chatRequest, @com.fasterxml.jackson.annotation.JsonProperty("chatResponse") ChatResponse chatResponse) {
        this.agenticScope = agenticScope;
        this.agent = agent;
        this.inputs = inputs;
        this.output = output;
        this.chatRequest = chatRequest;
        this.chatResponse = chatResponse;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agenticScope, ((AgentResponse) obj).agenticScope) && java.util.Objects.equals(agent, ((AgentResponse) obj).agent) && java.util.Objects.equals(inputs, ((AgentResponse) obj).inputs) && java.util.Objects.equals(output, ((AgentResponse) obj).output) && java.util.Objects.equals(chatRequest, ((AgentResponse) obj).chatRequest) && java.util.Objects.equals(chatResponse, ((AgentResponse) obj).chatResponse));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agenticScope)) + java.util.Objects.hashCode(agent)) + java.util.Objects.hashCode(inputs)) + java.util.Objects.hashCode(output)) + java.util.Objects.hashCode(chatRequest)) + java.util.Objects.hashCode(chatResponse));
    }

    public final String toString() {
        return "AgentResponse[" + "agenticScope=" + agenticScope + ", agent=" + agent + ", inputs=" + inputs + ", output=" + output + ", chatRequest=" + chatRequest + ", chatResponse=" + chatResponse + "]";
    }
}
