package dev.langchain4j.service;

import static dev.langchain4j.internal.Exceptions.runtime;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static dev.langchain4j.service.AiServiceParamsUtil.chatRequestParameters;
import dev.langchain4j.Internal;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.CapturedContextSupport;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;
import dev.langchain4j.service.tool.ToolServiceContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles response from a language model for AI Service that is streamed token-by-token. Handles both regular (text)
 * responses and responses with the request to execute one or multiple tools.
 */
@Internal
class AiServiceStreamingResponseHandler implements StreamingChatResponseHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AiServiceStreamingResponseHandler.class);

    private final StreamControl streamControl;

    private final ChatExecutor chatExecutor;

    private final ChatRequest chatRequest;

    private final AiServiceContext context;

    private final InvocationContext invocationContext;

    private final GuardrailRequestParams commonGuardrailParams;

    private final Object methodKey;

    private final Consumer<String> partialResponseHandler;

    private final BiConsumer<PartialResponse, PartialResponseContext> partialResponseWithContextHandler;

    private final Consumer<PartialThinking> partialThinkingHandler;

    private final BiConsumer<PartialThinking, PartialThinkingContext> partialThinkingWithContextHandler;

    private final Consumer<PartialToolCall> partialToolCallHandler;

    private final BiConsumer<PartialToolCall, PartialToolCallContext> partialToolCallWithContextHandler;

    private final Consumer<BeforeToolExecution> beforeToolExecutionHandler;

    private final Consumer<Object> rawEventHandler;

    private final Consumer<ToolExecution> toolExecutionHandler;

    private final Consumer<ChatResponse> intermediateResponseHandler;

    private final Consumer<ChatResponse> completeResponseHandler;

    private final Consumer<Throwable> errorHandler;

    private final ChatMemory temporaryMemory;

    private final TokenUsage tokenUsage;

    private final ToolServiceContext toolServiceContext;

    private final Map<String, ToolExecutor> toolExecutors;

    private final ToolArgumentsErrorHandler toolArgumentsErrorHandler;

    private final ToolExecutionErrorHandler toolExecutionErrorHandler;

    private final Executor toolExecutor;

    private final Executor completeResponseProcessingExecutor;

    private final Queue<Future<ToolRequestResult>> toolExecutionFutures = new ConcurrentLinkedQueue<>();

    private final List<String> responseBuffer = new ArrayList<>();

    private final boolean hasOutputGuardrails;

    private int toolCallingRoundTripsLeft;

    private final static class ToolRequestResult {

        private final ToolExecutionRequest request;

        public ToolExecutionRequest request() {
            return request;
        }

        private final ToolExecutionResult result;

        public ToolExecutionResult result() {
            return result;
        }

        public ToolRequestResult(ToolExecutionRequest request, ToolExecutionResult result) {
            this.request = request;
            this.result = result;
        }

        public boolean equals(Object obj) {
            return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(request, ((ToolRequestResult) obj).request) && java.util.Objects.equals(result, ((ToolRequestResult) obj).result));
        }

        public int hashCode() {
            return (31 * (31 * 0 + java.util.Objects.hashCode(request)) + java.util.Objects.hashCode(result));
        }

        public String toString() {
            return "ToolRequestResult[" + "request=" + request + ", result=" + result + "]";
        }
    }

    AiServiceStreamingResponseHandler(ChatRequest chatRequest, ChatExecutor chatExecutor, AiServiceContext context, InvocationContext invocationContext, Consumer<String> partialResponseHandler, BiConsumer<PartialResponse, PartialResponseContext> partialResponseWithContextHandler, Consumer<PartialThinking> partialThinkingHandler, BiConsumer<PartialThinking, PartialThinkingContext> partialThinkingWithContextHandler, Consumer<PartialToolCall> partialToolCallHandler, BiConsumer<PartialToolCall, PartialToolCallContext> partialToolCallWithContextHandler, Consumer<BeforeToolExecution> beforeToolExecutionHandler, Consumer<Object> rawEventHandler, Consumer<ToolExecution> toolExecutionHandler, Consumer<ChatResponse> intermediateResponseHandler, Consumer<ChatResponse> completeResponseHandler, Consumer<Throwable> errorHandler, ChatMemory temporaryMemory, TokenUsage tokenUsage, ToolServiceContext toolServiceContext, int toolCallingRoundTripsLeft, ToolArgumentsErrorHandler toolArgumentsErrorHandler, ToolExecutionErrorHandler toolExecutionErrorHandler, Executor toolExecutor, Executor completeResponseProcessingExecutor, GuardrailRequestParams commonGuardrailParams, Object methodKey) {
        this(chatRequest, chatExecutor, context, invocationContext, partialResponseHandler, partialResponseWithContextHandler, partialThinkingHandler, partialThinkingWithContextHandler, partialToolCallHandler, partialToolCallWithContextHandler, beforeToolExecutionHandler, rawEventHandler, toolExecutionHandler, intermediateResponseHandler, completeResponseHandler, errorHandler, temporaryMemory, tokenUsage, toolServiceContext, toolCallingRoundTripsLeft, toolArgumentsErrorHandler, toolExecutionErrorHandler, toolExecutor, completeResponseProcessingExecutor, commonGuardrailParams, methodKey, new StreamControl(dev.langchain4j.internal.CancellationContext.current()));
    }

    private AiServiceStreamingResponseHandler(ChatRequest chatRequest, ChatExecutor chatExecutor, AiServiceContext context, InvocationContext invocationContext, Consumer<String> partialResponseHandler, BiConsumer<PartialResponse, PartialResponseContext> partialResponseWithContextHandler, Consumer<PartialThinking> partialThinkingHandler, BiConsumer<PartialThinking, PartialThinkingContext> partialThinkingWithContextHandler, Consumer<PartialToolCall> partialToolCallHandler, BiConsumer<PartialToolCall, PartialToolCallContext> partialToolCallWithContextHandler, Consumer<BeforeToolExecution> beforeToolExecutionHandler, Consumer<Object> rawEventHandler, Consumer<ToolExecution> toolExecutionHandler, Consumer<ChatResponse> intermediateResponseHandler, Consumer<ChatResponse> completeResponseHandler, Consumer<Throwable> errorHandler, ChatMemory temporaryMemory, TokenUsage tokenUsage, ToolServiceContext toolServiceContext, int toolCallingRoundTripsLeft, ToolArgumentsErrorHandler toolArgumentsErrorHandler, ToolExecutionErrorHandler toolExecutionErrorHandler, Executor toolExecutor, Executor completeResponseProcessingExecutor, GuardrailRequestParams commonGuardrailParams, Object methodKey, StreamControl streamControl) {
        this.streamControl = streamControl;
        this.chatRequest = ensureNotNull(chatRequest, "chatRequest");
        this.chatExecutor = ensureNotNull(chatExecutor, "chatExecutor");
        this.context = ensureNotNull(context, "context");
        this.invocationContext = ensureNotNull(invocationContext, "invocationContext");
        this.methodKey = methodKey;
        this.partialResponseHandler = partialResponseHandler;
        this.partialResponseWithContextHandler = partialResponseWithContextHandler;
        this.partialThinkingHandler = partialThinkingHandler;
        this.partialThinkingWithContextHandler = partialThinkingWithContextHandler;
        this.partialToolCallHandler = partialToolCallHandler;
        this.partialToolCallWithContextHandler = partialToolCallWithContextHandler;
        this.intermediateResponseHandler = intermediateResponseHandler;
        this.completeResponseHandler = completeResponseHandler;
        this.beforeToolExecutionHandler = beforeToolExecutionHandler;
        this.rawEventHandler = rawEventHandler;
        this.toolExecutionHandler = toolExecutionHandler;
        this.errorHandler = errorHandler;
        this.temporaryMemory = temporaryMemory;
        this.tokenUsage = tokenUsage;
        this.commonGuardrailParams = commonGuardrailParams;
        this.toolServiceContext = toolServiceContext;
        this.toolExecutors = toolServiceContext != null ? toolServiceContext.toolExecutors() : dev.langchain4j.internal.Jdk8.map();
        this.toolArgumentsErrorHandler = ensureNotNull(toolArgumentsErrorHandler, "toolArgumentsErrorHandler");
        this.toolExecutionErrorHandler = ensureNotNull(toolExecutionErrorHandler, "toolExecutionErrorHandler");
        this.toolExecutor = toolExecutor;
        this.completeResponseProcessingExecutor = completeResponseProcessingExecutor;
        this.hasOutputGuardrails = context.guardrailService().hasOutputGuardrails(methodKey);
        this.toolCallingRoundTripsLeft = toolCallingRoundTripsLeft;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        if (streamControl.stopped()) return;
        // If we're using output guardrails, then buffer the partial response until the guardrails have completed
        if (hasOutputGuardrails) {
            responseBuffer.add(partialResponse);
        } else if (partialResponseHandler != null) {
            caller(() -> partialResponseHandler.accept(partialResponse));
        } else if (partialResponseWithContextHandler != null) {
            PartialResponseContext context = new PartialResponseContext(new CancellationUnsupportedStreamingHandle());
            caller(() -> partialResponseWithContextHandler.accept(new PartialResponse(partialResponse), new PartialResponseContext(streamControl.handle(context.streamingHandle()))));
        }
    }

    @Override
    public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        if (streamControl.stopped()) return;
        // If we're using output guardrails, then buffer the partial response until the guardrails have completed
        if (hasOutputGuardrails) {
            responseBuffer.add(partialResponse.text());
        } else if (partialResponseHandler != null) {
            caller(() -> partialResponseHandler.accept(partialResponse.text()));
        } else if (partialResponseWithContextHandler != null) {
            caller(() -> partialResponseWithContextHandler.accept(partialResponse, new PartialResponseContext(streamControl.handle(context.streamingHandle()))));
        }
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking) {
        if (streamControl.stopped()) return;
        if (partialThinkingHandler != null) {
            caller(() -> partialThinkingHandler.accept(partialThinking));
        } else if (partialThinkingWithContextHandler != null) {
            PartialThinkingContext context = new PartialThinkingContext(new CancellationUnsupportedStreamingHandle());
            caller(() -> partialThinkingWithContextHandler.accept(partialThinking, new PartialThinkingContext(streamControl.handle(context.streamingHandle()))));
        }
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        if (streamControl.stopped()) return;
        if (partialThinkingHandler != null) {
            caller(() -> partialThinkingHandler.accept(partialThinking));
        } else if (partialThinkingWithContextHandler != null) {
            caller(() -> partialThinkingWithContextHandler.accept(partialThinking, new PartialThinkingContext(streamControl.handle(context.streamingHandle()))));
        }
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall) {
        if (streamControl.stopped()) return;
        if (partialToolCallHandler != null) {
            caller(() -> partialToolCallHandler.accept(partialToolCall));
        } else if (partialToolCallWithContextHandler != null) {
            PartialToolCallContext context = new PartialToolCallContext(new CancellationUnsupportedStreamingHandle());
            caller(() -> partialToolCallWithContextHandler.accept(partialToolCall, new PartialToolCallContext(streamControl.handle(context.streamingHandle()))));
        }
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        if (streamControl.stopped()) return;
        if (partialToolCallHandler != null) {
            caller(() -> partialToolCallHandler.accept(partialToolCall));
        } else if (partialToolCallWithContextHandler != null) {
            caller(() -> partialToolCallWithContextHandler.accept(partialToolCall, new PartialToolCallContext(streamControl.handle(context.streamingHandle()))));
        }
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        if (streamControl.stopped()) return;
        if (toolExecutor != null) {
            ToolExecutionRequest toolRequest = completeToolCall.toolExecutionRequest();
            java.util.concurrent.CompletableFuture<dev.langchain4j.service.AiServiceStreamingResponseHandler.ToolRequestResult> future = dev.langchain4j.invocation.CapturedContextSupport.supplyAsync(() -> {
                ToolExecutionResult toolResult = execute(toolRequest);
                return new ToolRequestResult(toolRequest, toolResult);
            }, toolExecutor, invocationContext);
            toolExecutionFutures.add(future);
            streamControl.track(future);
        }
    }

    @Override
    public void onUnmappedRawEvent(Object rawEvent) {
        if (streamControl.stopped()) return;
        if (rawEventHandler != null) {
            caller(() -> rawEventHandler.accept(rawEvent));
        }
    }

    private <T> void fireInvocationComplete(T result) {
        context.eventListenerRegistrar.fireEvent(AiServiceCompletedEvent.builder().invocationContext(invocationContext).result(result).build());
    }

    private void fireResponseReceivedEvent(ChatResponse chatResponse) {
        context.eventListenerRegistrar.fireEvent(AiServiceResponseReceivedEvent.builder().invocationContext(invocationContext).request(chatRequest).response(chatResponse).build());
    }

    private void fireRequestIssuedEvent(ChatRequest chatRequest) {
        context.eventListenerRegistrar.fireEvent(AiServiceRequestIssuedEvent.builder().invocationContext(invocationContext).request(chatRequest).build());
    }

    private void fireErrorReceived(Throwable error) {
        context.eventListenerRegistrar.fireEvent(AiServiceErrorEvent.builder().invocationContext(invocationContext).error(error).build());
    }

    @Override
    public void onCompleteResponse(ChatResponse chatResponse) {
        if (streamControl.stopped()) return;
        if (completeResponseProcessingExecutor == null) {
            processCompleteResponse(chatResponse);
            return;
        }
        try {
            CompletableFuture<Void> completion = CapturedContextSupport.supplyAsync(() -> {
                processCompleteResponse(chatResponse);
                return null;
            }, completeResponseProcessingExecutor, invocationContext);
            streamControl.track(completion);
            completion.whenComplete((ignored, error) -> {
                if (error != null) reportCompletionFailure(dev.langchain4j.internal.Exceptions.unwrapCompletionException(error));
            });
        } catch (Exception e) {
            reportCompletionFailure(e);
        }
    }

    private void reportCompletionFailure(Throwable error) {
        if (streamControl.successfulTerminal()) {
            // Completion was already delivered; retain the failure without a second terminal.
            Thread current = Thread.currentThread();
            current.getUncaughtExceptionHandler().uncaughtException(current, error);
            return;
        }
        try {
            CapturedContextSupport.runWithOwner(invocationContext, () -> onError(error));
        } catch (RuntimeException | Error callbackFailure) {
            // CompletableFuture callbacks capture exceptions; retain the executor's uncaught-error channel.
            Thread current = Thread.currentThread();
            current.getUncaughtExceptionHandler().uncaughtException(current, callbackFailure);
        }
    }

    private void processCompleteResponse(ChatResponse chatResponse) {
        if (streamControl.stopped()) return;
        fireResponseReceivedEvent(chatResponse);
        AiMessage aiMessage = chatResponse.aiMessage();
        addToMemory(aiMessage);
        if (aiMessage.hasToolExecutionRequests()) {
            if (toolCallingRoundTripsLeft-- == 0) {
                throw runtime("Something is wrong, exceeded %s tool calling round trips (maxToolCallingRoundTrips)", context.toolService.maxToolCallingRoundTrips());
            }
            if (intermediateResponseHandler != null) {
                if (!streamControl.stopped()) caller(() -> intermediateResponseHandler.accept(chatResponse));
            }
            List<ToolExecutionRequest> executedRequests = aiMessage.toolExecutionRequests();
            Map<ToolExecutionRequest, ToolExecutionResult> toolResults = new LinkedHashMap<>();
            if (toolExecutor != null) {
                executedRequests = new ArrayList<>();
                for (Future<ToolRequestResult> toolExecutionFuture : toolExecutionFutures) {
                    try {
                        ToolRequestResult toolRequestResult = toolExecutionFuture.get();
                        executedRequests.add(toolRequestResult.request());
                        toolResults.put(toolRequestResult.request(), toolRequestResult.result());
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof RuntimeException) {
                            throw ((RuntimeException) e.getCause());
                        } else {
                            throw new RuntimeException(e.getCause());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
            } else {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    toolResults.put(toolRequest, execute(toolRequest));
                }
            }
            // Use the requests that were executed: the final response can reformat their JSON arguments.
            if (streamControl.stopped()) return;
            ToolService.ToolResultsOutcome outcome = context.toolService.processToolResults(context, executedRequests, toolResults, new ArrayList<>(), invocationContext, toolServiceContext);
            List<ChatMessage> nextMessages = context.toolService.persistToolResultsAndResolveMessagesSync(context, getMemory(), null, outcome.resultMessages(), invocationContext);
            if (ToolService.shouldReturnImmediately(outcome.anyToolErrored(), outcome.returnBehaviors())) {
                ChatResponse finalChatResponse = finalResponse(chatResponse, aiMessage);
                complete(finalChatResponse);
                return;
            }
            ChatRequestParameters parameters = chatRequestParameters(invocationContext.methodArguments(), toolServiceContext.effectiveTools());
            ToolService.NextChatRequest next = context.toolService.prepareNextChatRequest(context, invocationContext.chatMemoryId(), nextMessages, invocationContext, toolServiceContext, toolResults, parameters);
            dev.langchain4j.service.AiServiceStreamingResponseHandler handler = new AiServiceStreamingResponseHandler(next.chatRequest(), chatExecutor, context, invocationContext, partialResponseHandler, partialResponseWithContextHandler, partialThinkingHandler, partialThinkingWithContextHandler, partialToolCallHandler, partialToolCallWithContextHandler, beforeToolExecutionHandler, rawEventHandler, toolExecutionHandler, intermediateResponseHandler, completeResponseHandler, errorHandler, temporaryMemory, TokenUsage.sum(tokenUsage, chatResponse.metadata().tokenUsage()), next.toolServiceContext(), toolCallingRoundTripsLeft, toolArgumentsErrorHandler, toolExecutionErrorHandler, toolExecutor, completeResponseProcessingExecutor, commonGuardrailParams, methodKey, streamControl);
            handler.startModel();
        } else {
            ChatResponse finalChatResponse = finalResponse(chatResponse, aiMessage);
            if (completeResponseHandler != null) {
                // Invoke output guardrails
                if (hasOutputGuardrails) {
                    if (commonGuardrailParams != null) {
                        dev.langchain4j.guardrail.GuardrailRequestParams newCommonParams = commonGuardrailParams.toBuilder().chatMemory(getMemory()).build();
                        dev.langchain4j.guardrail.OutputGuardrailRequest outputGuardrailParams = OutputGuardrailRequest.builder().responseFromLLM(finalChatResponse).chatExecutor(ToolAwareRepromptExecutor.wrap(chatExecutor, context, invocationContext.chatMemoryId(), chatRequest.parameters(), invocationContext, toolServiceContext, this::executeSynchronously)).requestParams(newCommonParams).chatRequest(chatRequest).build();
                        finalChatResponse = context.guardrailService().executeGuardrails(methodKey, outputGuardrailParams);
                    }
                    // If we have output guardrails, we should process all of the partial responses first before
                    // completing
                    if (partialResponseHandler != null) {
                        responseBuffer.forEach(partialResponseHandler::accept);
                    } else if (partialResponseWithContextHandler != null) {
                        PartialResponseContext partialResponseContext = new PartialResponseContext(new CancellationUnsupportedStreamingHandle());
                        responseBuffer.forEach(s -> partialResponseWithContextHandler.accept(new PartialResponse(s), partialResponseContext));
                    }
                    responseBuffer.clear();
                }
                complete(finalChatResponse);
            } else {
                complete(finalChatResponse);
            }
        }
    }

    /**
     * Performs a single, blocking model call against the configured
     * {@link dev.langchain4j.model.chat.StreamingChatModel}, exposing it as a plain synchronous call so that
     * {@code executeInferenceAndToolsLoop} can drive the tool loop on a streaming-only AI Service. This is
     * intentionally a raw model call and does not fire request/response events — those are fired by
     * {@code executeInferenceAndToolsLoop} itself, mirroring the synchronous path.
     */
    private ChatResponse executeSynchronously(ChatRequest request) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        context.streamingChatModel.chat(request, new StreamingChatResponseHandler() {

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw ((RuntimeException) e.getCause());
            }
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private ChatResponse finalResponse(ChatResponse completeResponse, AiMessage aiMessage) {
        TokenUsage totalTokenUsage = TokenUsage.sum(tokenUsage, completeResponse.metadata().tokenUsage());
        return ChatResponse.builder().aiMessage(aiMessage).metadata(completeResponse.metadata().toBuilder().tokenUsage(totalTokenUsage).build()).build();
    }

    private ToolExecutionResult execute(ToolExecutionRequest toolRequest) {
        if (streamControl.stopped()) throw new java.util.concurrent.CancellationException("Stream cancelled");
        return context.toolService.executeTool(invocationContext, toolExecutors, toolRequest,
                beforeToolExecutionHandler == null ? null : event -> {
                    if (!streamControl.stopped()) caller(() -> beforeToolExecutionHandler.accept(event));
                },
                toolExecutionHandler == null ? null : event -> {
                    if (!streamControl.stopped()) caller(() -> toolExecutionHandler.accept(event));
                });
    }

    private ChatMemory getMemory() {
        return getMemory(invocationContext.chatMemoryId());
    }

    private ChatMemory getMemory(Object memId) {
        return context.hasChatMemory() ? context.chatMemoryService.getOrCreateChatMemory(invocationContext.chatMemoryId()) : temporaryMemory;
    }

    private void addToMemory(ChatMessage chatMessage) {
        if (streamControl.stopped()) throw new java.util.concurrent.CancellationException("Stream cancelled");
        getMemory().add(chatMessage);
    }

    void startModel() {
        if (streamControl.stopped()) return;
        dev.langchain4j.internal.CancellationContext.run(streamControl, () -> {
            if (!streamControl.stopped()) context.streamingChatModel.chat(chatRequest, this);
        });
    }

    private void caller(Runnable callback) {
        dev.langchain4j.internal.CancellationContext.run(streamControl.parent,
                () -> CapturedContextSupport.runWithOwner(invocationContext, callback));
    }

    private void complete(ChatResponse response) {
        if (!streamControl.claimTerminal(true)) return;
        try {
            caller(() -> {
                fireInvocationComplete(response);
                if (completeResponseHandler != null) completeResponseHandler.accept(response);
            });
        } catch (RuntimeException | Error callbackFailure) {
            // Report on the thread executing the callback, not a later Future observer thread.
            Thread current = Thread.currentThread();
            caller(() -> current.getUncaughtExceptionHandler().uncaughtException(current, callbackFailure));
        } finally { streamControl.finish(); }
    }

    @Override
    public void onError(Throwable error) {
        if (!streamControl.claimTerminal(false)) return;
        streamControl.cancelResources(error);
        toolExecutionFutures.clear();
        try {
            caller(() -> {
                if (errorHandler != null) {
                    try {
                        fireErrorReceived(error);
                        errorHandler.accept(error);
                    } catch (Exception e) {
                        LOG.error("While handling the following error...", error);
                        LOG.error("...the following error happened", e);
                    }
                } else LOG.warn("Ignored error", error);
            });
        } finally { streamControl.finish(); }
    }

    /** One TokenStream's resources across model rounds; the parent root remains independent. */
    private static final class StreamControl implements dev.langchain4j.internal.CancellationContext.Registrar {
        private final dev.langchain4j.internal.CancellationContext.Registrar parent;
        private final java.util.Set<Entry> resources = new java.util.HashSet<>();
        private final java.util.Map<dev.langchain4j.model.chat.response.StreamingHandle, dev.langchain4j.model.chat.response.StreamingHandle> handles = new java.util.IdentityHashMap<>();
        private volatile boolean cancelled;
        private boolean terminal;
        private boolean successful;
        private boolean finished;
        StreamControl(dev.langchain4j.internal.CancellationContext.Registrar parent) { this.parent = parent; }
        synchronized boolean stopped() { return cancelled || terminal || finished; }
        synchronized boolean claimTerminal(boolean success) { if (stopped()) return false; terminal = true; successful = success; return true; }
        synchronized boolean successfulTerminal() { return terminal && successful && !cancelled; }
        @Override public <T> T inContext(java.util.function.Supplier<T> action) {
            return parent == null ? action.get() : parent.inContext(action);
        }
        private Entry local(Runnable cancel) {
            Entry entry = new Entry(cancel);
            boolean immediate;
            synchronized (this) { immediate = cancelled || terminal || finished; if (!immediate) resources.add(entry); }
            if (immediate) entry.cancel();
            return entry;
        }
        @Override public dev.langchain4j.internal.CancellationContext.Registration register(Runnable cancel) {
            Entry entry = local(cancel);
            try { if (parent != null) entry.attach(parent.register(entry::cancel)); }
            catch (RuntimeException | Error failure) { entry.close(); throw failure; }
            return entry;
        }
        void track(CompletableFuture<?> future) {
            Entry entry = local(() -> future.cancel(true));
            future.whenComplete((ignored, error) -> entry.close());
        }
        dev.langchain4j.model.chat.response.StreamingHandle handle(dev.langchain4j.model.chat.response.StreamingHandle delegate) {
            dev.langchain4j.model.chat.response.StreamingHandle wrapped;
            synchronized (this) {
                wrapped = handles.get(delegate);
                if (wrapped != null) return wrapped;
                wrapped = new ControlHandle(this, delegate);
                handles.put(delegate, wrapped);
            }
            local(delegate::cancel);
            return wrapped;
        }
        void cancel() {
            synchronized (this) { if (cancelled) return; cancelled = true; if (finished) return; }
            java.util.concurrent.CancellationException reason = new java.util.concurrent.CancellationException("Stream cancelled");
            cancelResources(reason);
            Throwable[] failures = reason.getSuppressed();
            if (failures.length != 0) {
                Throwable first = failures[0];
                for (int i = 1; i < failures.length; i++) if (first != failures[i]) first.addSuppressed(failures[i]);
                if (first instanceof Error) throw (Error) first;
                throw (RuntimeException) first;
            }
        }
        void cancelResources(Throwable primary) {
            java.util.List<Entry> pending;
            synchronized (this) { pending = new java.util.ArrayList<>(resources); resources.clear(); handles.clear(); }
            for (Entry entry : pending) {
                try { entry.cancel(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != primary) primary.addSuppressed(cleanup); }
            }
        }
        void finish() {
            java.util.List<Entry> closing;
            synchronized (this) { finished = true; closing = new java.util.ArrayList<>(resources); resources.clear(); handles.clear(); }
            for (Entry entry : closing) {
                try { entry.close(); }
                catch (RuntimeException | Error cleanup) { LOG.error("Failed to detach streaming resource ownership", cleanup); }
            }
        }
        private final class Entry implements dev.langchain4j.internal.CancellationContext.Registration {
            private final Runnable cancel;
            private dev.langchain4j.internal.CancellationContext.Registration upstream;
            private boolean closed;
            Entry(Runnable cancel) { this.cancel = cancel; }
            void attach(dev.langchain4j.internal.CancellationContext.Registration registration) {
                boolean close;
                synchronized (StreamControl.this) { close = closed; if (!close) upstream = registration; }
                if (close) registration.close();
            }
            public void close() {
                dev.langchain4j.internal.CancellationContext.Registration releasing;
                synchronized (StreamControl.this) { if (closed) return; closed = true; resources.remove(this); releasing = upstream; upstream = null; }
                if (releasing != null) releasing.close();
            }
            void cancel() {
                dev.langchain4j.internal.CancellationContext.Registration releasing;
                synchronized (StreamControl.this) { if (closed) return; closed = true; resources.remove(this); releasing = upstream; upstream = null; }
                Throwable failure = null;
                try { if (releasing != null) releasing.close(); }
                catch (RuntimeException | Error cleanup) { failure = cleanup; }
                try { cancel.run(); }
                catch (RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
                if (failure instanceof Error) throw (Error) failure;
                if (failure != null) throw (RuntimeException) failure;
            }
        }
    }

    private static final class ControlHandle implements dev.langchain4j.model.chat.response.StreamingHandle {
        private final StreamControl control;
        private final dev.langchain4j.model.chat.response.StreamingHandle delegate;
        ControlHandle(StreamControl control, dev.langchain4j.model.chat.response.StreamingHandle delegate) { this.control = control; this.delegate = delegate; }
        public void cancel() { control.cancel(); }
        public boolean isCancelled() { return control.cancelled || delegate.isCancelled(); }
    }
}
