package dev.langchain4j.store.memory.chat.sqlite;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.sqlite.internal.SqliteDatabase;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static dev.langchain4j.internal.ValidationUtils.*;

/**
 * Persistent conversation snapshots using the core message JSON codec. String/Integer/Long/UUID IDs
 * have separate namespaces. A write atomically replaces one conversation, not a read-modify-write turn.
 * JDBC is blocking; async methods require an explicitly supplied, caller-owned Executor.
 */
public final class SqliteChatMemoryStore implements ChatMemoryStore, AutoCloseable {
    private final SqliteDatabase database;
    private final Executor asyncExecutor;

    private SqliteChatMemoryStore(Builder builder) {
        database = new SqliteDatabase(builder.databasePath, builder.busyTimeoutMillis);
        asyncExecutor = builder.asyncExecutor;
        database.transaction(true, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS lc4j_chat_memory_config(id INTEGER PRIMARY KEY CHECK(id=1),schema_version INTEGER NOT NULL)");
                statement.executeUpdate("INSERT OR IGNORE INTO lc4j_chat_memory_config(id,schema_version) VALUES(1,1)");
                try (ResultSet result = statement.executeQuery("SELECT schema_version FROM lc4j_chat_memory_config WHERE id=1")) {
                    if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Unsupported SQLite chat memory schema version");
                }
                statement.execute("CREATE TABLE IF NOT EXISTS lc4j_chat_memories(memory_id TEXT PRIMARY KEY NOT NULL,format_version INTEGER NOT NULL,messages TEXT NOT NULL)");
            }
            return null;
        });
    }

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private Path databasePath;
        private int busyTimeoutMillis = 5000;
        private Executor asyncExecutor;
        public Builder databasePath(Path path) { databasePath = path; return this; }
        public Builder databasePath(String path) { return databasePath(Paths.get(ensureNotBlank(path, "databasePath"))); }
        public Builder busyTimeoutMillis(int value) { busyTimeoutMillis = value; return this; }
        public Builder asyncExecutor(Executor value) { asyncExecutor = ensureNotNull(value, "asyncExecutor"); return this; }
        public SqliteChatMemoryStore build() { return new SqliteChatMemoryStore(this); }
    }

    @Override public List<ChatMessage> getMessages(Object memoryId) {
        String key = key(memoryId);
        return database.transaction(false, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT format_version,messages FROM lc4j_chat_memories WHERE memory_id=?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return new ArrayList<>();
                    if (result.getInt(1) != 1) throw new IllegalStateException("Unsupported SQLite chat message format version");
                    try { return snapshot(ChatMessageDeserializer.messagesFromJson(requireJson(result.getString(2)))); }
                    catch (RuntimeException error) { throw new IllegalStateException("Corrupt SQLite chat messages", error); }
                }
            }
        });
    }

    @Override public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        write(key(memoryId), encode(messages));
    }

    private void write(String key, String json) {
        database.transaction(true, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO lc4j_chat_memories(memory_id,format_version,messages) VALUES(?,1,?) ON CONFLICT(memory_id) DO UPDATE SET format_version=1,messages=excluded.messages")) {
                statement.setString(1, key); statement.setString(2, json); statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public void deleteMessages(Object memoryId) {
        String key = key(memoryId);
        database.transaction(true, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM lc4j_chat_memories WHERE memory_id=?")) {
                statement.setString(1, key); statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public CompletableFuture<List<ChatMessage>> getMessagesAsync(Object memoryId) {
        if (asyncExecutor == null) return ChatMemoryStore.super.getMessagesAsync(memoryId);
        return submit(() -> getMessages(memoryId));
    }

    @Override public CompletableFuture<Void> updateMessagesAsync(Object memoryId, List<ChatMessage> messages) {
        if (asyncExecutor == null) return ChatMemoryStore.super.updateMessagesAsync(memoryId, messages);
        try {
            // Capture the request before returning so queued writes do not observe later caller mutations.
            String key = key(memoryId), json = encode(messages);
            return submit(() -> { write(key, json); return null; });
        } catch (Throwable error) { return dev.langchain4j.internal.Jdk8.failedFuture(error); }
    }

    @Override public CompletableFuture<Void> deleteMessagesAsync(Object memoryId) {
        if (asyncExecutor == null) return ChatMemoryStore.super.deleteMessagesAsync(memoryId);
        return submit(() -> { deleteMessages(memoryId); return null; });
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        try { database.checkOpen(); return CompletableFuture.supplyAsync(task, asyncExecutor); }
        catch (Throwable error) { return dev.langchain4j.internal.Jdk8.failedFuture(error); }
    }

    @Override public void close() { database.close(); }

    private static String key(Object id) {
        ensureNotNull(id, "memoryId");
        if (id instanceof String) return "s:" + id;
        if (id instanceof Integer) return "i:" + id;
        if (id instanceof Long) return "l:" + id;
        if (id instanceof UUID) return "u:" + id;
        throw new IllegalArgumentException("SQLite memoryId must be a String, Integer, Long or UUID");
    }
    private static List<ChatMessage> snapshot(List<ChatMessage> messages) {
        List<ChatMessage> copy = new ArrayList<>(ensureNotNull(messages, "messages"));
        for (ChatMessage message : copy) ensureNotNull(message, "message");
        return copy;
    }
    private static String encode(List<ChatMessage> messages) {
        return requireJson(ChatMessageSerializer.messagesToJson(snapshot(messages)));
    }
    private static String requireJson(String json) {
        if (json == null || json.trim().isEmpty() || json.trim().equals("null"))
            throw new IllegalStateException("Chat message codec must return a non-null JSON snapshot");
        return json;
    }
}
