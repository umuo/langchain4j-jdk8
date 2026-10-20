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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Independent BOM consumer: OpenAI HTTP -> AI Services -> MCP HTTP tool -> model. */
public final class McpExample {
    public interface Assistant { String chat(String question); }
    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new IllegalStateException("Requires Java 8");
        ObjectMapper json = new ObjectMapper();
        boolean recovery = args.length > 0 && "recovery".equals(args[0]);
        AtomicInteger calls = new AtomicInteger(), sessions = new AtomicInteger(), expired = new AtomicInteger(), resumes = new AtomicInteger(), released = new AtomicInteger();
        AtomicReference<String> pendingResponse = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().close();
                    if (recovery && !"session-2".equals(exchange.getRequestHeaders().getFirst("MCP-Session-Id"))) throw new AssertionError("wrong session DELETE");
                    released.incrementAndGet(); exchange.sendResponseHeaders(204, -1); return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().close();
                    String cursor = exchange.getRequestHeaders().getFirst("Last-Event-ID");
                    if (cursor == null) { exchange.sendResponseHeaders(405, -1); return; }
                    if (!"tool-cursor".equals(cursor)) throw new AssertionError(cursor);
                    resumes.incrementAndGet();
                    byte[] body = ("id: tool-done\ndata: " + pendingResponse.get() + "\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); return;
                }
                JsonNode request = json.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                String result;
                if (method.equals("initialize")) {
                    if (recovery) exchange.getResponseHeaders().set("MCP-Session-Id", "session-" + sessions.incrementAndGet());
                    result = "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                } else if (method.equals("tools/list")) result = "{\"tools\":[{\"name\":\"price\",\"description\":\"Look up price\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\"}},\"required\":[\"item\"]}}]}";
                else if (method.equals("tools/call")) {
                    if (!"apple".equals(request.path("params").path("arguments").path("item").asText())) throw new AssertionError(request);
                    if (recovery && "session-1".equals(exchange.getRequestHeaders().getFirst("MCP-Session-Id"))) {
                        expired.incrementAndGet(); exchange.sendResponseHeaders(404, -1); return;
                    }
                    calls.incrementAndGet(); result = "{\"content\":[{\"type\":\"text\",\"text\":\"7 yuan\"}]}";
                } else throw new AssertionError(method);
                byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
                if (recovery && method.equals("tools/call")) {
                    pendingResponse.set(new String(body, StandardCharsets.UTF_8));
                    byte[] priming = "id: tool-cursor\ndata:\n\nretry: 10\n".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, priming.length); exchange.getResponseBody().write(priming); return;
                }
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
        StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder().url(base + "/mcp").subsidiaryChannel(recovery).build();
        try (DefaultMcpClient mcp = DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).build()) {
            if (recovery) {
                try {
                    mcp.executeToolAsync(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("expired").name("price").arguments("{\"item\":\"apple\"}").build(), null).get(5, TimeUnit.SECONDS);
                    throw new AssertionError("Expired tool must fail instead of being replayed");
                } catch (ExecutionException expected) {
                    transport.sessionInitialization().get(5, TimeUnit.SECONDS);
                }
            }
            OpenAiChatModel model = OpenAiChatModel.builder().baseUrl(base + "/v1").apiKey("synthetic").modelName("fixture").maxRetries(0).build();
            Assistant service = AiServices.builder(Assistant.class).chatModel(model).toolProvider(McpToolProvider.builder().mcpClients(mcp).build()).build();
            String answer = service.chat("apple price?");
            if (!"7 yuan".equals(answer) || calls.get() != 1) throw new AssertionError(answer);
            System.out.println("Java 8 MCP OK; answer: " + answer + "; remote tools: " + calls.get());
        } finally { server.stop(0); }
        if (recovery) {
            if (sessions.get() != 2 || expired.get() != 1 || calls.get() != 1 || resumes.get() != 1 || released.get() != 1) throw new AssertionError("MCP recovery counts");
            System.out.println("Java 8 MCP recovery OK; sessions: 2; expired POST: 1; executed tools: 1; resume GET: 1; DELETE: 1");
        }
    }
}
