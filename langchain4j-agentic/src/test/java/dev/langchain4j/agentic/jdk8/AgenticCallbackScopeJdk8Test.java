package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.internal.InvocationTasks;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.internal.CancellationContext;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticCallbackScopeJdk8Test {
    static CancellationContext.Registrar capture(DefaultAgenticScope scope) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        return scope.invocationTasks().call(CancellationContext::current);
    }
    static void rejected(Runnable action) {
        try { action.run(); fail("cancelled callback wrote state"); } catch (CancellationException expected) { }
    }
    @Test(timeout=5000) public void capturedCallbackCannotWriteAfterItsOwnerFails() {
        DefaultAgenticScope scope=DefaultAgenticScope.ephemeralAgenticScope();
        CancellationContext.Registrar owner=capture(scope);
        scope.cancelPendingTasks(new IllegalStateException("primary"));
        CancellationContext.run(owner,()->{
            rejected(()->scope.writeState("late","stale"));
            rejected(()->scope.writeStateIfAbsent("absent","stale"));
            rejected(()->scope.writeStates(java.util.Collections.singletonMap("bulk","stale")));
        });
        assertTrue(scope.state().isEmpty());assertNull(InvocationTasks.bound(scope));assertNull(CancellationContext.current());
    }
    @Test(timeout=5000) public void nestedCallbackRestoresOtherOwnerAndLeavesReusedThreadClean() throws Exception {
        DefaultAgenticScope failed=DefaultAgenticScope.ephemeralAgenticScope(),other=DefaultAgenticScope.ephemeralAgenticScope();
        CancellationContext.Registrar old=capture(failed),active=capture(other);failed.cancelPendingTasks(new IllegalStateException("primary"));
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            executor.submit(()->{
                CancellationContext.run(active,()->{
                    assertSame(other.invocationTasks(),InvocationTasks.bound(other));
                    CancellationContext.run(old,()->rejected(()->failed.writeState("late","stale")));
                    assertSame(other.invocationTasks(),InvocationTasks.bound(other));other.writeState("result","kept");
                });
                assertNull(InvocationTasks.bound(other));assertNull(InvocationTasks.bound(failed));assertNull(CancellationContext.current());
            }).get(2,TimeUnit.SECONDS);
            executor.submit(()->{assertNull(CancellationContext.current());assertNull(InvocationTasks.bound(other));assertNull(InvocationTasks.bound(failed));}).get(2,TimeUnit.SECONDS);
            assertEquals("kept",other.readState("result"));assertTrue(failed.state().isEmpty());
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test(timeout=5000) public void callbackErrorRestoresBothBindingsWithoutReplacingError() {
        DefaultAgenticScope scope=DefaultAgenticScope.ephemeralAgenticScope();CancellationContext.Registrar owner=capture(scope);
        AssertionError primary=new AssertionError("callback fixture");
        try {CancellationContext.run(owner,()->{assertNotNull(InvocationTasks.bound(scope));throw primary;});fail("must throw");}
        catch(AssertionError expected){assertSame(primary,expected);}
        assertNull(InvocationTasks.bound(scope));assertNull(CancellationContext.current());
        scope.writeState("external","allowed");assertEquals("allowed",scope.readState("external"));
    }
    @Test(timeout=5000) public void oldCallbackDoesNotAdoptNewRootGeneration() {
        DefaultAgenticScope scope=DefaultAgenticScope.ephemeralAgenticScope();
        CancellationContext.Registrar old=capture(scope);scope.cancelPendingTasks(new IllegalStateException("primary"));scope.rootCallStarted(null);
        CancellationContext.Registrar fresh=capture(scope);
        CancellationContext.run(fresh,()->{
            scope.writeState("result","fresh");
            CancellationContext.run(old,()->rejected(()->scope.writeState("result","stale")));
            scope.writeStateIfAbsent("other","fresh");
        });
        assertEquals("fresh",scope.readState("result"));assertEquals("fresh",scope.readState("other"));
        assertNull(InvocationTasks.bound(scope));assertNull(CancellationContext.current());
    }
}
