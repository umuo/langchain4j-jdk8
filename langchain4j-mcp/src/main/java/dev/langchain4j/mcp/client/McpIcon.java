package dev.langchain4j.mcp.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The 'Icon' object from the MCP protocol schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class McpIcon {
    private final String mimeType;
    private final List<String> sizes;
    private final String src;
    private final McpIconTheme theme;
    @JsonCreator
    public McpIcon(@JsonProperty("mimeType") String mimeType, @JsonProperty("sizes") List<String> sizes, @JsonProperty("src") String src, @JsonProperty("theme") McpIconTheme theme) {
        sizes = sizes == null ? dev.langchain4j.internal.Jdk8.list() : dev.langchain4j.internal.Jdk8.copyList(sizes);
        this.mimeType = mimeType;
        this.sizes = sizes;
        this.src = src;
        this.theme = theme;
    }
    @JsonProperty("mimeType")
    public String mimeType() { return mimeType; }
    @JsonProperty("sizes")
    public List<String> sizes() { return sizes; }
    @JsonProperty("src")
    public String src() { return src; }
    @JsonProperty("theme")
    public McpIconTheme theme() { return theme; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpIcon)) return false;
        McpIcon that = (McpIcon) other;
        return java.util.Objects.equals(mimeType, that.mimeType) && java.util.Objects.equals(sizes, that.sizes) && java.util.Objects.equals(src, that.src) && java.util.Objects.equals(theme, that.theme);
    }
    @Override public int hashCode() { return (31 * (31 * (31 * (31 * 0 + java.util.Objects.hashCode(mimeType)) + java.util.Objects.hashCode(sizes)) + java.util.Objects.hashCode(src)) + java.util.Objects.hashCode(theme)); }
    @Override public String toString() { return "McpIcon[" + "mimeType=" + mimeType + ", " + "sizes=" + sizes + ", " + "src=" + src + ", " + "theme=" + theme + "]"; }

}
