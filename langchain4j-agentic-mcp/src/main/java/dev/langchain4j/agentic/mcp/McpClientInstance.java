package dev.langchain4j.agentic.mcp;

import dev.langchain4j.agentic.internal.InternalAgent;
import java.util.Map;

public interface McpClientInstance extends InternalAgent {

    String[] inputKeys();

    /** Descriptions published by the MCP tool for each input key. */
    default Map<String, String> inputDescriptions() {
        return dev.langchain4j.internal.Jdk8.map();
    }

    String toolName();

    String toolDescription();
}
