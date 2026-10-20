package dev.langchain4j.store.embedding.sqlite;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.filter.Filter;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import dev.langchain4j.store.sqlite.internal.SqliteDatabase;
import static dev.langchain4j.internal.ValidationUtils.*;

/**
 * File-backed, transactional TextSegment store with exact Java cosine search (no native vector extension).
 * Each operation owns and closes its JDBC connection. Blocking I/O: async RAG must opt into offloading.
 * A database has one collection and a dimension fixed by its first nonempty write, even after removeAll().
 * IDs are upserted; a batch commits entirely or is rolled back. Metadata filters execute in Java.
 */
public final class SqliteEmbeddingStore implements EmbeddingStore<TextSegment>, AutoCloseable {
    private final SqliteDatabase database;

    private SqliteEmbeddingStore(Builder builder) {
        this.database = new SqliteDatabase(builder.databasePath, builder.busyTimeoutMillis);
        transaction(true, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS lc4j_embedding_config (id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL, dimension INTEGER CHECK(dimension>0))");
                statement.executeUpdate("INSERT OR IGNORE INTO lc4j_embedding_config(id,schema_version) VALUES(1,1)");
                try (ResultSet result = statement.executeQuery("SELECT schema_version FROM lc4j_embedding_config WHERE id=1")) {
                    if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Unsupported SQLite embedding schema version");
                }
                statement.execute("CREATE TABLE IF NOT EXISTS lc4j_embeddings (id TEXT PRIMARY KEY NOT NULL, vector BLOB NOT NULL, segment TEXT, metadata BLOB, CHECK((segment IS NULL AND metadata IS NULL) OR (segment IS NOT NULL AND metadata IS NOT NULL)))");
            }
            return null;
        });
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Path databasePath;
        private int busyTimeoutMillis = 5000;
        public Builder databasePath(Path path) { this.databasePath = path; return this; }
        public Builder databasePath(String path) { return databasePath(Paths.get(ensureNotBlank(path, "databasePath"))); }
        public Builder busyTimeoutMillis(int value) { this.busyTimeoutMillis = value; return this; }
        public SqliteEmbeddingStore build() { return new SqliteEmbeddingStore(this); }
    }

    @Override public String add(Embedding embedding) { return add(embedding, null); }
    @Override public void add(String id, Embedding embedding) { add(id, embedding, null); }
    @Override public String add(Embedding embedding, TextSegment segment) {
        String id = UUID.randomUUID().toString(); add(id, embedding, segment); return id;
    }
    public void add(String id, Embedding embedding, TextSegment segment) {
        addAll(Collections.singletonList(id), Collections.singletonList(embedding), Collections.singletonList(segment));
    }
    @Override public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = generateIds(embeddings == null ? 0 : embeddings.size());
        addAll(ids, embeddings, null); return ids;
    }
    @Override public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        ensureConsistentSizes(ids, embeddings, segments);
        List<Row> rows = new ArrayList<>();
        if (embeddings != null) for (int i = 0; i < embeddings.size(); i++) {
            TextSegment segment = segments == null ? null : segments.get(i);
            rows.add(new Row(ensureNotBlank(ids.get(i), "id"), SqliteCodec.vector(embeddings.get(i)),
                    segment == null ? null : segment.text(), segment == null ? null : SqliteCodec.metadata(segment.metadata())));
        }
        if (rows.isEmpty()) { checkOpen(); return; }
        transaction(true, connection -> {
            Integer dimensions = dimensions(connection);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc4j_embeddings(id,vector,segment,metadata) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET vector=excluded.vector,segment=excluded.segment,metadata=excluded.metadata")) {
                for (Row row : rows) {
                    int size = row.vector.length / Float.BYTES;
                    if (dimensions == null) {
                        dimensions = size;
                        try (PreparedStatement configure = connection.prepareStatement("UPDATE lc4j_embedding_config SET dimension=? WHERE id=1")) {
                            configure.setInt(1, size); configure.executeUpdate();
                        }
                    }
                    if (dimensions != size) throw new IllegalArgumentException("Embedding dimension does not match database dimension " + dimensions);
                    insert.setString(1, row.id); insert.setBytes(2, row.vector);
                    insert.setString(3, row.segment); insert.setBytes(4, row.metadata); insert.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        ensureNotNull(request, "request");
        byte[] queryVector = SqliteCodec.vector(request.queryEmbedding());
        // Snapshot the caller's mutable vector as well as persisted values.
        Embedding query = SqliteCodec.vector(queryVector, queryVector.length / Float.BYTES);
        return transaction(false, connection -> {
            Integer dimension = dimensions(connection);
            if (dimension != null && dimension != query.dimension()) throw new IllegalArgumentException("Query dimension does not match database dimension " + dimension);
            Comparator<EmbeddingMatch<TextSegment>> order = Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score)
                    .thenComparing(EmbeddingMatch::embeddingId, Comparator.reverseOrder());
            PriorityQueue<EmbeddingMatch<TextSegment>> top = new PriorityQueue<>(order);
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT id,vector,segment,metadata FROM lc4j_embeddings")) {
                while (result.next()) {
                    TextSegment segment = segment(result);
                    if (!matches(segment, request.filter())) continue;
                    if (dimension == null) throw new IllegalStateException("Missing SQLite embedding dimension");
                    Embedding embedding = SqliteCodec.vector(result.getBytes("vector"), dimension);
                    double score = RelevanceScore.fromCosineSimilarity(CosineSimilarity.between(embedding, query));
                    if (score >= request.minScore()) {
                        top.add(new EmbeddingMatch<>(score, result.getString("id"), embedding, segment));
                        if (top.size() > request.maxResults()) top.poll();
                    }
                }
            }
            List<EmbeddingMatch<TextSegment>> result = new ArrayList<>(top);
            result.sort(order.reversed());
            return new EmbeddingSearchResult<>(result);
        });
    }

    @Override public void removeAll(Collection<String> ids) {
        Set<String> snapshot = new HashSet<>();
        if (ids != null) for (String id : ids) snapshot.add(ensureNotBlank(id, "id"));
        if (snapshot.isEmpty()) { checkOpen(); return; }
        transaction(true, connection -> { delete(connection, snapshot); return null; });
    }
    @Override public void removeAll(Filter filter) {
        ensureNotNull(filter, "filter");
        transaction(true, connection -> {
            List<String> ids = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT id,segment,metadata FROM lc4j_embeddings")) {
                while (result.next()) if (matches(segment(result), filter)) ids.add(result.getString("id"));
            }
            delete(connection, ids); return null;
        });
    }
    @Override public void removeAll() {
        transaction(true, connection -> {
            try (Statement statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM lc4j_embeddings"); }
            return null;
        });
    }
    public long size() {
        return transaction(false, connection -> {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc4j_embeddings")) {
                result.next(); return result.getLong(1);
            }
        });
    }
    @Override public void close() { database.close(); }

    private static void delete(Connection connection, Collection<String> ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM lc4j_embeddings WHERE id=?")) {
            for (String id : ids) { statement.setString(1, id); statement.executeUpdate(); }
        }
    }
    private static Integer dimensions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT dimension FROM lc4j_embedding_config WHERE id=1")) {
            if (!result.next()) throw new IllegalStateException("Missing SQLite embedding configuration");
            int value = result.getInt(1);
            return result.wasNull() ? null : value;
        }
    }
    private static TextSegment segment(ResultSet result) throws SQLException {
        String text = result.getString("segment");
        return text == null ? null : TextSegment.from(text, SqliteCodec.metadata(result.getBytes("metadata")));
    }
    private static boolean matches(TextSegment segment, Filter filter) {
        return filter == null || (segment != null && filter.test(segment.metadata()));
    }
    private void checkOpen() { database.checkOpen(); }
    private <T> T transaction(boolean write, SqliteDatabase.Work<T> work) {
        return database.transaction(write, work);
    }
    private static final class Row {
        final String id, segment;
        final byte[] vector, metadata;
        Row(String id, byte[] vector, String segment, byte[] metadata) {
            this.id = id; this.vector = vector; this.segment = segment; this.metadata = metadata;
        }
    }
}
