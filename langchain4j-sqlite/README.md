# SQLite embedding store (Java 8 fork)

This new compatibility module implements `EmbeddingStore<TextSegment>` under
`dev.langchain4j.store.embedding.sqlite.SqliteEmbeddingStore`. It uses Xerial SQLite JDBC
3.53.4.0, standard SQLite tables, and exact Java cosine search. It does not require a
SQLite vector extension or a server. Build through `./jdk8/build.sh`, not the upstream root POM.

Import the compatibility BOM and add:

```xml
<dependency>
    <groupId>dev.langchain4j.jdk8</groupId>
    <artifactId>langchain4j-sqlite</artifactId>
</dependency>
```

```java
try (SqliteEmbeddingStore store = SqliteEmbeddingStore.builder()
        .databasePath(Paths.get("/Volumes/Nas/data/catalog.db"))
        .busyTimeoutMillis(5000)
        .build()) {
    store.add("product-1", Embedding.from(new float[] {1, 0}),
            TextSegment.from("apple price is 7 yuan.", Metadata.from("catalog", "public")));
    EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
            .queryEmbedding(Embedding.from(new float[] {1, 0}))
            .filter(metadataKey("catalog").isEqualTo("public"))
            .maxResults(3).minScore(0.8).build());
}
```

Use the imports from the `dev.langchain4j.data.*`, `dev.langchain4j.store.embedding.*`,
`dev.langchain4j.store.embedding.sqlite` packages and `java.nio.file.Paths`, plus the static
`MetadataFilterBuilder.metadataKey` import. The parent directory must exist.

Each file contains one collection. The first nonempty write fixes its vector dimension;
clearing all entries preserves that dimension. IDs are upserted, including replacement of
text and metadata; the last occurrence of a repeated batch ID wins. Batch writes and
removals commit atomically. Empty/null input batches follow the core no-op contract.
Metadata preserves String, Integer, Long, Float, Double and UUID using a versioned primitive
binary format. No Java object deserialization is used. Unknown schema versions and
malformed stored payloads fail explicitly; no automatic schema migration is performed.

Search scans persisted rows and evaluates metadata filters in Java. It uses O(N × dimension)
work and O(k) result memory, orders by descending relevance and then ascending ID for ties,
and uses the same cosine/relevance formula as the in-memory store. This is intended as a
small local persistent store, not an ANN index. Entries without TextSegment contents are
supported, but do not match metadata filters; RAG should ingest TextSegments.

JDBC search is blocking. `searchAsync` retains the core unsupported-async default. Configure
`EmbeddingStoreContentRetriever.builder().offloadBlocking(true)` for Future/Publisher RAG.
Cancellation does not guarantee interruption of an already running JDBC scan; its transaction
and connection close when that operation finishes. Every operation owns its connection;
`close()` waits for active operations, rejects later operations and never deletes the file.
The store uses immediate write transactions, deferred read snapshots and a configurable
SQLite busy timeout, without enabling WAL or changing a user's journal policy.

SQLite JDBC extracts its bundled native library. Keep that extraction on the external disk
with `-Dorg.sqlite.tmpdir=/Volumes/Nas/path/to/existing-native-temp-dir`; the compatibility
build configures this automatically in `jdk8/native-tmp`. The same driver bundles multiple
platform libraries, but this milestone's native runtime verification is macOS/aarch64 on
Java 8. Network filesystem locking, simultaneous multiprocess writers, abrupt termination
and power-loss recovery have not been tested here.

The independent BOM example `jdk8/example/src/main/java/HttpRagExample.java` supports
`write <file>` and `read <file>`. The build runs those in separate Java 8 processes: the writer
only embeds/ingests and exits, while the reader reopens SQLite and completes async RAG
through actual OpenAI HTTP adapters against local fixtures. No remote service credentials
are needed. The writer refuses to overwrite an existing example database.

Driver sources and build target: [Xerial POM](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/pom.xml).
Native extraction configuration: [Xerial usage](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/USAGE.md).

## Persistent chat memory

The same module now includes `dev.langchain4j.store.memory.chat.sqlite.SqliteChatMemoryStore`.
It implements the core `ChatMemoryStore` interface. It can share a file with the embedding
store, using separate tables and schema versions. It atomically replaces or deletes one
conversation snapshot; it does not append messages on `updateMessages`.

```java
ExecutorService io = Executors.newSingleThreadExecutor(); // caller owns the executor
try (SqliteChatMemoryStore store = SqliteChatMemoryStore.builder()
        .databasePath(Paths.get("/Volumes/Nas/data/catalog.db"))
        .asyncExecutor(io) // explicit JDBC offload; omit for synchronous use only
        .build()) {
    MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
            .id("session-1").maxMessages(20).chatMemoryStore(store).build();
    // Configure memory directly or via AiServices.chatMemoryProvider(id -> ...).
} finally {
    io.shutdown();
}
```

Use the `SqliteChatMemoryStore`, `MessageWindowChatMemory`, `ExecutorService` and `Executors`
imports. Without `asyncExecutor`, async store methods return the core unsupported-async
failure; they do not silently block the caller. With an executor, messages are serialized
before an asynchronous write is queued, so later mutation of the caller's list cannot alter
the request. Cancelling a queued store future prevents its work from starting. `close()` does
not shut down a supplied executor; it remains the application's responsibility.

Memory IDs must be non-null String, Integer, Long or UUID. Each type has its own namespace:
`"1"`, `1` and `1L` identify different conversations. Unsupported objects fail explicitly
instead of using an unstable or colliding `toString()`. Empty strings are valid IDs.

Snapshots use the existing `ChatMessageSerializer` / `ChatMessageDeserializer` JSON codec,
including its SPI and attribute semantics, with payload format version 1. System, user,
assistant, tool call/result and custom message round trips are tested. Lists returned by
`getMessages` are independent and mutable; modifying one does not persist changes. Missing
conversations return an empty list. Invalid JSON, null messages and unknown schema/format
versions fail explicitly without silently deleting a conversation. No automatic schema
migration is attempted.

`deleteMessages(id)` or `ChatMemory.clear()` deletes persisted history for that ID.
`ChatMemoryAccess.evictChatMemory(id)` only removes the AI Service's in-process memory
instance; subsequent calls reload persistent history. Message-window eviction and removal
of orphaned tool results retain the core behavior.

The SQLite transaction protects the whole snapshot, not a complete model turn or the
memory's read-modify-write sequence. Serialize concurrent AI Service calls for the same
memory ID at the application layer to avoid lost updates. Different conversations remain
isolated. Cancellation does not roll back already committed history or guarantee interruption
of JDBC work that has started. Windowed memory and AI Services now forward cancellation
through the memory read/assembly/write stages, preventing cancelled queued operations from
persisting a new question. These changes do not alter Guardrails behavior.

The BOM example adds `memory-write <file>` / `memory-read <file>`. Separate Java 8 processes
persist vector data and four messages (including an automatic tool request/result), then
restore that history into a five-message model request and finish with six stored messages.
Both use the real OpenAI HTTP adapters against local fixtures; no credentials or remote
model are required. Existing `write` / `read` vector-only modes are retained.
