package dev.langchain4j.mcp.client;

import dev.langchain4j.Internal;
import java.util.List;

/**
 * One page of a paginated MCP list response.
 *
 * <p>The items and the cursor sit in the same response, so reading them together is what lets the
 * response be parsed once. Reading them separately meant parsing the whole document twice, and the
 * documents in question are lists - a server's entire tool catalogue, schemas included.
 *
 * @param items the page's entries
 * @param nextCursor the cursor for the following page, or null when this is the last one
 */
@Internal
final class McpPage<T> {
    private final List<T> items;
    private final String nextCursor;
    McpPage(List<T> items, String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }
    public List<T> items() { return items; }
    public String nextCursor() { return nextCursor; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpPage)) return false;
        McpPage<?> that = (McpPage<?> ) other;
        return java.util.Objects.equals(items, that.items) && java.util.Objects.equals(nextCursor, that.nextCursor);
    }
    @Override public int hashCode() { return (31 * (31 * 0 + java.util.Objects.hashCode(items)) + java.util.Objects.hashCode(nextCursor)); }
    @Override public String toString() { return "McpPage[" + "items=" + items + ", " + "nextCursor=" + nextCursor + "]"; }
}
