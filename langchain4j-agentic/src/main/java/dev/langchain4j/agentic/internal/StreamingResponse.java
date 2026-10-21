package dev.langchain4j.agentic.internal;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.CompletableFuture;

public class StreamingResponse implements DelayedResponse<String> {

    private final CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();

    public StreamingResponse(TokenStream tokenStream) {
        dev.langchain4j.internal.CancellationContext.Registration registration =
                dev.langchain4j.internal.CancellationContext.register(() -> futureResponse.cancel(false));
        futureResponse.whenComplete((value, error) -> registration.close());
        try {
            if (futureResponse.isCancelled()) return;
            tokenStream
                    .onCompleteResponse(futureResponse::complete)
                    .onError(futureResponse::completeExceptionally)
                    .start();
        } catch (RuntimeException | Error failure) {
            futureResponse.completeExceptionally(failure);
            throw failure;
        }
    }

    @dev.langchain4j.Internal
    public boolean isCancelled() { return futureResponse.isCancelled(); }

    @Override
    public boolean isDone() {
        return futureResponse.isDone();
    }

    @Override
    public String blockingGet() {
        return DelayedResponse.join(futureResponse).aiMessage().text();
    }

    @Override
    public String toString() {
        return result().toString();
    }
}
