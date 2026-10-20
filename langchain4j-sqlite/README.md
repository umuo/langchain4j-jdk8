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
