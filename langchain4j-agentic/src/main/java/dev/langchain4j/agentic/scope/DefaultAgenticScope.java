package dev.langchain4j.agentic.scope;

import dev.langchain4j.agentic.internal.DeferredScopeContext;
import dev.langchain4j.agentic.internal.InvocationTasks;
import dev.langchain4j.agentic.internal.AsyncResponse;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static dev.langchain4j.agentic.internal.AgentUtil.keyDefaultValue;
import static dev.langchain4j.agentic.internal.AgentUtil.keyName;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.Internal;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.agent.ChatMessagesAccess;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.declarative.TypedKey;
import dev.langchain4j.agentic.internal.DeferredResponse;
import dev.langchain4j.agentic.internal.DelayedResponse;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Utils;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.service.tool.ToolExecution;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Internal
@JsonInclude(NON_NULL)
public class DefaultAgenticScope implements AgenticScope {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgenticScope.class);

    @JsonInclude(NON_NULL)
    public final static class AgentMessage {

        private final String agentName;

        public String agentName() {
            return agentName;
        }

        private final String agentId;

        public String agentId() {
            return agentId;
        }

        private final ChatMessage message;

        public ChatMessage message() {
            return message;
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public AgentMessage(@JsonProperty("agentName") String agentName, @JsonProperty("agentId") String agentId, @JsonProperty("message") ChatMessage message) {
            this.agentName = agentName;
            this.agentId = agentId;
            this.message = message;
        }

        public final boolean equals(Object obj) {
            return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentName, ((AgentMessage) obj).agentName) && java.util.Objects.equals(agentId, ((AgentMessage) obj).agentId) && java.util.Objects.equals(message, ((AgentMessage) obj).message));
        }

        public final int hashCode() {
            return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(agentName)) + java.util.Objects.hashCode(agentId)) + java.util.Objects.hashCode(message));
        }

        public final String toString() {
            return "AgentMessage[" + "agentName=" + agentName + ", agentId=" + agentId + ", message=" + message + "]";
        }
    }

    private final Object memoryId;

    private final Map<String, Object> state = new ConcurrentHashMap<>();

    // Snapshot metadata only: an in-flight or controlled-suspended async invocation is re-entered
    // from its method boundary after loading, never restored as a live CompletableFuture.
    private final Set<String> incompleteAsyncAgentIds = new java.util.concurrent.ConcurrentSkipListSet<>();

    private transient InvocationTasks invocationTasks = new InvocationTasks(this);

    private static final Object UNRESOLVED_ASYNC = new Object();

    private final List<AgentInvocation> agentInvocations = Collections.synchronizedList(new ArrayList<>());

    private final List<AgentMessage> context = Collections.synchronizedList(new ArrayList<>());

    private final transient Map<String, Object> agents = new ConcurrentHashMap<>();

    private final transient Map<String, Object> executionContexts = new ConcurrentHashMap<>();

    private final transient List<CompensableExecution> compensableExecutions = Collections.synchronizedList(new ArrayList<>());

    private static final Function<ErrorContext, ErrorRecoveryResult> DEFAULT_ERROR_RECOVERY = errorContext -> ErrorRecoveryResult.throwException();

    private transient Function<ErrorContext, ErrorRecoveryResult> errorHandler = DEFAULT_ERROR_RECOVERY;

    private static Predicate<Object> serializableStateFilter = ((Predicate<Object>) DefaultAgenticScope::isProxy).negate().and(((Predicate<Object>) DefaultAgenticScope::isTokenStream).negate()).and(((Predicate<Object>) DefaultAgenticScope::isFuture).negate());

    private static boolean isProxy(Object obj) {
        return Proxy.isProxyClass(obj.getClass());
    }

    private static boolean isTokenStream(Object obj) {
        return obj instanceof TokenStream;
    }

    private static boolean isFuture(Object obj) {
        return obj instanceof Future;
    }

    public enum Kind {

        EPHEMERAL, REGISTERED, PERSISTENT
    }

    private final Kind kind;

    /**
     * The state that is safe to persist. Public so that a JSON codec supplied through the SPI can
     * reach it from another package.
     */
    public DefaultAgenticScope serializableCopy() {
        DefaultAgenticScope copy = new DefaultAgenticScope(memoryId, kind);
        copy.incompleteAsyncAgentIds.addAll(incompleteAsyncAgentIds);
        state.forEach((key, value) -> {
            Object snapshot = checkpointValue(value);
            if (snapshot != UNRESOLVED_ASYNC && snapshot != null && isSerializable(snapshot)) {
                copy.state.put(key, snapshot);
            }
        });
        synchronized (agentInvocations) {
            for (AgentInvocation invocation : agentInvocations) {
                Object snapshot = checkpointValue(invocation.rawOutput());
                if (snapshot == UNRESOLVED_ASYNC) {
                    copy.incompleteAsyncAgentIds.add(invocation.agentId());
                    snapshot = null;
                } else if (invocation.rawOutput() != null || !incompleteAsyncAgentIds.contains(invocation.agentId())) {
                    copy.incompleteAsyncAgentIds.remove(invocation.agentId());
                }
                copy.agentInvocations.add(new AgentInvocation(invocation.agentType(), invocation.agentName(),
                        invocation.agentId(), invocation.input(), snapshot, invocation.skipReason()));
            }
        }
        copy.context.addAll(context);
        return copy;
    }

    /** Never wait while holding the checkpoint write lock: workers can need scope read locks. */
    private static Object checkpointValue(Object value) {
        while (value instanceof dev.langchain4j.agentic.internal.AsyncResponse<?>
                || value instanceof DeferredResponse<?> && ((DelayedResponse<?>) value).isDone()) {
            DelayedResponse<?> delayed = (DelayedResponse<?>) value;
            if (!delayed.isDone() || delayed instanceof AsyncResponse<?> && ((AsyncResponse<?>) delayed).isCancelled()) return UNRESOLVED_ASYNC;
            try {
                Object resolved = delayed.blockingGet();
                if (resolved == value) throw new IllegalStateException("Self-referencing checkpoint value");
                value = resolved;
            } catch (AgenticSystemSuspendedException controlled) {
                return UNRESOLVED_ASYNC;
            }
        }
        return value;
    }

    public static boolean isSerializable(Object value) {
        return value == null || serializableStateFilter.test(value);
    }

    public static void addSerializableStateFilter(Predicate<Object> filter) {
        serializableStateFilter = serializableStateFilter.and(filter);
    }

    /**
     * This lock is used to ensure that the AgenticScope doesn't get concurrently modified when it is going to be persisted.
     * The internal data structures of the AgenticScope are all thread-safe, so they don't need to be guarded by a read lock
     * when accessed. In essence multiple changes are allowed at the same time, but it is not allowed to persist a
     * AgenticScope that is not in a frozen state. That's why the read lock is acquired for the first and a write lock
     * when the second happens.
     */
    private final transient ReadWriteLock lock;

    DefaultAgenticScope(Kind kind) {
        this(Utils.randomUUID(), kind);
    }

    @JsonCreator
    DefaultAgenticScope(@JsonProperty("memoryId") Object memoryId, @JsonProperty("kind") Kind kind) {
        this.memoryId = memoryId;
        this.kind = kind;
        this.lock = (kind == Kind.PERSISTENT) ? new ReentrantReadWriteLock() : null;
    }

    public static DefaultAgenticScope ephemeralAgenticScope() {
        return new DefaultAgenticScope(DefaultAgenticScope.Kind.EPHEMERAL);
    }

    @Override
    public Object memoryId() {
        return memoryId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void writeState(String key, Object value) {
        InvocationTasks.mutate(this, () -> writeStateOwned(key, value));
    }

    private void writeStateOwned(String key, Object value) {
        if (DeferredScopeContext.handles(this, key)) {
            Object old = DeferredScopeContext.read();
            DeferredScopeContext.write(value);
            if (old instanceof DeferredResponse<?> && !((DeferredResponse<?>) old).isDone()) {
                ((DeferredResponse<Object>) old).complete(value);
            }
            return;
        }
        withReadLock(() -> {
            Object old;
            if (value == null) {
                old = state.remove(key);
            } else {
                old = state.put(key, value);
            }
            if (old instanceof DeferredResponse<?> && !((DeferredResponse<?>) old).isDone()) {
                ((DeferredResponse<Object>) ((DeferredResponse<?>) old)).complete(value);
            }
        });
    }

    @Override
    public <T> void writeState(Class<? extends TypedKey<T>> key, T value) {
        writeState(keyName(key), value);
    }

    @Override
    public void writeStateIfAbsent(String key, Object value) {
        InvocationTasks.mutate(this, () -> writeStateIfAbsentOwned(key, value));
    }

    private void writeStateIfAbsentOwned(String key, Object value) {
        if (value != null) {
            if (DeferredScopeContext.handles(this, key)) {
                if (!hasState(key)) DeferredScopeContext.write(value);
                return;
            }
            withReadLock(() -> state.compute(key, (k, v) -> hasState(k) ? v : value));
        }
    }

    @Override
    public <T> void writeStateIfAbsent(Class<? extends TypedKey<T>> key, T value) {
        writeStateIfAbsent(keyName(key), value);
    }

    @Override
    public void writeStates(Map<String, Object> newState) {
        InvocationTasks.mutate(this, () -> writeStatesOwned(newState));
    }

    private void writeStatesOwned(Map<String, Object> newState) {
        withReadLock(() -> newState.forEach((key, value) -> {
            java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(value);
            if (DeferredScopeContext.handles(this, key)) DeferredScopeContext.write(value);
            else state.put(key, value);
        }));
    }

    @Override
    public boolean hasState(String key) {
        Object value = stateValue(key);
        if (value == null) {
            return false;
        }
        return value instanceof String ? !dev.langchain4j.internal.Jdk8.isBlank(((String) value)) : true;
    }

    @Override
    public boolean hasState(Class<? extends TypedKey<?>> key) {
        return hasState(keyName(key));
    }

    @Override
    public Object readState(String key) {
        return readStateBlocking(key, stateValue(key));
    }

    @Override
    public <T> T readState(String key, T defaultValue) {
        Object value = stateValue(key);
        return (T) readStateBlocking(key, value != null ? value : defaultValue);
    }

    @Override
    public <T> T readState(Class<? extends TypedKey<T>> key) {
        return readState(keyName(key), keyDefaultValue(key));
    }

    private Object stateValue(String key) {
        return DeferredScopeContext.handles(this, key) ? DeferredScopeContext.read() : state.get(key);
    }

    private Object readStateBlocking(String key, Object value) {
        InvocationTasks.check(this);
        while (value instanceof DelayedResponse) {
            if (value instanceof dev.langchain4j.agentic.internal.SuspendedResponse<?> && !((DelayedResponse<?>) value).isDone()) {
                throw new AgenticSystemSuspendedException(this);
            }
            Object pending = value;
            if (pending instanceof DeferredResponse<?> && InvocationTasks.bound(this) != null) {
                try { value = ((DeferredResponse<?>) pending).blockingGet(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS); }
                catch (java.util.concurrent.TimeoutException impossible) { throw new IllegalStateException(impossible); }
            } else value = ((DelayedResponse<?>) pending).blockingGet();
            InvocationTasks.check(this);
            if (value == pending) throw new IllegalStateException("Self-referencing deferred state: " + key);
            publishResolvedState(key, pending, value);
        }
        return value;
    }

    private void publishResolvedState(String key, Object pending, Object value) {
        InvocationTasks.mutate(this, () -> publishResolvedStateOwned(key, pending, value));
    }

    private void publishResolvedStateOwned(String key, Object pending, Object value) {
        if (DeferredScopeContext.handles(this, key)) {
            DeferredScopeContext.write(value);
        } else {
            // An older waiter must not overwrite a newer assignment or pending operation.
            withReadLock(() -> state.compute(key, (k, current) -> current == pending ? value : current));
        }
    }

    /** Internal planner barrier: unwrap async computations without waiting on human response markers. */
    @Internal
    public void resolveAsyncStates() {
        new java.util.HashMap<>(state).forEach((key, original) -> {
            if (DeferredScopeContext.handles(this, key)) return;
            Object value = original;
            while (value instanceof dev.langchain4j.agentic.internal.AsyncResponse<?>) {
                Object pending = value;
                value = ((DelayedResponse<?>) pending).blockingGet();
                if (value == pending) throw new IllegalStateException("Self-referencing async state: " + key);
                publishResolvedState(key, pending, value);
            }
        });
    }

    /** Internal resume classification, using the latest published invocation per agent. */
    @Internal
    public Set<String> suspendedAsyncAgentIds() {
        Map<String, AgentInvocation> latest = new java.util.LinkedHashMap<>();
        synchronized (agentInvocations) {
            for (AgentInvocation invocation : agentInvocations) latest.put(invocation.agentId(), invocation);
        }
        Set<String> suspended = new java.util.HashSet<>(incompleteAsyncAgentIds);
        latest.forEach((id, invocation) -> {
            Object result = invocation.rawOutput();
            if (result instanceof dev.langchain4j.agentic.internal.AsyncResponse<?> && ((DelayedResponse<?>) result).isDone()) {
                try {
                    ((DelayedResponse<?>) result).blockingGet();
                } catch (AgenticSystemSuspendedException controlled) {
                    suspended.add(id);
                } catch (RuntimeException failure) {
                    // Ordinary failure remains on its existing failure/recovery path.
                }
            }
        });
        return suspended;
    }

    /** Await only the selected async invocation, without draining unrelated parallel work. */
    @Internal
    public Object resolveAsyncState(AgentInvocation invocation) {
        Object value = invocation.rawOutput();
        while (value instanceof dev.langchain4j.agentic.internal.AsyncResponse<?>) {
            Object pending = value;
            value = ((DelayedResponse<?>) pending).blockingGet();
            if (value == pending) throw new IllegalStateException("Self-referencing async invocation: " + invocation.agentId());
            Object resolved = value;
            new java.util.HashMap<>(state).forEach((key, current) -> {
                if (DeferredScopeContext.handles(this, key)) {
                    if (DeferredScopeContext.read() == pending) publishResolvedState(key, pending, resolved);
                } else if (current == pending) {
                    publishResolvedState(key, pending, resolved);
                }
            });
        }
        return value;
    }

    @Override
    public Map<String, Object> state() {
        return state;
    }

    public <T> T getOrCreateAgent(String agentId, Function<DefaultAgenticScope, T> agentFactory) {
        return (T) agents.computeIfAbsent(agentId, id -> agentFactory.apply(this));
    }

    public void registerAgentInvocation(AgentInvocation agentInvocation, Object agent) {
        InvocationTasks.mutate(this, () -> registerAgentInvocationOwned(agentInvocation, agent));
    }

    private void registerAgentInvocationOwned(AgentInvocation agentInvocation, Object agent) {
        withReadLock(() -> {
            incompleteAsyncAgentIds.remove(agentInvocation.agentId());
            agentInvocations.add(agentInvocation);
            registerContext(agentInvocation, agent);
        });
    }

    @Internal
    public InvocationTasks invocationTasks() {
        InvocationTasks bound = InvocationTasks.bound(this);
        return bound != null ? bound : invocationTasks;
    }

    @Internal
    public void cancelPendingTasks(Throwable primary) { invocationTasks().cancel(primary); }

    public void rootCallStarted(AgenticScopeRegistry registry) {
        if (invocationTasks.finished()) invocationTasks = new InvocationTasks(this);
    }

    public void rootCallEnded(AgenticScopeRegistry registry, AgentListener agentListener) {
        // ensure that all pending async operations are completed before ending the root call
        dev.langchain4j.internal.Jdk8.copyMap(state).forEach((key, value) -> {
            if (value instanceof DelayedResponse<?>) {
                publishResolvedState(key, value, ((DelayedResponse<?>) value).blockingGet());
            }
        });
        if (kind == Kind.EPHEMERAL) {
            // Ephemeral agenticScope are for single-use and can be evicted immediately
            registry.evict(memoryId, agentListener);
        } else if (kind == Kind.PERSISTENT) {
            flush(registry);
        }
        compensableExecutions.clear();
        invocationTasks().finish();
    }

    /** Failure cleanup never waits for external answers or unfinished computations.
     * Successful root completion retains its existing blocking drain contract. */
    @Internal
    public void rootCallFailed(AgenticScopeRegistry registry, AgentListener listener, Throwable primary) {
        cancelPendingTasks(primary);
        new java.util.HashMap<>(state).forEach((key, original) ->
                dev.langchain4j.agentic.internal.AgenticJdk8.suppressCleanupFailure(primary, () -> {
                    Object value = original;
                    while (value instanceof DelayedResponse<?> && ((DelayedResponse<?>) value).isDone()) {
                        if (value instanceof AsyncResponse<?> && ((AsyncResponse<?>) value).isCancelled()) break;
                        Object pending = value;
                        value = ((DelayedResponse<?>) pending).blockingGet();
                        if (value == pending) throw new IllegalStateException("Self-referencing failed-call state: " + key);
                        publishResolvedState(key, pending, value);
                    }
                }));
        dev.langchain4j.agentic.internal.AgenticJdk8.suppressCleanupFailure(primary, () -> {
            if (kind == Kind.EPHEMERAL) registry.evict(memoryId, listener);
            else if (kind == Kind.PERSISTENT) flush(registry);
        });
        compensableExecutions.clear();
    }

    private void flush(AgenticScopeRegistry registry) {
        lock.writeLock().lock();
        try {
            registry.update(this);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void registerContext(AgentInvocation agentInvocation, Object agent) {
        ChatMemory chatMemory = agent instanceof ChatMemoryAccess ? ((ChatMemoryAccess) agent).getChatMemory(memoryId) : null;
        if (chatMemory != null) {
            registerContextFromChatMemory(agentInvocation, chatMemory);
        } else if (agent instanceof ChatMessagesAccess && agentInvocation.output() != null) {
            context.add(new AgentMessage(agentInvocation.agentName(), agentInvocation.agentId(), ((ChatMessagesAccess) agent).lastUserMessage(memoryId())));
            context.add(new AgentMessage(agentInvocation.agentName(), agentInvocation.agentId(), AiMessage.aiMessage(agentInvocation.output().toString())));
        }
        if (agent instanceof ChatMessagesAccess) {
            ((ChatMessagesAccess) agent).removeLastResponseEvent(memoryId());
        }
    }

    private void registerContextFromChatMemory(AgentInvocation agentInvocation, ChatMemory chatMemory) {
        List<ChatMessage> agentMessages = chatMemory.messages();
        if (Utils.isNullOrEmpty(agentMessages)) {
            return;
        }
        ChatMessage lastMessage = agentMessages.get(agentMessages.size() - 1);
        if (!(lastMessage instanceof AiMessage)) {
            return;
        }
        for (int i = agentMessages.size() - 1; i >= 0; i--) {
            ChatMessage candidate = agentMessages.get(i);
            if (candidate instanceof UserMessage) {
                // Only add to the agenticScope's context the last UserMessage ...
                context.add(new AgentMessage(agentInvocation.agentName(), agentInvocation.agentId(), ((UserMessage) candidate)));
                // ... and last AiMessage response, all other messages are local to the invoked agent internals
                context.add(new AgentMessage(agentInvocation.agentName(), agentInvocation.agentId(), ((AiMessage) lastMessage)));
                return;
            }
        }
    }

    public List<AgentMessage> context() {
        return context;
    }

    @Override
    public String contextAsConversation(Object... agents) {
        Predicate<String> agentFilter = agents != null && agents.length > 0 ? Arrays.stream(agents).filter(AgentInstance.class::isInstance).map(AgentInstance.class::cast).map(AgentInstance::name).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList))::contains : agent -> true;
        return contextAsConversation(agentFilter);
    }

    @Override
    public String contextAsConversation(String... agentNames) {
        Predicate<String> agentFilter = agentNames != null && agentNames.length > 0 ? dev.langchain4j.internal.Jdk8.list(agentNames)::contains : agent -> true;
        return contextAsConversation(agentFilter);
    }

    private String contextAsConversation(Predicate<String> agentFilter) {
        StringBuilder sb = new StringBuilder();
        for (AgentMessage agentMessage : context) {
            if (!agentFilter.test(agentMessage.agentName())) {
                continue;
            }
            ChatMessage message = agentMessage.message();
            if (message instanceof UserMessage) {
                sb.append("User: \"").append(((UserMessage) message).singleText()).append("\"\n");
            } else if (message instanceof AiMessage) {
                sb.append(agentMessage.agentName()).append(" agent: \"").append(((AiMessage) message).text()).append("\"\n");
            }
        }
        String contextAsConversation = sb.toString();
        LOG.trace("AgenticScope context as conversation: '{}'", contextAsConversation);
        return contextAsConversation;
    }

    @Override
    public List<AgentInvocation> agentInvocations() {
        return Collections.unmodifiableList(agentInvocations);
    }

    @Override
    public List<AgentInvocation> agentInvocations(String agentName) {
        return agentInvocations.stream().filter(inv -> inv.agentName().equals(agentName)).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
    }

    @Override
    public List<AgentInvocation> agentInvocations(Class<?> agentType) {
        return agentInvocations.stream().filter(inv -> inv.agentType().equals(agentType)).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AgenticScope{memoryId='").append(memoryId).append("', state={");
        boolean first = true;
        for (Map.Entry<String, Object> e : state.entrySet()) {
            if (!first)
                sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=');
            // Guard against circular references (e.g. ResultWithAgenticScope holding this scope)
            if (e.getValue() instanceof ResultWithAgenticScope<?>) {
                sb.append("<ResultWithAgenticScope>");
            } else {
                sb.append(e.getValue());
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private void withReadLock(Runnable action) {
        if (kind == Kind.PERSISTENT) {
            lock.readLock().lock();
            try {
                action.run();
            } finally {
                lock.readLock().unlock();
            }
        } else {
            action.run();
        }
    }

    public DefaultAgenticScope withErrorHandler(Function<ErrorContext, ErrorRecoveryResult> errorHandler) {
        if (errorHandler != null) {
            this.errorHandler = errorHandler;
        }
        return this;
    }

    public ErrorRecoveryResult handleError(String agentName, AgentInvocationException exception) {
        return errorHandler.apply(new ErrorContext(agentName, this, exception));
    }

    private final static class CompensableExecution {

        public void compensate() {
            try {
                compensatingAction.accept(toolExecution());
            } catch (Exception e) {
                LOG.warn("Cross-agent compensating action failed for tool '{}': {}", toolExecution().request().name(), e.getMessage(), e);
            }
        }

        private final ToolExecution toolExecution;

        public ToolExecution toolExecution() {
            return toolExecution;
        }

        private final Consumer<ToolExecution> compensatingAction;

        public Consumer<ToolExecution> compensatingAction() {
            return compensatingAction;
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public CompensableExecution(@com.fasterxml.jackson.annotation.JsonProperty("toolExecution") ToolExecution toolExecution, @com.fasterxml.jackson.annotation.JsonProperty("compensatingAction") Consumer<ToolExecution> compensatingAction) {
            this.toolExecution = toolExecution;
            this.compensatingAction = compensatingAction;
        }

        public final boolean equals(Object obj) {
            return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(toolExecution, ((CompensableExecution) obj).toolExecution) && java.util.Objects.equals(compensatingAction, ((CompensableExecution) obj).compensatingAction));
        }

        public final int hashCode() {
            return (31 * (31 * 0 + java.util.Objects.hashCode(toolExecution)) + java.util.Objects.hashCode(compensatingAction));
        }

        public final String toString() {
            return "CompensableExecution[" + "toolExecution=" + toolExecution + ", compensatingAction=" + compensatingAction + "]";
        }
    }

    public void registerCompensableExecution(ToolExecution toolExecution, Consumer<ToolExecution> compensatingAction) {
        compensableExecutions.add(new CompensableExecution(toolExecution, compensatingAction));
    }

    public void compensateAll() {
        List<CompensableExecution> snapshot;
        synchronized (compensableExecutions) {
            snapshot = new ArrayList<>(compensableExecutions);
            compensableExecutions.clear();
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            snapshot.get(i).compensate();
        }
    }

    /**
     * Checkpoints the current state of this scope by persisting it to the store.
     * This is a no-op for non-persistent scopes. For persistent scopes, it acquires
     * the write lock and flushes the current state to the store.
     *
     * @param registry the registry managing this scope's persistence
     */
    public void checkpoint(AgenticScopeRegistry registry) {
        if (kind == Kind.PERSISTENT) {
            flush(registry);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean completePendingResponse(String responseId, Object value) {
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            Object candidate = entry.getValue();
            if (candidate instanceof DeferredResponse<?>) {
                DeferredResponse<?> deferred = (DeferredResponse<?>) candidate;
                if (!deferred.responseId().equals(responseId)) {
                    continue;
                }
                boolean completed = ((DeferredResponse<Object>) deferred).complete(value);
                if (completed) {
                    writeState(entry.getKey(), value);
                }
                return completed;
            }
        }
        return false;
    }

    @Override
    public Set<String> pendingResponseIds() {
        return state.values().stream().filter(DeferredResponse.class::isInstance).map(DeferredResponse.class::cast).filter(p -> !p.isDone()).map(DeferredResponse::responseId).collect(Collectors.toSet());
    }

    @Override
    public void writeExecutionContext(final String key, final Object context) {
        if (key == null)
            throw new IllegalArgumentException("key cannot be null");
        if (context == null)
            throw new IllegalArgumentException("context cannot be null");
        this.executionContexts.put(key, context);
    }

    @Override
    public Object executionContext(final String key) {
        return this.executionContexts.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T executionContextAs(final String key, final Class<T> type) {
        return (T) this.executionContexts.get(key);
    }
}
