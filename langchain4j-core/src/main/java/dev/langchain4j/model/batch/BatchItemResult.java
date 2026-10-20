package dev.langchain4j.model.batch;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import dev.langchain4j.Experimental;
import org.jspecify.annotations.Nullable;

/**
 * Represents the outcome of a single request within a batch operation.
 *
 * <p>Each batch request is processed independently and either succeeds (producing a response) or
 * fails (producing a {@link BatchError}). The results of a batch are returned in the same order as
 * the input requests, so the i-th {@code BatchItemResult} corresponds to the i-th submitted request,
 * allowing the caller to correlate every outcome with its originating request even when only some
 * requests fail.</p>
 *
 * @param <T> the type of a successful response payload (e.g., {@code ChatResponse}, {@code Embedding})
 * @see BatchResponse#results()
 */
@Experimental
public interface BatchItemResult<T> {

    /**
     * Returns {@code true} if this request succeeded.
     */
    boolean isSuccess();

    /**
     * Returns the successful response, or {@code null} if this request failed.
     */
    @Nullable
    T response();

    /**
     * Returns the error, or {@code null} if this request succeeded.
     */
    @Nullable
    BatchError error();

    /**
     * Creates a successful {@link BatchItemResult}.
     */
    static <T> BatchItemResult<T> success(T response) {
        return new Success<>(response);
    }

    /**
     * Creates a failed {@link BatchItemResult}.
     */
    static <T> BatchItemResult<T> failure(BatchError error) {
        return new Failure<>(error);
    }

    /**
     * The successful outcome of a single batch request.
     *
     * @param <T>      the type of the response payload
     * @param response the successful response, never {@code null}
     */
    final static public class Success<T> implements BatchItemResult<T> {

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        @Nullable
        public BatchError error() {
            return null;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("response")
        private final T response;

        public T response() {
            return response;
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Success(@com.fasterxml.jackson.annotation.JsonProperty("response") T response) {
            ensureNotNull(response, "response");
            this.response = response;
        }

        public boolean equals(Object obj) {
            return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(response, ((Success<?>) obj).response));
        }

        public int hashCode() {
            return (31 * 0 + java.util.Objects.hashCode(response));
        }

        public String toString() {
            return "Success[" + "response=" + response + "]";
        }
    }

    /**
     * The failed outcome of a single batch request.
     *
     * @param <T>   the type of the response payload that would have been produced on success
     * @param error the error describing the failure, never {@code null}
     */
    final static public class Failure<T> implements BatchItemResult<T> {

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        @Nullable
        public T response() {
            return null;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("error")
        private final BatchError error;

        public BatchError error() {
            return error;
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Failure(@com.fasterxml.jackson.annotation.JsonProperty("error") BatchError error) {
            ensureNotNull(error, "error");
            this.error = error;
        }

        public boolean equals(Object obj) {
            return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(error, ((Failure<?>) obj).error));
        }

        public int hashCode() {
            return (31 * 0 + java.util.Objects.hashCode(error));
        }

        public String toString() {
            return "Failure[" + "error=" + error + "]";
        }
    }
}
