package dev.langchain4j.agentic.internal;

import java.util.Map;

public final class AgentInvocationArguments {

    private final Map<String, Object> namedArgs;

    public Map<String, Object> namedArgs() {
        return namedArgs;
    }

    private final Object[] positionalArgs;

    public Object[] positionalArgs() {
        return positionalArgs;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentInvocationArguments(@com.fasterxml.jackson.annotation.JsonProperty("namedArgs") Map<String, Object> namedArgs, @com.fasterxml.jackson.annotation.JsonProperty("positionalArgs") Object[] positionalArgs) {
        this.namedArgs = namedArgs;
        this.positionalArgs = positionalArgs;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(namedArgs, ((AgentInvocationArguments) obj).namedArgs) && java.util.Objects.equals(positionalArgs, ((AgentInvocationArguments) obj).positionalArgs));
    }

    public final int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(namedArgs)) + java.util.Objects.hashCode(positionalArgs));
    }

    public final String toString() {
        return "AgentInvocationArguments[" + "namedArgs=" + namedArgs + ", positionalArgs=" + positionalArgs + "]";
    }
}
