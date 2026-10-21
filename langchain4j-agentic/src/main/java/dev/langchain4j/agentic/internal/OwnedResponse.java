package dev.langchain4j.agentic.internal;

import dev.langchain4j.Internal;

/** A deferred operation owned by the current root invocation, rather than an external answer. */
@Internal
public interface OwnedResponse<T> extends DelayedResponse<T> {
    boolean isCancelled();

    // Keep the fixed-baseline direct interfaces of AsyncResponse/StreamingResponse unchanged.
    static boolean isOwned(Object value) {
        return value instanceof OwnedResponse<?> || value instanceof AsyncResponse<?> || value instanceof StreamingResponse;
    }
    static boolean isCancelled(Object value) {
        if (value instanceof OwnedResponse<?>) return ((OwnedResponse<?>) value).isCancelled();
        if (value instanceof AsyncResponse<?>) return ((AsyncResponse<?>) value).isCancelled();
        return value instanceof StreamingResponse && ((StreamingResponse) value).isCancelled();
    }
}
