package dev.langchain4j.agentic.scope;

import dev.langchain4j.Internal;
import dev.langchain4j.agentic.observability.AgentListener;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.langchain4j.agentic.observability.ListenerNotifierUtil.beforeAgenticScopeDestroyed;
import static dev.langchain4j.agentic.scope.DefaultAgenticScope.ephemeralAgenticScope;

/** Registry for agentic scopes and their pluggable persistence. */
@Internal
public class AgenticScopeRegistry {
    private final String agentId;
    private final AgenticScopeStore store;
    private final Map<AgenticScopeKey, DefaultAgenticScope> inMemoryAgenticScope = new ConcurrentHashMap<>();
    private final Map<AgenticScopeKey, Throwable> initializationFailures = new ConcurrentHashMap<>();
    private final Set<AgenticScopeKey> initializing = ConcurrentHashMap.newKeySet();
    private final Map<AgenticScopeKey, KeyLock> keyLocks = new HashMap<>();

    public AgenticScopeRegistry(String agentId) {
        this.agentId = agentId;
        this.store = AgenticScopePersister.store;
    }
    private boolean hasStore() { return store != null; }
    public void update(DefaultAgenticScope scope) {
        // Scope.flush already owns its state lock. Do not introduce a scope-lock/key-lock inversion.
        if (hasStore() && !store.save(new AgenticScopeKey(agentId, scope.memoryId()), scope)) {
            throw new IllegalStateException("Failed to save agentic scope: " + scope.memoryId());
        }
    }
    public DefaultAgenticScope get(Object memoryId) {
        AgenticScopeKey key = new AgenticScopeKey(agentId, memoryId);
        return withKey(key, () -> {
            DefaultAgenticScope scope = inMemoryAgenticScope.get(key);
            if (scope == null && hasStore()) {
                scope = store.load(key).orElse(null);
                if (scope != null) inMemoryAgenticScope.put(key, scope);
            }
            return scope;
        });
    }
    /** Atomically acquire/create and initialize a scope within this registry. Failed initialization
     * remains inspectable through get, but cannot run another root without explicit eviction/replacement. */
    public DefaultAgenticScope getOrCreate(Object memoryId, Consumer<DefaultAgenticScope> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        AgenticScopeKey key = new AgenticScopeKey(agentId, memoryId);
        return withKey(key, () -> {
            if (!initializing.add(key)) throw new IllegalStateException("Reentrant agentic scope initialization: " + memoryId);
            try {
                Throwable failed = initializationFailures.get(key);
                if (failed != null) rethrow(failed);
                DefaultAgenticScope scope = get(memoryId);
                if (scope == null) {
                    scope = create(memoryId);
                    initialize(key, scope, initializer);
                }
                return scope;
            } finally { initializing.remove(key); }
        });
    }
    public DefaultAgenticScope create(Object memoryId) {
        AgenticScopeKey key = new AgenticScopeKey(agentId, memoryId);
        return withKey(key, () -> {
            DefaultAgenticScope scope = new DefaultAgenticScope(memoryId, hasStore() ? DefaultAgenticScope.Kind.PERSISTENT : DefaultAgenticScope.Kind.REGISTERED);
            register(scope);
            return scope;
        });
    }
    public DefaultAgenticScope createEphemeralAgenticScope() {
        return createEphemeralAgenticScope(scope -> { });
    }
    /** Initialize an ephemeral scope before another thread can acquire it by its generated ID. */
    public DefaultAgenticScope createEphemeralAgenticScope(Consumer<DefaultAgenticScope> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        DefaultAgenticScope scope = ephemeralAgenticScope();
        AgenticScopeKey key = new AgenticScopeKey(agentId, scope.memoryId());
        return withKey(key, () -> {
            initializing.add(key);
            try { register(scope); initialize(key, scope, initializer); return scope; }
            finally { initializing.remove(key); }
        });
    }
    private void initialize(AgenticScopeKey key, DefaultAgenticScope scope, Consumer<DefaultAgenticScope> initializer) {
        try { initializer.accept(scope); }
        catch (RuntimeException | Error failure) {
            if (inMemoryAgenticScope.get(key) == scope) initializationFailures.put(key, failure);
            throw failure;
        }
    }
    private void register(DefaultAgenticScope scope) {
        AgenticScopeKey key = new AgenticScopeKey(agentId, scope.memoryId());
        DefaultAgenticScope previous = inMemoryAgenticScope.put(key, scope);
        try {
            update(scope);
            initializationFailures.remove(key);
        } catch (RuntimeException | Error failure) {
            // Preserve a valid prior scope or a replacement installed by a reentrant store callback.
            if (previous == null) {
                if (inMemoryAgenticScope.remove(key, scope)) initializationFailures.put(key, failure);
            } else inMemoryAgenticScope.replace(key, scope, previous);
            scope.cancelPendingTasks(failure);
            // A write may already have happened. Do not blindly delete or replay it.
            throw failure;
        }
    }
    public boolean evict(Object memoryId, AgentListener listener) {
        AgenticScopeKey key = new AgenticScopeKey(agentId, memoryId);
        return withKey(key, () -> {
            DefaultAgenticScope scope = inMemoryAgenticScope.remove(key);
            boolean removed = scope != null;
            boolean failedInitializationRemoved = initializationFailures.remove(key) != null;
            if (removed) beforeAgenticScopeDestroyed(listener, scope);
            if (hasStore()) return store.delete(key) || removed || failedInitializationRemoved;
            return removed || failedInitializationRemoved;
        });
    }
    public Set<AgenticScopeKey> getAllAgenticScopeKeysInMemory() { return inMemoryAgenticScope.keySet(); }
    public void clearInMemory() {
        // Retain failed-initialization gates; clearing a cache must not retry an uncertain write.
        for (AgenticScopeKey key : inMemoryAgenticScope.keySet()) {
            withKey(key, () -> { inMemoryAgenticScope.remove(key); return null; });
        }
    }
    private <T> T withKey(AgenticScopeKey key, Supplier<T> action) {
        KeyLock entry;
        synchronized (keyLocks) {
            entry = keyLocks.get(key);
            if (entry == null) { entry = new KeyLock(); keyLocks.put(key, entry); }
            entry.references++;
        }
        boolean acquired = false;
        try {
            entry.lock.lockInterruptibly(); acquired = true;
            return action.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("Interrupted while waiting for agentic scope access");
            cancelled.initCause(interrupted); throw cancelled;
        } finally {
            if (acquired) entry.lock.unlock();
            synchronized (keyLocks) { if (--entry.references == 0) keyLocks.remove(key, entry); }
        }
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        throw (RuntimeException) failure;
    }
    private static final class KeyLock {
        final ReentrantLock lock = new ReentrantLock(true);
        int references;
    }
}
