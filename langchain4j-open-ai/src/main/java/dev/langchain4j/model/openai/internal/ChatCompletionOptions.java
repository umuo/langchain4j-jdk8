package dev.langchain4j.model.openai.internal;

import dev.langchain4j.Internal;

@Internal
public final class ChatCompletionOptions {

    @com.fasterxml.jackson.annotation.JsonProperty("returnThinking")
    private final boolean returnThinking;

    public boolean returnThinking() {
        return returnThinking;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("accumulateToolCallId")
    private final boolean accumulateToolCallId;

    public boolean accumulateToolCallId() {
        return accumulateToolCallId;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public ChatCompletionOptions(@com.fasterxml.jackson.annotation.JsonProperty("returnThinking") boolean returnThinking, @com.fasterxml.jackson.annotation.JsonProperty("accumulateToolCallId") boolean accumulateToolCallId) {
        this.returnThinking = returnThinking;
        this.accumulateToolCallId = accumulateToolCallId;
    }

    public boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(returnThinking, ((ChatCompletionOptions) obj).returnThinking) && java.util.Objects.equals(accumulateToolCallId, ((ChatCompletionOptions) obj).accumulateToolCallId));
    }

    public int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(returnThinking)) + java.util.Objects.hashCode(accumulateToolCallId));
    }

    public String toString() {
        return "ChatCompletionOptions[" + "returnThinking=" + returnThinking + ", accumulateToolCallId=" + accumulateToolCallId + "]";
    }
}
