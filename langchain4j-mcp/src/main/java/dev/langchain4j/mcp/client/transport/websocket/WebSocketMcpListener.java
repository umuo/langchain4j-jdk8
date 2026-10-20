package dev.langchain4j.mcp.client.transport.websocket;

import java.io.IOException;
import java.util.function.Consumer;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** OkHttp supplies complete messages after frame reassembly, on a serial socket reader. */
public class WebSocketMcpListener extends WebSocketListener {
    private final Consumer<WebSocket> opened;
    private final Consumer<String> received;
    private final Consumer<Throwable> failed;
    private final Runnable ended;
    public WebSocketMcpListener(Consumer<WebSocket> opened, Consumer<String> received,
            Consumer<Throwable> failed, Runnable ended) {
        this.opened = opened; this.received = received; this.failed = failed; this.ended = ended;
    }
    @Override public void onOpen(WebSocket socket, Response response) { opened.accept(socket); }
    @Override public void onMessage(WebSocket socket, String text) { received.accept(text); }
    @Override public void onMessage(WebSocket socket, ByteString bytes) {
        failed.accept(new IOException("Binary MCP messages are unsupported")); socket.cancel();
    }
    @Override public void onClosing(WebSocket socket, int code, String reason) {
        socket.close(code, reason); failed.accept(new IOException("WebSocket closing: " + code + " " + reason));
    }
    @Override public void onClosed(WebSocket socket, int code, String reason) { ended.run(); }
    @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
        try { failed.accept(error); } finally { if (response != null) response.close(); ended.run(); }
    }
}
