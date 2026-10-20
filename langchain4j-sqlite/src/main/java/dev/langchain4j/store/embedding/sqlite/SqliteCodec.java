package dev.langchain4j.store.embedding.sqlite;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned primitive encoding; no Java object deserialization or polymorphic type loading. */
final class SqliteCodec {
    private SqliteCodec() { }

    static byte[] vector(Embedding embedding) {
        float[] values = Objects.requireNonNull(embedding, "embedding").vector();
        if (values.length == 0) throw new IllegalArgumentException("Vector dimension must be positive");
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(values.length, Float.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Vector values must be finite");
            bytes.putFloat(value);
        }
        return bytes.array();
    }

    static Embedding vector(byte[] bytes, int dimensions) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0 || bytes.length / Float.BYTES != dimensions)
            throw new IllegalStateException("Corrupt SQLite embedding vector dimensions");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            values[i] = input.getFloat();
            if (!Float.isFinite(values[i])) throw new IllegalStateException("Corrupt SQLite embedding vector value");
        }
        return Embedding.from(values);
    }

    static byte[] metadata(Metadata metadata) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            Map<String, Object> values = new TreeMap<>(metadata.toMap());
            output.writeInt(1);
            output.writeInt(values.size());
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                string(output, entry.getKey());
                Object value = entry.getValue();
                if (value instanceof String) { output.writeByte(1); string(output, (String) value); }
                else if (value instanceof Integer) { output.writeByte(2); output.writeInt((Integer) value); }
                else if (value instanceof Long) { output.writeByte(3); output.writeLong((Long) value); }
                else if (value instanceof Float) { output.writeByte(4); output.writeFloat((Float) value); }
                else if (value instanceof Double) { output.writeByte(5); output.writeDouble((Double) value); }
                else if (value instanceof UUID) {
                    output.writeByte(6);
                    output.writeLong(((UUID) value).getMostSignificantBits());
                    output.writeLong(((UUID) value).getLeastSignificantBits());
                } else throw new IllegalArgumentException("Unsupported SQLite metadata value type");
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    static Metadata metadata(byte[] bytes) {
        if (bytes == null) throw new IllegalStateException("Missing SQLite segment metadata");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != 1) throw new IOException("Unknown metadata format version");
            int count = input.readInt();
            if (count < 0 || count > input.available() / 5) throw new IOException("Invalid metadata entry count");
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String key = string(input);
                Object value;
                switch (input.readUnsignedByte()) {
                    case 1: value = string(input); break;
                    case 2: value = input.readInt(); break;
                    case 3: value = input.readLong(); break;
                    case 4: value = input.readFloat(); break;
                    case 5: value = input.readDouble(); break;
                    case 6: value = new UUID(input.readLong(), input.readLong()); break;
                    default: throw new IOException("Unknown metadata value tag");
                }
                if (values.put(key, value) != null) throw new IOException("Duplicate metadata key");
            }
            if (input.available() != 0) throw new IOException("Trailing metadata bytes");
            return new Metadata(values);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Corrupt SQLite segment metadata", error);
        }
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String string(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) throw new IOException("Invalid metadata string length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
