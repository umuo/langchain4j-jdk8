import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Independent consumer of the compatibility BOM; no external endpoint or credentials required.
 */
public final class Example {

    public interface Assistant { String chat(String message); }

    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version")))
            throw new IllegalStateException("Run this acceptance example on Java 8");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"id\":\"test\",\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Java 8 OK\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            OpenAiChatModel model = OpenAiChatModel.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").apiKey("synthetic").modelName("mock-model").maxRetries(0).build();
            String response = dev.langchain4j.service.AiServices.create(Assistant.class, model).chat("hello");
            if (!"Java 8 OK".equals(response))
                throw new AssertionError(response);
            System.out.println("Runtime: " + System.getProperty("java.version") + "; response: " + response);
        } finally {
            server.stop(0);
        }
    }
}
