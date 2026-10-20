package dev.langchain4j.mcp.client.transport.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Per-connection SSE parser; the stream owns the committed cursor across connections. */
final class SseEventReader {
    interface Listener {
        void retry(long millis);
        void event(String type, String data, String id, boolean hasId);
    }
    static void read(InputStream input, BooleanSupplier done, Listener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String type = "message", id = null;
        boolean hasData = false, hasId = false, first = true;
        String line;
        while (!done.getAsBoolean() && (line = reader.readLine()) != null) {
            if (first) {
                first = false;
                if (line.startsWith("\uFEFF")) line = line.substring(1);
            }
            if (line.isEmpty()) {
                // A cursor-only/empty-data event may prime recovery without being a JSON-RPC message.
                if (hasData || hasId) {
                    if (hasData) data.setLength(data.length() - 1);
                    listener.event(type, data.toString(), id, hasId);
                }
                data.setLength(0); type = "message"; id = null; hasData = false; hasId = false;
            } else if (!line.startsWith(":")) {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "data": data.append(value).append('\n'); hasData = true; break;
                    case "event": type = value.isEmpty() ? "message" : value; break;
                    case "id": if (value.indexOf('\0') < 0) { id = value; hasId = true; } break;
                    case "retry":
                        if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                            try { listener.retry(Long.parseLong(value)); }
                            catch (NumberFormatException ignored) { /* Outside timer range. */ }
                        }
                        break;
                    default: break;
                }
            }
        }
        // An unterminated event is not committed; reconnect uses the previous complete event ID.
    }
    private SseEventReader() {}
}
