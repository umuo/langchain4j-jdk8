package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.internal.*;
import dev.langchain4j.agentic.observability.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticFailureCleanupJdk8Test {
    public interface Stable extends AgenticScopeAccess {@Agent String run(@MemoryId String id);}
    public interface Ephemeral extends AgenticScopeAccess {@Agent String run();}
    @Test(timeout=15000) public void failedSiblingDoesNotWaitOnHuman() throws Exception {child("human");}
    @Test(timeout=15000) public void failedSiblingDoesNotWaitOnPendingResponse() throws Exception {child("pending");}
    @Test(timeout=15000) public void failureUnwrapsAsyncHumanWithoutWaitingOnAnswer() throws Exception {child("async-human");}
    @Test(timeout=15000) public void asyncFailureDoesNotWaitOnHuman() throws Exception {child("async-bad");}
    @Test(timeout=15000) public void persistenceFailureIsSuppressedOnOriginalFailure() throws Exception {child("store");}
    @Test(timeout=15000) public void errorListenerFailureDoesNotPreventEphemeralEviction() throws Exception {child("listener");}
    @Test(timeout=15000) public void unfinishedComputationDoesNotDelayPrimaryFailure() throws Exception {child("inflight");}
    @Test(timeout=15000) public void failurePersistsAnUnansweredMarkerWithoutCompletingIt() throws Exception {child("persist");}
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"failure-cleanup-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticFailureCleanupJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("child timed out: "+mode,p.waitFor(8,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally {if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));String mode=args[0];
        AtomicBoolean cleaning=new AtomicBoolean();AtomicReference<Throwable> primary=new AtomicReference<>();AtomicReference<AgenticScope> scopeSeen=new AtomicReference<>();AtomicInteger errors=new AtomicInteger(),asks=new AtomicInteger(),failureSaves=new AtomicInteger();
        AtomicReference<AgenticScopeKey> lastKey=new AtomicReference<>();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        AgenticRecoveryJdk8Test.JsonStore store=new AgenticRecoveryJdk8Test.JsonStore(){
            @Override public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){lastKey.set(key);if(cleaning.get()){failureSaves.incrementAndGet();if(mode.equals("store"))throw new IllegalStateException("save-cleanup");}return super.save(key,scope);}
        };
        AgenticScopePersister.setStore(mode.equals("store")||mode.equals("persist")?store:null);
        Object human=AgenticServices.humanInTheLoopBuilder().async(mode.equals("async-human")).outputKey("a").responseProvider(scope->{asks.incrementAndGet();scopeSeen.set(scope);return mode.equals("pending")?new PendingResponse<String>("approval"):new SuspendedResponse<String>("approval");}).build();
        Object bad=AgenticServices.nonAiAgentBuilder(scope->{
            scopeSeen.set(scope);if(mode.equals("inflight"))assertTrue(entered.await(3,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline){Object value=scope.state().get("a");if(value instanceof DeferredResponse<?>||value instanceof AsyncResponse<?> && ((AsyncResponse<?>)value).isDone())throw new IllegalStateException("primary failure");Thread.yield();}
            throw new AssertionError("human response was not published");
        }).name("doomed").async(mode.equals("async-bad")).outputKey("z").build();
        AgentListener listener=new AgentListener(){@Override public void onAgentInvocationError(AgentInvocationError event){primary.set(event.error());cleaning.set(true);if(mode.equals("listener"))throw new AssertionError("listener-cleanup");}};
        Object background=AgenticServices.nonAiAgentBuilder(scope->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return "released";}).name("background").async(true).outputKey("x").build();
        Object[] agents=mode.equals("inflight")?new Object[]{human,bad,background}:new Object[]{human,bad};
        Object workflow=mode.equals("listener")?AgenticServices.parallelBuilder(Ephemeral.class).subAgents(agents).listener(listener).errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build()
                :AgenticServices.parallelBuilder(Stable.class).subAgents(agents).listener(listener).errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
        try {if(workflow instanceof Stable)((Stable)workflow).run("session");else((Ephemeral)workflow).run();fail("must fail");}
        catch(AgentInvocationException failure){
            assertSame(primary.get(),failure);Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();assertEquals("primary failure",cause.getMessage());
            if(mode.equals("store")||mode.equals("listener")){String expected=mode.equals("store")?"save-cleanup":"listener-cleanup";assertTrue(Arrays.toString(failure.getSuppressed()),Arrays.stream(failure.getSuppressed()).anyMatch(error->expected.equals(error.getMessage())));}
        } finally {release.countDown();}
        if(mode.equals("inflight")){try {scopeSeen.get().readState("x");fail("owned background must be cancelled");}catch(java.util.concurrent.CancellationException expected){}}
        if(mode.equals("persist")){assertEquals(1,failureSaves.get());assertEquals(Collections.singleton("approval"),store.load(lastKey.get()).get().pendingResponseIds());}
        assertEquals(1,errors.get());assertEquals(1,asks.get());assertNotNull(scopeSeen.get());assertEquals(Collections.singleton("approval"),scopeSeen.get().pendingResponseIds());
        if(mode.equals("listener"))assertTrue(((AgenticScopeOwner)workflow).registry().getAllAgenticScopeKeysInMemory().isEmpty());
        else assertSame(scopeSeen.get(),((Stable)workflow).getAgenticScope("session"));
    }
}
