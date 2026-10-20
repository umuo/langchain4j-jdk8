package dev.langchain4j.mcp.client;

public final class McpRoot {
    private final String name;
    private final String uri;
    @com.fasterxml.jackson.annotation.JsonCreator
    public McpRoot(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("uri") String uri) {
        this.name = name;
        this.uri = uri;
    }
    @com.fasterxml.jackson.annotation.JsonProperty("name")
    public String name() { return name; }
    @com.fasterxml.jackson.annotation.JsonProperty("uri")
    public String uri() { return uri; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpRoot)) return false;
        McpRoot that = (McpRoot) other;
        return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(uri, that.uri);
    }
    @Override public int hashCode() { return (31 * (31 * 0 + java.util.Objects.hashCode(name)) + java.util.Objects.hashCode(uri)); }
    @Override public String toString() { return "McpRoot[" + "name=" + name + ", " + "uri=" + uri + "]"; }
}
