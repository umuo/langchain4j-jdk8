package dev.langchain4j.agentic.scope;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.agentic.internal.DelayedResponse;
import java.util.Map;

/**
 * The invocation of an agent, as notified to the planner of its parent.
 *
 * @param skipReason why the agent was not invoked, because its activation condition was false or because it is
 *        optional and one of its arguments was missing, or {@code null} when it was invoked. The output of a skipped
 *        agent is the current value of its output key. Skipped invocations are not registered in the
 *        {@link AgenticScope}.
 */
@JsonInclude(NON_NULL)
public final class AgentInvocation {

    public AgentInvocation(Class<?> agentType, String agentName, String agentId, Map<String, Object> input, Object output) {
        this(agentType, agentName, agentId, input, output, null);
    }

    /**
     * Returns {@code true} if the agent was skipped instead of being invoked.
     */
    public boolean skipped() {
        return skipReason != null;
    }

    public Object output() {
        return output instanceof DelayedResponse<?> ? ((DelayedResponse<?>) output).result() : output;
    }

    @Override
    public String toString() {
        return "AgentInvocation{" + "agentName=" + agentName + ", input=" + input + ", output=" + output + (skipped() ? ", skipped: " + skipReason : "") + '}';
    }

    private final Class<?> agentType;

    public Class<?> agentType() {
        return agentType;
    }

    private final String agentName;

    public String agentName() {
        return agentName;
    }

    private final String agentId;

    public String agentId() {
        return agentId;
    }

    private final Map<String, Object> input;

    public Map<String, Object> input() {
        return input;
    }

    private final Object output;

    private final String skipReason;

    public String skipReason() {
        return skipReason;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentInvocation(@JsonProperty("agentType") Class<?> agentType, @JsonProperty("agentName") String agentName, @JsonProperty("agentId") String agentId, @JsonProperty("input") Map<String, Object> input, @JsonProperty("output") Object output, @JsonProperty("skipReason") String skipReason) {
        this.agentType = agentType;
        this.agentName = agentName;
        this.agentId = agentId;
        this.input = input;
        this.output = output;
        this.skipReason = skipReason;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentType, ((AgentInvocation) obj).agentType) && java.util.Objects.equals(agentName, ((AgentInvocation) obj).agentName) && java.util.Objects.equals(agentId, ((AgentInvocation) obj).agentId) && java.util.Objects.equals(input, ((AgentInvocation) obj).input) && java.util.Objects.equals(output, ((AgentInvocation) obj).output) && java.util.Objects.equals(skipReason, ((AgentInvocation) obj).skipReason));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agentType)) + java.util.Objects.hashCode(agentName)) + java.util.Objects.hashCode(agentId)) + java.util.Objects.hashCode(input)) + java.util.Objects.hashCode(output)) + java.util.Objects.hashCode(skipReason));
    }
}
