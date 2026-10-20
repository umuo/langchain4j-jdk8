package dev.langchain4j.model.batch;

import dev.langchain4j.Experimental;
import java.util.List;

/**
 * Represents the possible states of a batch job.
 */
@Experimental
public enum BatchState {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNSPECIFIED;

    private static final List<BatchState> TERMINAL_BATCH_STATES = dev.langchain4j.internal.Jdk8.list(SUCCEEDED, FAILED, CANCELLED, EXPIRED);

    public boolean isTerminal() {
        return TERMINAL_BATCH_STATES.contains(this);
    }
}
