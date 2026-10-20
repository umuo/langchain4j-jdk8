package dev.langchain4j.mcp.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What an MCP server reports about itself when the client connects to it, using the
 * {@code server/discover} request of MCP protocol version 2026-07-28 and later.
 * It is delivered to {@link McpClientListener#afterServerDiscover(McpCallContext, McpDiscoverResult)}.
 *
 * @param supportedVersions the protocol versions the server can speak, never {@code null}
 * @param capabilities the optional protocol features the server offers, such as {@code tools}
 *                     or {@code resources}, as a tree of nested maps; never {@code null},
 *                     but empty for a server that offers none
 * @param serverInfo which server this is, if it says
 * @param instructions a hint from the server on how it is meant to be used, if it provides one
 * @param resultType how the server answered; {@code "complete"} for an ordinary answer
 */
public final class McpDiscoverResult {
    private final List<String> supportedVersions;
    private final Map<String, Object> capabilities;
    private final McpServerInfo serverInfo;
    private final String instructions;
    private final String resultType;
    @com.fasterxml.jackson.annotation.JsonCreator
    public McpDiscoverResult(@com.fasterxml.jackson.annotation.JsonProperty("supportedVersions") List<String> supportedVersions, @com.fasterxml.jackson.annotation.JsonProperty("capabilities") Map<String, Object> capabilities, @com.fasterxml.jackson.annotation.JsonProperty("serverInfo") McpServerInfo serverInfo, @com.fasterxml.jackson.annotation.JsonProperty("instructions") String instructions, @com.fasterxml.jackson.annotation.JsonProperty("resultType") String resultType) {
        supportedVersions =
                supportedVersions == null ? dev.langchain4j.internal.Jdk8.list() : Collections.unmodifiableList(new ArrayList<>(supportedVersions));
        capabilities = capabilities == null ? dev.langchain4j.internal.Jdk8.map() : Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        this.supportedVersions = supportedVersions;
        this.capabilities = capabilities;
        this.serverInfo = serverInfo;
        this.instructions = instructions;
        this.resultType = resultType;
    }
    @com.fasterxml.jackson.annotation.JsonProperty("supportedVersions")
    public List<String> supportedVersions() { return supportedVersions; }
    @com.fasterxml.jackson.annotation.JsonProperty("capabilities")
    public Map<String, Object> capabilities() { return capabilities; }
    @com.fasterxml.jackson.annotation.JsonProperty("serverInfo")
    public McpServerInfo serverInfo() { return serverInfo; }
    @com.fasterxml.jackson.annotation.JsonProperty("instructions")
    public String instructions() { return instructions; }
    @com.fasterxml.jackson.annotation.JsonProperty("resultType")
    public String resultType() { return resultType; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpDiscoverResult)) return false;
        McpDiscoverResult that = (McpDiscoverResult) other;
        return java.util.Objects.equals(supportedVersions, that.supportedVersions) && java.util.Objects.equals(capabilities, that.capabilities) && java.util.Objects.equals(serverInfo, that.serverInfo) && java.util.Objects.equals(instructions, that.instructions) && java.util.Objects.equals(resultType, that.resultType);
    }
    @Override public int hashCode() { return (31 * (31 * (31 * (31 * (31 * 0 + java.util.Objects.hashCode(supportedVersions)) + java.util.Objects.hashCode(capabilities)) + java.util.Objects.hashCode(serverInfo)) + java.util.Objects.hashCode(instructions)) + java.util.Objects.hashCode(resultType)); }
    @Override public String toString() { return "McpDiscoverResult[" + "supportedVersions=" + supportedVersions + ", " + "capabilities=" + capabilities + ", " + "serverInfo=" + serverInfo + ", " + "instructions=" + instructions + ", " + "resultType=" + resultType + "]"; }

}
