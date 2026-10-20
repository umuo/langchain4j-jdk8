package dev.langchain4j.internal.reactive;

public final class TubeConfiguration {

    private int bufferSize = 256;

    public TubeConfiguration withBackpressureStrategy(BackpressureStrategy strategy) {
        java.util.Objects.requireNonNull(strategy);
        return this;
    }

    public TubeConfiguration withBufferSize(int size) {
        if (size < 1)
            throw new IllegalArgumentException("positive buffer size required");
        bufferSize = size;
        return this;
    }

    int bufferSize() {
        return bufferSize;
    }
}
