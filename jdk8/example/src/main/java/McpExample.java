import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent BOM consumer: OpenAI HTTP -> AI Services -> MCP HTTP tool -> model. */
public final class McpExample {
    public interface Assistant { String chat(String question); }
    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new IllegalStateException("Requires Java 8");
        ObjectMapper json = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                String result;
                if (method.equals("initialize")) result = "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                else if (method.equals("tools/list")) result = "{\"tools\":[{\"name\":\"price\",\"description\":\"Look up price\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\"}},\"required\":[\"item\"]}}]}";
                else if (method.equals("tools/call")) {
                    if (!"apple".equals(request.path("params").path("arguments").path("item").asText())) throw new AssertionError(request);
                    calls.incrementAndGet(); result = "{\"content\":[{\"type\":\"text\",\"text\":\"7 yuan\"}]}";
                } else throw new AssertionError(method);
                byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                boolean hasResult = request.path("messages").size() > 1;
                if (hasResult && !"7 yuan".equals(request.path("messages").get(2).path("content").asText())) throw new AssertionError(request);
                String message = hasResult ? "{\"role\":\"assistant\",\"content\":\"7 yuan\"}" : "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"price\",\"arguments\":\"{\\\"item\\\":\\\"apple\\\"}\"}}]}";
                byte[] body = ("{\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + (hasResult ? "stop" : "tool_calls") + "\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (DefaultMcpClient mcp = DefaultMcpClient.builder().transport(StreamableHttpMcpTransport.builder().url(base + "/mcp").build()).autoHealthCheck(false).build()) {
            OpenAiChatModel model = OpenAiChatModel.builder().baseUrl(base + "/v1").apiKey("synthetic").modelName("fixture").maxRetries(0).build();
            Assistant service = AiServices.builder(Assistant.class).chatModel(model).toolProvider(McpToolProvider.builder().mcpClients(mcp).build()).build();
            String answer = service.chat("apple price?");
            if (!"7 yuan".equals(answer) || calls.get() != 1) throw new AssertionError(answer);
            System.out.println("Java 8 MCP OK; answer: " + answer + "; remote tools: " + calls.get());
        } finally { server.stop(0); }
    }
}
