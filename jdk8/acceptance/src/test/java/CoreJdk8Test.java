import dev.langchain4j.internal.Jdk8;
import dev.langchain4j.internal.JsonParsingUtils.ParsedJson;
import dev.langchain4j.internal.VirtualThreadUtils;
import dev.langchain4j.model.batch.BatchItemResult;
import dev.langchain4j.model.embedding.request.EmbeddingParameter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoreJdk8Test {

    @Test
    public void immutableFactoriesPreserveContracts() {
        List<String> values = Jdk8.list("a", "b");
        assertEquals(Arrays.asList("a", "b"), values);
        assertThrows(UnsupportedOperationException.class, () -> values.add("c"));
        assertThrows(NullPointerException.class, () -> Jdk8.list("a", null));
        assertThrows(IllegalArgumentException.class, () -> Jdk8.set("a", "a"));
        assertThrows(IllegalArgumentException.class, () -> Jdk8.map("a", 1, "a", 2));
        assertThrows(NullPointerException.class, () -> Jdk8.map("a", null));
        assertEquals(Collections.singleton("a"), Jdk8.copySet(Arrays.asList("a", "a")));
        List<String> mutable = new ArrayList<String>(values);
        List<String> copied = Jdk8.copyList(mutable);
        mutable.clear();
        assertEquals(2, copied.size());
    }

    @Test
    public void loweredRecordsRetainValueAndValidationSemantics() {
        ParsedJson<String> a = new ParsedJson<String>("value", "json"), b = new ParsedJson<String>("value", "json");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("ParsedJson[value=value, json=json]", a.toString());
        assertEquals("value", a.value());
        assertTrue(BatchItemResult.success("ok").isSuccess());
        assertThrows(IllegalArgumentException.class, () -> BatchItemResult.success(null));
        assertEquals(new EmbeddingParameter<String>("key", String.class), new EmbeddingParameter<Integer>("key", Integer.class));
    }

    @Test
    public void loweredRecordsRetainJsonRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ParsedJson<String> original = new ParsedJson<String>("value", "json");
        assertEquals(original, mapper.readValue(mapper.writeValueAsString(original), ParsedJson.class));
        BatchItemResult.Success<String> success = new BatchItemResult.Success<String>("ok");
        assertEquals(success, mapper.readValue(mapper.writeValueAsString(success), BatchItemResult.Success.class));
    }

    @Test
    public void utfWhitespaceAndFailureFutureWorkOnJava8() throws Exception {
        assertTrue(Jdk8.isBlank("\u2003\t "));
        assertEquals("hello", Jdk8.strip("\u2003hello\u2003"));
        RuntimeException cause = new RuntimeException("test");
        ExecutionException error = assertThrows(ExecutionException.class, () -> Jdk8.failedFuture(cause).get());
        assertSame(cause, error.getCause());
        assertEquals("recovered", Jdk8.exceptionallyCompose(Jdk8.<String>failedFuture(cause), e -> CompletableFuture.completedFuture("recovered")).get());
    }

    @Test
    public void java8UsesPlatformThreadFallback() throws Exception {
        assertTrue("Actual Java 8 runtime required", System.getProperty("java.specification.version").equals("1.8"));
        assertFalse(VirtualThreadUtils.isVirtualThreadsSupported());
        ExecutorService pool = VirtualThreadUtils.createVirtualThreadExecutor(Executors::newSingleThreadExecutor);
        try {
            assertEquals("ok", pool.submit(() -> "ok").get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
