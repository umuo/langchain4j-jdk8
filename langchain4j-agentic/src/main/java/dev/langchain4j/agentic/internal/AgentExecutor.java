package dev.langchain4j.agentic.internal;

import static dev.langchain4j.agentic.observability.ListenerNotifierUtil.agentError;
import static dev.langchain4j.agentic.observability.ListenerNotifierUtil.agentSkipped;
import static dev.langchain4j.agentic.scope.DefaultAgenticScope.isSerializable;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.AgentCondition;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticSystemSuspendedException;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.TokenStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentExecutor implements AgentInstance, InternalAgent {

    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutor.class);

    public Object execute(DefaultAgenticScope agenticScope, PlannerExecutor planner) {
        return execute(agenticScope, planner, agentInvoker.async());
    }

    public Object syncExecute(DefaultAgenticScope agenticScope, PlannerExecutor planner) {
        if (agentInvoker.async()) {
            LOG.info("Executing '{}' agent in a sync way even if declared as async", agentInvoker.name());
        }
        return execute(agenticScope, planner, false);
    }

    private Object execute(DefaultAgenticScope agenticScope, PlannerExecutor planner, boolean async) {
        Object invokedAgent = (agent instanceof AgenticScopeOwner ? ((AgenticScopeOwner) agent).withAgenticScope(agenticScope) : agent);
        try {
            return agenticScope.invocationTasks().call(() -> internalExecute(agenticScope, invokedAgent, planner, async));
        } catch (PlannerInvocationFailure failure) {
            // Leave the agent-recovery catch before restoring the original public exception.
            throw failure.failure;
        }
    }

    private Object handleAgentFailure(AgentInvocationException e, DefaultAgenticScope agenticScope, Object invokedAgent, PlannerExecutor planner, AgentInvocationArguments args) {
        InvocationTasks.check(agenticScope);
        ErrorRecoveryResult recoveryResult = agenticScope.handleError(agentInvoker.name(), e);
        switch(recoveryResult.type()) {
            case THROW_EXCEPTION:
                {
                    ActivationConditions.clearActivation(this, agenticScope);
                    throw e;
                }
            case RETRY:
                return internalExecute(agenticScope, invokedAgent, planner, false);
            case RETURN_RESULT:
                return completeAgentInvocation(recoveryResult.result(), agenticScope, invokedAgent, planner, args);
            default:
                throw new IllegalStateException("Unexpected enum value");
        }
    }

    private Object internalExecute(DefaultAgenticScope agenticScope, Object invokedAgent, PlannerExecutor planner, boolean async) {
        AgentInvocationArguments args = null;
        try {
            AgentCondition condition = agentInvoker.activationCondition();
            if (condition != null && !ActivationConditions.isActivated(this, agenticScope)) {
                if (!isActive(condition, agenticScope)) {
                    return skip(agenticScope, planner, "activation condition is false: " + condition.description());
                }
                ActivationConditions.markActivated(this, agenticScope);
            }
            try {
                args = agentInvoker.toInvocationArguments(agenticScope);
            } catch (MissingArgumentException e) {
                if (optional()) {
                    return skip(agenticScope, planner, "missing argument '" + e.argumentName() + "'");
                }
                throw e;
            }
            Object response = agentResponse(agenticScope, invokedAgent, planner, args, async);
            return completeAgentInvocation(response, agenticScope, invokedAgent, planner, args);
        } catch (AgenticSystemSuspendedException e) {
            if (planner != null) {
                planner.onSubagentSuspended();
            }
            return null;
        } catch (AgentInvocationException e) {
            return handleAgentFailure(e, agenticScope, invokedAgent, planner, args);
        }
    }

    private boolean isActive(AgentCondition condition, DefaultAgenticScope agenticScope) {
        try {
            return condition.test(agenticScope);
        } catch (RuntimeException e) {
            AgentInvocationException failure = new AgentInvocationException("Activation condition of agent '" + name() + "' failed", e);
            agentError(agentInvoker.listener(), agenticScope, agentInvoker, dev.langchain4j.internal.Jdk8.map(), failure);
            throw failure;
        }
    }

    /**
     * Skips this agent without invoking it: nothing is written to the scope and the response is the current value
     * of the output key. The skip is a plain value also for an async agent, and its invocation is not registered in
     * the scope, but the planner is notified so that it can move on to the next agent.
     */
    private Object skip(DefaultAgenticScope agenticScope, PlannerExecutor planner, String reason) {
        LOG.info("Skipping agent '{}': {}", name(), reason);
        String outputKey = outputKey();
        Object response = outputKey == null || dev.langchain4j.internal.Jdk8.isBlank(outputKey) ? null : agenticScope.readState(outputKey);
        agentSkipped(agentInvoker.listener(), agenticScope, agentInvoker, reason);
        if (planner != null) {
            notifyPlanner(planner, new AgentInvocation(type(), name(), agentId(), dev.langchain4j.internal.Jdk8.map(), response, reason));
        }
        return response;
    }

    private Object completeAgentInvocation(Object response, DefaultAgenticScope agenticScope, Object invokedAgent, PlannerExecutor planner, AgentInvocationArguments args) {
        ActivationConditions.clearActivation(this, agenticScope);
        String outputKey = agentInvoker.outputKey();
        if (outputKey != null && !dev.langchain4j.internal.Jdk8.isBlank(outputKey)) {
            Object stateValue = response instanceof ResultWithAgenticScope<?> ? ((ResultWithAgenticScope<?>) response).result() : response;
            agenticScope.writeState(outputKey, stateValue);
        }
        Map<String, Object> namedArgs = args != null ? args.namedArgs() : dev.langchain4j.internal.Jdk8.map();
        AgentInvocation agentInvocation = new AgentInvocation(type(), name(), agentId(), namedArgs, isSerializable(response) ? response : "<unknown>");
        agenticScope.registerAgentInvocation(agentInvocation, invokedAgent);
        if (planner != null) {
            notifyPlanner(planner, agentInvocation);
        }
        return response;
    }

    private static void notifyPlanner(PlannerExecutor planner, AgentInvocation invocation) {
        try {
            planner.onSubagentInvoked(invocation);
        } catch (AgentInvocationException failure) {
            // A planner failure is not a new failed attempt of the reporting agent.
            throw new PlannerInvocationFailure(failure);
        }
    }

    private static final class PlannerInvocationFailure extends RuntimeException {
        final AgentInvocationException failure;
        PlannerInvocationFailure(AgentInvocationException failure) {
            super(failure);
            this.failure = failure;
        }
    }

    private Object agentResponse(DefaultAgenticScope agenticScope, Object invokedAgent, PlannerExecutor planner, AgentInvocationArguments args, boolean async) {
        // Capture this dispatch's decision before the planner advances on another thread.
        boolean consumeStreaming = planner != null && !planner.propagateStreaming();
        DeferredScopeContext.Frame binding = new DeferredScopeContext.Frame(agenticScope, outputKey());
        if (async) {
            return new AsyncResponse<>(() -> binding.call(() -> {
                try {
                    return invokeDeferredAttempt(agenticScope, invokedAgent, args, consumeStreaming);
                } catch (RuntimeException e) {
                    return recoverDeferredFailure(e, agenticScope, invokedAgent, args, consumeStreaming);
                }
            }), agenticScope.invocationTasks());
        }
        Object response = agentInvoker.invoke(agenticScope, invokedAgent, args);
        if (consumeStreaming && response instanceof TokenStream) {
            return new RecoverableStream((TokenStream) response,
                    error -> binding.call(() -> recoverDeferredFailure(error, agenticScope, invokedAgent, args, true)));
        }
        return response;
    }

    private Object invokeDeferredAttempt(DefaultAgenticScope scope, Object invokedAgent, AgentInvocationArguments args, boolean consumeStreaming) {
        Object response = agentInvoker.invoke(scope, invokedAgent, args);
        return consumeStreaming && response instanceof TokenStream
                ? new StreamingResponse((TokenStream) response).blockingGet()
                : response;
    }

    /** A deferred operation was already published; attempts must not publish it again. */
    private Object recoverDeferredFailure(Throwable initialFailure, DefaultAgenticScope scope, Object invokedAgent,
                                          AgentInvocationArguments args, boolean consumeStreaming) {
        Throwable failure = initialFailure;
        AgentInvocationArguments currentArgs = args;
        while (true) {
            InvocationTasks.check(scope);
            if (failure instanceof AgenticSystemSuspendedException) throw (AgenticSystemSuspendedException) failure;
            if (failure instanceof Error) throw (Error) failure;
            AgentInvocationException invocationFailure;
            if (failure instanceof AgentInvocationException) {
                invocationFailure = (AgentInvocationException) failure;
            } else {
                invocationFailure = new AgentInvocationException("Failed to consume response of agent '" + name() + "'",
                        failure instanceof Exception ? (Exception) failure : new RuntimeException(failure));
                agentError(agentInvoker.listener(), scope, agentInvoker, currentArgs.namedArgs(), invocationFailure);
            }
            ErrorRecoveryResult recovery = scope.handleError(name(), invocationFailure);
            switch (recovery.type()) {
                case THROW_EXCEPTION:
                    ActivationConditions.clearActivation(this, scope);
                    throw invocationFailure;
                case RETURN_RESULT:
                    return recovery.result();
                case RETRY:
                    try {
                        // A recovery handler can update arguments in the scope for the next attempt.
                        currentArgs = agentInvoker.toInvocationArguments(scope);
                        return invokeDeferredAttempt(scope, invokedAgent, currentArgs, consumeStreaming);
                    } catch (RuntimeException nextFailure) {
                        failure = nextFailure;
                    }
                    break;
                default:
                    throw new IllegalStateException("Unexpected recovery type: " + recovery.type());
            }
        }
    }

    /** Intermediate streaming recovery runs off the HTTP reader, including retry I/O. */
    private static final class RecoverableStream implements DelayedResponse<Object> {
        private final java.util.concurrent.CompletableFuture<Object> result = new java.util.concurrent.CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.function.Function<Throwable, Object> recovery;

        private RecoverableStream(TokenStream stream, java.util.function.Function<Throwable, Object> recovery) {
            this.recovery = recovery;
            try {
                stream.onCompleteResponse(response -> {
                    try {
                        String text = response.aiMessage().text();
                        if (terminal.compareAndSet(false, true)) result.complete(text);
                    } catch (RuntimeException invalidResponse) {
                        failed(invalidResponse);
                    }
                }).onError(this::failed).start();
            } catch (RuntimeException error) {
                failed(error);
            }
        }

        private void failed(Throwable error) {
            if (!terminal.compareAndSet(false, true)) return;
            try {
                dev.langchain4j.internal.DefaultExecutorProvider.getDefaultExecutor().execute(() -> {
                    try {
                        result.complete(recovery.apply(error));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException rejected) {
                result.completeExceptionally(rejected);
            }
        }

        @Override public boolean isDone() { return result.isDone(); }
        @Override public Object blockingGet() { return DelayedResponse.join(result); }
        @Override public String toString() { return String.valueOf(result()); }
    }

    @Override
    public Class<?> type() {
        return agentInvoker.type();
    }

    @Override
    public Class<? extends Planner> plannerType() {
        return agentInvoker.plannerType();
    }

    @Override
    public String name() {
        return agentInvoker.name();
    }

    @Override
    public String agentId() {
        return agentInvoker.agentId();
    }

    @Override
    public String description() {
        return agentInvoker.description();
    }

    @Override
    public Type outputType() {
        return agentInvoker.outputType();
    }

    @Override
    public String outputKey() {
        return agentInvoker.outputKey();
    }

    @Override
    public List<AgentArgument> arguments() {
        return agentInvoker.arguments();
    }

    @Override
    public List<AgentInstance> subagents() {
        return agentInvoker.subagents();
    }

    @Override
    public boolean async() {
        return agentInvoker.async();
    }

    @Override
    public boolean optional() {
        return agentInvoker.optional();
    }

    @Override
    public AgentCondition activationCondition() {
        return agentInvoker.activationCondition();
    }

    @Override
    public AgenticSystemTopology topology() {
        return agentInvoker.topology();
    }

    @Override
    public AgentInstance parent() {
        return agentInvoker.parent();
    }

    @Override
    public void setParent(InternalAgent parent) {
        agentInvoker.setParent(parent);
    }

    @Override
    public boolean compensateOnError() {
        return agentInvoker.compensateOnError();
    }

    @Override
    public void enableCrossAgentCompensation() {
        agentInvoker.enableCrossAgentCompensation();
    }

    @Override
    public void registerInheritedParentListener(AgentListener parentListener) {
        agentInvoker.registerInheritedParentListener(parentListener);
    }

    @Override
    public void appendId(final String idSuffix) {
        agentInvoker.appendId(idSuffix);
    }

    @Override
    public AgentListener listener() {
        return agentInvoker.listener();
    }

    @Override
    public <T extends AgentInstance> T as(Class<T> agentInstanceClass) {
        return agentInvoker.as(agentInstanceClass);
    }

    void setParent(InternalAgent parent, int index) {
        setParent(parent);
        propagateParentIndex(agentInvoker, index);
    }

    private void propagateParentIndex(InternalAgent agent, int index) {
        agent.appendId("$" + index);
        for (AgentInstance subagent : agent.subagents()) {
            if (subagent instanceof InternalAgent) {
                propagateParentIndex(((InternalAgent) subagent), index);
            }
        }
    }

    private final AgentInvoker agentInvoker;

    public AgentInvoker agentInvoker() {
        return agentInvoker;
    }

    private final Object agent;

    public Object agent() {
        return agent;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentExecutor(@com.fasterxml.jackson.annotation.JsonProperty("agentInvoker") AgentInvoker agentInvoker, @com.fasterxml.jackson.annotation.JsonProperty("agent") Object agent) {
        this.agentInvoker = agentInvoker;
        this.agent = agent;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(agentInvoker, ((AgentExecutor) obj).agentInvoker) && java.util.Objects.equals(agent, ((AgentExecutor) obj).agent));
    }

    public final int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(agentInvoker)) + java.util.Objects.hashCode(agent));
    }

    public final String toString() {
        return "AgentExecutor[" + "agentInvoker=" + agentInvoker + ", agent=" + agent + "]";
    }
}
