package dev.langchain4j.mcp.client.transport.stdio;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProcessStderrHandler implements Runnable, Closeable {

    private final Process process;
    // Resolved per instance (in the constructor) rather than in a static initializer so that the
    // logger reflects any LoggerFactory configuration in effect when the handler is created, and is
    // not fixed at class-load time (which also makes this class testable in isolation).
    private final Logger log;
    private volatile boolean closed = false;

    public ProcessStderrHandler(final Process process) {
        this.process = process;
        this.log = LoggerFactory.getLogger(ProcessStderrHandler.class);
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) log.debug("[STDERR] {}", line);
        } catch (IOException e) {
            if (!closed) log.debug("MCP subprocess stderr closed", e);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }
}
