package dev.langchain4j.agentic.scope;

public final class AgenticScopeKey {

    private final String agentId;

    public String agentId() {
        return agentId;
    }

    private final Object memoryId;

    public Object memoryId() {
        return memoryId;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgenticScopeKey(@com.fasterxml.jackson.annotation.JsonProperty("agentId") String agentId, @com.fasterxml.jackson.annotation.JsonProperty("memoryId") Object memoryId) {
        this.agentId = agentId;
        this.memoryId = memoryId;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentId, ((AgenticScopeKey) obj).agentId) && java.util.Objects.equals(memoryId, ((AgenticScopeKey) obj).memoryId));
    }

    public final int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(agentId)) + java.util.Objects.hashCode(memoryId));
    }

    public final String toString() {
        return "AgenticScopeKey[" + "agentId=" + agentId + ", memoryId=" + memoryId + "]";
    }
}
