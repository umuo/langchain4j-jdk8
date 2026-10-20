import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** Independent BOM consumer: real OpenAI embedding and chat adapters, local fixture endpoints. */
public final class HttpRagExample {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger EMBEDDINGS = new AtomicInteger();
    public interface Assistant { java.util.concurrent.CompletableFuture<Result<String>> chat(String question); }
    static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version")))
            throw new IllegalStateException("Run this example on Java 8");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            EMBEDDINGS.incrementAndGet();
            JsonNode input = JSON.readTree(exchange.getRequestBody()).path("input");
            ObjectNode response = JSON.createObjectNode().put("model", "fixture-embedding");
            com.fasterxml.jackson.databind.node.ArrayNode data = response.putArray("data");
            // Reverse wire order deliberately: vectors must still align with their input indices.
            for (int i=input.size()-1; i>=0; i--) {
                boolean apple = input.get(i).asText().contains("apple");
                data.addObject().put("index", i).putArray("embedding").add(apple ? 1 : 0).add(apple ? 0 : 1);
            }
            response.putObject("usage").put("prompt_tokens", input.size()).put("total_tokens", input.size());
            respond(exchange, response.toString());
        });
        server.createContext("/v1/chat/completions", exchange -> {
            String prompt = JSON.readTree(exchange.getRequestBody()).path("messages").toString();
            String answer = prompt.contains("apple price is 7 yuan.") && !prompt.contains("pear price") ? "7 yuan" : "wrong retrieval";
            ObjectNode response = JSON.createObjectNode().put("id", "fixture").put("model", "fixture-chat");
            ObjectNode choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", answer);
            respond(exchange, response.toString());
        });
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        okhttp3.ConnectionPool pool = new okhttp3.ConnectionPool();
        okhttp3.OkHttpClient.Builder transport = new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenAiEmbeddingModel embeddings = OpenAiEmbeddingModel.builder().baseUrl(base).apiKey("synthetic")
                    .modelName("fixture-embedding").dimensions(2).maxRetries(0)
                    .httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(transport)).build();
            InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<TextSegment>();
            EmbeddingStoreIngestor.builder().documentSplitter(new DocumentByParagraphSplitter(30, 0))
                    .embeddingModel(embeddings).embeddingStore(store).build().ingest(
                            Document.from("apple price is 7 yuan.\n\npear price is 9 yuan.", Metadata.from("catalog", "public")));
            OpenAiChatModel chat = OpenAiChatModel.builder().baseUrl(base).apiKey("synthetic").modelName("fixture-chat")
                    .maxRetries(0).httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(transport)).build();
            Assistant assistant = AiServices.builder(Assistant.class).chatModel(chat).contentRetriever(
                    EmbeddingStoreContentRetriever.builder().embeddingModel(embeddings)
                            .embeddingStore(InMemoryEmbeddingStore.fromJson(store.serializeToJson()))
                            .maxResults(1).minScore(0.9).filter(metadataKey("catalog").isEqualTo("public")).build()).build();
            Result<String> result = assistant.chat("What is the apple price?").get(5, TimeUnit.SECONDS);
            if (!"7 yuan".equals(result.content()) || result.sources().size()!=1 || EMBEDDINGS.get()!=2)
                throw new AssertionError("HTTP RAG failed: " + result.content());
            System.out.println("Java 8 HTTP RAG OK; answer: " + result.content() + "; embedding requests: " + EMBEDDINGS.get());
        } finally {
            server.stop(0); dispatcher.cancelAll(); dispatcher.executorService().shutdownNow(); pool.evictAll();
        }
    }
}
