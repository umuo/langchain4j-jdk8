package dev.langchain4j.agentic.observability;

/**
 * Represents the outcome returned by an A2A (agent-to-agent) streaming client listener.
 *
 * <p>The result allows the listener to control whether the client should continue
 * consuming the streaming response and, when stopping, optionally provide a final
 * response to the caller.
 *
 * <p>This is particularly useful when the caller is only interested in certain
 * events or does not need to observe the entire execution. In such cases, the
 * listener can request the client to stop consuming the stream and return a
 * response immediately, while the A2A server-side task may continue executing
 * asynchronously.
 *
 * <p>Consumers should use the provided factory methods {@link #continueStreaming()},
 * {@link #stopWithResponse(String)}, and {@link #stopWithCurrentArtifacts()} to
 * clearly express the desired behavior.
 *
 * <p>Typical usage:
 * <pre>
 *
 * // Continue consuming the streaming response
 * return A2AStreamingClientListenerResult.continueStreaming();
 *
 * // Stop consuming the stream and return immediately
 * // while the server-side task may continue asynchronously
 * return A2AStreamingClientListenerResult.stopWithResponse("Task accepted");
 * </pre>
 */
public final class A2AStreamingClientListenerResult {

    /**
     * Creates a result that signals the streaming process should continue.
     *
     * @return an instance with {@code stop=false} and {@code response=null}
     */
    public static A2AStreamingClientListenerResult continueStreaming() {
        return new A2AStreamingClientListenerResult(false, null, false);
    }

    /**
     * Creates a result that signals the streaming process should stop and provides
     * an optional final response to return to the caller.
     *
     * @param response the final response to use when stopping; may be {@code null} to
     *                 indicate stopping without providing a message
     * @return an instance with {@code stop=true} and the provided {@code response}
     */
    public static A2AStreamingClientListenerResult stopWithResponse(String response) {
        return new A2AStreamingClientListenerResult(true, response, false);
    }

    /**
     * Creates a result that signals the streaming process should stop and uses the
     * artifacts received so far as the final response. The artifacts are extracted
     * using the same {@code extractText} logic used by the default A2A client
     * response handling.
     *
     * @return an instance with {@code stop=true} and the response extracted from
     *         the artifacts received so far
     */
    public static A2AStreamingClientListenerResult stopWithCurrentArtifacts() {
        return new A2AStreamingClientListenerResult(true, null, true);
    }

    private final boolean stop;

    public boolean stop() {
        return stop;
    }

    private final String response;

    public String response() {
        return response;
    }

    private final boolean withCurrentArtifacts;

    public boolean withCurrentArtifacts() {
        return withCurrentArtifacts;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public A2AStreamingClientListenerResult(@com.fasterxml.jackson.annotation.JsonProperty("stop") boolean stop, @com.fasterxml.jackson.annotation.JsonProperty("response") String response, @com.fasterxml.jackson.annotation.JsonProperty("withCurrentArtifacts") boolean withCurrentArtifacts) {
        this.stop = stop;
        this.response = response;
        this.withCurrentArtifacts = withCurrentArtifacts;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(stop, ((A2AStreamingClientListenerResult) obj).stop) && java.util.Objects.equals(response, ((A2AStreamingClientListenerResult) obj).response) && java.util.Objects.equals(withCurrentArtifacts, ((A2AStreamingClientListenerResult) obj).withCurrentArtifacts));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(stop)) + java.util.Objects.hashCode(response)) + java.util.Objects.hashCode(withCurrentArtifacts));
    }

    public final String toString() {
        return "A2AStreamingClientListenerResult[" + "stop=" + stop + ", response=" + response + ", withCurrentArtifacts=" + withCurrentArtifacts + "]";
    }
}
