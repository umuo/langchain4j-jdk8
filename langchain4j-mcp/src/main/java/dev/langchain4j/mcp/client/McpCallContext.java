package dev.langchain4j.mcp.client;

import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Context information for any invocation made towards an MCP server.
 *
 * It contains the AI service invocation context when this is during
 * an AI service invocation (in other cases, the invocation context is null).
 */
public final class McpCallContext {
    private final InvocationContext invocationContext;
    private final McpClientMessage message;
    private final Map<String, String> mcpParamHeaders;
    @com.fasterxml.jackson.annotation.JsonCreator
    public McpCallContext(@com.fasterxml.jackson.annotation.JsonProperty("invocationContext") InvocationContext invocationContext, @com.fasterxml.jackson.annotation.JsonProperty("message") McpClientMessage message, @com.fasterxml.jackson.annotation.JsonProperty("mcpParamHeaders") Map<String, String> mcpParamHeaders) {
        this.invocationContext = invocationContext;
        this.message = message;
        this.mcpParamHeaders = mcpParamHeaders;
    }
    @com.fasterxml.jackson.annotation.JsonProperty("invocationContext")
    public InvocationContext invocationContext() { return invocationContext; }
    @com.fasterxml.jackson.annotation.JsonProperty("message")
    public McpClientMessage message() { return message; }
    @com.fasterxml.jackson.annotation.JsonProperty("mcpParamHeaders")
    public Map<String, String> mcpParamHeaders() { return mcpParamHeaders; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpCallContext)) return false;
        McpCallContext that = (McpCallContext) other;
        return java.util.Objects.equals(invocationContext, that.invocationContext) && java.util.Objects.equals(message, that.message) && java.util.Objects.equals(mcpParamHeaders, that.mcpParamHeaders);
    }
    @Override public int hashCode() { return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(invocationContext)) + java.util.Objects.hashCode(message)) + java.util.Objects.hashCode(mcpParamHeaders)); }
    @Override public String toString() { return "McpCallContext[" + "invocationContext=" + invocationContext + ", " + "message=" + message + ", " + "mcpParamHeaders=" + mcpParamHeaders + "]"; }


    public McpCallContext(@Nullable InvocationContext invocationContext, McpClientMessage message) {
        this(invocationContext, message, null);
    }
}
