package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.InvocationTasks;
import dev.langchain4j.agentic.observability.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.util.concurrent.atomic.*;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Failures around the planner must release resources before control returns to the caller. */
public class AgenticRootBoundaryJdk8Test {
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    public interface Ephemeral { @Agent String run(); }
    @After public void resetStore() { AgenticScopePersister.setStore(null); }
    @Test(timeout=8000) public void beforeCallRuntimeFailureCancelsResources() { run("before-runtime"); }
    @Test(timeout=8000) public void beforeCallErrorCancelsResources() { run("before-error"); }
    @Test(timeout=8000) public void beforeListenerErrorCancelsResources() { run("listener-before"); }
    @Test(timeout=8000) public void afterListenerErrorCancelsResources() { run("listener-after"); }
    @Test(timeout=8000) public void finalSaveFailureCancelsResourcesAndRetainsPrimary() { run("save-runtime"); }
    @Test(timeout=8000) public void finalSaveErrorCancelsResourcesAndRetainsPrimary() { run("save-error"); }
    @Test(timeout=8000) public void listenerRuntimeFailureRetainsLogAndContinueContract() { run("listener-runtime"); }
    @Test(timeout=8000) public void ephemeralBeforeCallFailureEvictsScope() { run("ephemeral"); }

    @Test(timeout=8000) public void reusedSessionCreatesOwnerBeforeFailingBeforeCall() {
        AtomicInteger calls = new AtomicInteger(), released = new AtomicInteger();
        AtomicReference<InvocationTasks> first = new AtomicReference<>(), second = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("second-before");
        Object step = AgenticServices.nonAiAgentBuilder(scope -> "ok").name("step").outputKey("answer").build();
        Work work = AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer")
                .beforeCall(scope -> {
                    InvocationTasks owner = ((DefaultAgenticScope) scope).invocationTasks();
                    if (calls.getAndIncrement() == 0) first.set(owner);
                    else { second.set(owner); owner.register(released::incrementAndGet); throw failure; }
                }).build();
        assertEquals("ok", work.run("same"));
        try { work.run("same"); fail("expected second invocation failure"); }
        catch (IllegalStateException actual) { assertSame(failure, actual); }
        assertNotSame("beforeCall must not acquire the previous finished owner", first.get(), second.get());
        assertEquals(1, released.get());
        AtomicInteger oldLate = new AtomicInteger();
        first.get().register(oldLate::incrementAndGet);
        assertEquals("second invocation cancellation must not poison the old successful owner", 0, oldLate.get());
        first.get().cancel(new IllegalStateException("fixture cleanup"));
    }

    @Test(timeout=8000) public void normalSuspensionRetainsLiveResources() { suspension(false); }
    @Test(timeout=8000) public void suspensionListenerErrorCancelsAndRetainsPendingMarker() { suspension(true); }
    private static void suspension(boolean failListener) {
        AssertionError failure = new AssertionError("suspension-listener");
        AtomicInteger released = new AtomicInteger(), errors = new AtomicInteger();
        AtomicReference<InvocationTasks> owner = new AtomicReference<>();
        AtomicReference<DefaultAgenticScope> captured = new AtomicReference<>();
        Object human = AgenticServices.humanInTheLoopBuilder().outputKey("answer")
                .responseProvider(scope -> new dev.langchain4j.agentic.internal.SuspendedResponse<String>("approval")).build();
        Work work = AgenticServices.sequenceBuilder(Work.class).subAgents(human).outputKey("answer")
                .beforeCall(scope -> {
                    captured.set((DefaultAgenticScope) scope); owner.set(captured.get().invocationTasks());
                    owner.get().register(released::incrementAndGet);
                }).listener(new AgentListener() {
                    @Override public void onAgenticSystemSuspended(AgenticScope scope) {
                        if (failListener) throw failure;
                    }
                    @Override public void onAgentInvocationError(AgentInvocationError error) {
                        errors.incrementAndGet(); assertSame(failure, error.error());
                    }
                }).build();
        try { work.run("paused"); fail("expected pause or listener failure"); }
        catch (AgenticSystemSuspendedException paused) { assertFalse(failListener); }
        catch (AssertionError actual) { assertTrue(failListener); assertSame(failure, actual); }
        assertEquals(java.util.Collections.singleton("approval"), captured.get().pendingResponseIds());
        assertEquals(failListener ? 1 : 0, released.get());
        assertEquals(failListener ? 1 : 0, errors.get());
        assertEquals(failListener, owner.get().finished());
        owner.get().cancel(new IllegalStateException("fixture cleanup"));
    }

    private static void run(String mode) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        boolean success = mode.equals("listener-runtime");
        Throwable primary = mode.endsWith("error") || mode.startsWith("listener-") && !success
                ? new AssertionError("boundary-primary") : new IllegalStateException("boundary-primary");
        AssertionError cleanup = new AssertionError("cleanup-secondary");
        AtomicReference<DefaultAgenticScope> captured = new AtomicReference<>();
        AtomicReference<InvocationTasks> owner = new AtomicReference<>();
        AtomicReference<Throwable> notified = new AtomicReference<>();
        AtomicInteger released = new AtomicInteger(), body = new AtomicInteger(), errors = new AtomicInteger(), saves = new AtomicInteger();
        AtomicBoolean armSave = new AtomicBoolean();
        if (mode.startsWith("save")) {
            AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore() {
                @Override public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) {
                    if (armSave.get()) { saves.incrementAndGet(); throwUnchecked(primary); }
                    return super.save(key, scope);
                }
            });
        }
        AgentListener listener = new AgentListener() {
            @Override public void beforeAgentInvocation(AgentRequest request) {
                if (request.agent().type() == Work.class && mode.equals("listener-before")) throwUnchecked(primary);
                if (request.agent().type() == Work.class && success) throwUnchecked(primary);
            }
            @Override public void afterAgentInvocation(AgentResponse response) {
                if (response.agent().type() == Work.class) {
                    armSave.set(true);
                    if (mode.equals("listener-after") || success) throwUnchecked(primary);
                }
            }
            @Override public void onAgentInvocationError(AgentInvocationError error) {
                errors.incrementAndGet(); notified.set(error.error()); throw cleanup;
            }
        };
        Object step = AgenticServices.nonAiAgentBuilder(scope -> { body.incrementAndGet(); return "ok"; })
                .name("step").outputKey("answer").build();
        java.util.function.Consumer<AgenticScope> before = scope -> {
            DefaultAgenticScope actual = (DefaultAgenticScope) scope;
            captured.set(actual); owner.set(actual.invocationTasks());
            owner.get().register(released::incrementAndGet);
            if (mode.startsWith("before") || mode.equals("ephemeral")) throwUnchecked(primary);
        };
        Object work = mode.equals("ephemeral")
                ? AgenticServices.sequenceBuilder(Ephemeral.class).subAgents(step).outputKey("answer").beforeCall(before).listener(listener).build()
                : AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").beforeCall(before).listener(listener).build();
        if (success) {
            assertEquals("ok", ((Work) work).run("session"));
            assertEquals(0, errors.get()); assertEquals(0, released.get()); assertTrue(owner.get().finished());
            owner.get().cancel(new IllegalStateException("fixture cleanup"));
            return;
        }
        try {
            if (work instanceof Work) ((Work) work).run("session"); else ((Ephemeral) work).run();
            fail("expected original failure");
        } catch (RuntimeException | Error failure) { assertSame(primary, failure); }
        // All these checks precede any fixture cleanup.
        assertEquals("owned resource leaked", 1, released.get());
        assertTrue(owner.get().finished());
        AtomicInteger late = new AtomicInteger(); owner.get().register(late::incrementAndGet);
        assertEquals("failed owner must cancel late resources", 1, late.get());
        assertEquals(1, errors.get()); assertSame(primary, notified.get());
        assertTrue(java.util.Arrays.asList(primary.getSuppressed()).contains(cleanup));
        assertEquals(mode.startsWith("before") || mode.equals("ephemeral") || mode.equals("listener-before") ? 0 : 1, body.get());
        if (mode.startsWith("save")) assertEquals("one final save and one failure snapshot attempt", 2, saves.get());
        if (work instanceof Work) assertSame(captured.get(), ((Work) work).getAgenticScope("session"));
        else assertTrue(((dev.langchain4j.agentic.internal.AgenticScopeOwner) work).registry().getAllAgenticScopeKeysInMemory().isEmpty());
    }
    private static void throwUnchecked(Throwable error) {
        if (error instanceof Error) throw (Error) error;
        throw (RuntimeException) error;
    }
}
