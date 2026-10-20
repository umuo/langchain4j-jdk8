package dev.langchain4j.mcp.client;

import org.jspecify.annotations.Nullable;

/**
 * Identifies the MCP server: who it is and which version of it is running.
 * A server reports this while the client connects to it, and the client passes it on
 * through {@link McpDiscoverResult#serverInfo()}.
 *
 * @param name the server's programmatic name, for example {@code "filesystem-server"}
 * @param version the server's own version, unrelated to the MCP protocol version
 * @param title an optional human-readable name meant for display, if the server provides one
 */
public final class McpServerInfo {
    private final String name;
    private final String version;
    private final String title;
    @com.fasterxml.jackson.annotation.JsonCreator
    public McpServerInfo(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("version") String version, @com.fasterxml.jackson.annotation.JsonProperty("title") String title) {
        this.name = name;
        this.version = version;
        this.title = title;
    }
    @com.fasterxml.jackson.annotation.JsonProperty("name")
    public String name() { return name; }
    @com.fasterxml.jackson.annotation.JsonProperty("version")
    public String version() { return version; }
    @com.fasterxml.jackson.annotation.JsonProperty("title")
    public String title() { return title; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpServerInfo)) return false;
        McpServerInfo that = (McpServerInfo) other;
        return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(version, that.version) && java.util.Objects.equals(title, that.title);
    }
    @Override public int hashCode() { return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(name)) + java.util.Objects.hashCode(version)) + java.util.Objects.hashCode(title)); }
    @Override public String toString() { return "McpServerInfo[" + "name=" + name + ", " + "version=" + version + ", " + "title=" + title + "]"; }
}
