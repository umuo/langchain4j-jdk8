import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** Standalone BOM consumer. The tiny keyword model demonstrates plumbing, not semantic search quality. */
public final class RagExample {
    public interface Assistant { Result<String> chat(String question); }
    public static void main(String[] args) {
        if (!"1.8".equals(System.getProperty("java.specification.version")))
            throw new IllegalStateException("Run this acceptance example on Java 8");
        EmbeddingModel embeddings = new EmbeddingModel() {
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> vectors = new ArrayList<Embedding>();
            for (TextSegment segment : segments)
                vectors.add(Embedding.from(segment.text().contains("apple") ? new float[]{1,0} : new float[]{0,1}));
            return Response.from(vectors);
            }
        };
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<TextSegment>();
        EmbeddingStoreIngestor.builder().documentSplitter(new DocumentByParagraphSplitter(40, 0))
                .embeddingModel(embeddings).embeddingStore(store).build().ingest(
                        Document.from("apple price is 7 yuan.\n\npear price is 9 yuan.", Metadata.from("catalog", "public")));
        // Exercise persistence without creating files or accessing a remote service.
        InMemoryEmbeddingStore<TextSegment> restored = InMemoryEmbeddingStore.fromJson(store.serializeToJson());
        ChatModel localModel = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                String prompt = ((UserMessage) request.messages().get(0)).singleText();
                if (!prompt.contains("apple price is 7 yuan.") || prompt.contains("pear price"))
                    throw new AssertionError("Incorrect retrieval: " + prompt);
                return ChatResponse.builder().aiMessage(AiMessage.from("7 yuan")).build();
            }
        };
        Assistant assistant = AiServices.builder(Assistant.class).chatModel(localModel).contentRetriever(
                EmbeddingStoreContentRetriever.builder().embeddingModel(embeddings).embeddingStore(restored)
                        .maxResults(1).minScore(0.9).filter(metadataKey("catalog").isEqualTo("public")).build()).build();
        Result<String> result = assistant.chat("What is the apple price?");
        if (!"7 yuan".equals(result.content()) || result.sources().size() != 1)
            throw new AssertionError("RAG failed");
        System.out.println("Java 8 RAG OK; answer: " + result.content()
                + "; source: " + result.sources().get(0).textSegment().text());
    }
}
