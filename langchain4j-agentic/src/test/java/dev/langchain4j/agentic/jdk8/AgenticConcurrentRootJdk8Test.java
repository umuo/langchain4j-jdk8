package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.InvocationTasks;
import dev.langchain4j.agentic.internal.DeferredScopeContext;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.observability.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticConcurrentRootJdk8Test {
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id, @V("value") String value); }
    public interface Pausing extends AgenticScopeAccess { @Agent ResultWithAgenticScope<String> run(@MemoryId String id); }
    @Test(timeout=15000) public void sameSessionRootsSerializeBeforeInputWrites()throws Exception{child("body");}
    @Test(timeout=15000) public void asyncComputationDrainRetainsRootExclusivity()throws Exception{child("async");}
    @Test(timeout=15000) public void failedRootReleasesWaiterWithFreshOwner()throws Exception{child("failure");}
    @Test(timeout=15000) public void beforeCallRunsUnderRootExclusivity()throws Exception{child("before");}
    @Test(timeout=15000) public void successListenerRemainsInsideRootBoundary()throws Exception{child("after");}
    @Test(timeout=15000) public void finalSaveRetainsRootExclusivity()throws Exception{child("save");}
    @Test(timeout=15000) public void finalSaveFailureDoesNotPoisonWaitingRoot()throws Exception{child("save-failure");}
    @Test(timeout=15000) public void interruptedWaiterDoesNotCancelActiveRoot()throws Exception{child("interrupt");}
    @Test(timeout=15000) public void sameThreadRootReentryCannotOverwriteOuterInputs()throws Exception{child("reentrant");}
    @Test(timeout=15000) public void ownedAsyncWorkerReentryCannotDeadlockOrCancelOuterRoot()throws Exception{child("worker-reentrant");}
    @Test(timeout=15000) public void nestedSubworkflowRemainsSupported()throws Exception{child("nested");}
    @Test(timeout=15000) public void suspensionReleasesExecutionRightForResume()throws Exception{child("pause");}

    @Test(timeout=15000) public void completedCapturedCallbackStartsIndependentRootAndRestoresContext()throws Exception{child("completed-context");}
    @Test(timeout=15000) public void cancelledCapturedCallbackCannotPoisonNewRoot()throws Exception{child("cancelled-context");}

    @Test(timeout=15000) public void failingNewRootRestoresOldCallbackWithoutCancellingParent()throws Exception{child("failed-context");}

    @Test(timeout=15000) public void unrelatedDeferredScopeFrameSurvivesIndependentRoot()throws Exception{child("other-frame-context");}

    static void child(String mode)throws Exception{
        Path log=Files.createTempFile(Paths.get("target"),"concurrent-root-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticConcurrentRootJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean passed=false;
        try{boolean ended=p.waitFor(10,TimeUnit.SECONDS);assertTrue("child timed out: "+mode+"; output: "+new String(Files.readAllBytes(log),StandardCharsets.UTF_8),ended);assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());passed=true;}
        finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}if(passed)Files.deleteIfExists(log);else System.err.println("Retained child log: "+log);}
    }
    static void gate(CountDownLatch entered,CountDownLatch release){entered.countDown();try{assertTrue(release.await(6,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static Throwable original(Throwable failure){while(failure.getCause()!=null)failure=failure.getCause();return failure;}
    public static void main(String[] args)throws Exception{
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        if(mode.equals("reentrant")||mode.equals("worker-reentrant")){reentrant(mode.equals("worker-reentrant"));return;}
        if(mode.endsWith("-context")){capturedContext(mode.equals("cancelled-context"),mode.equals("failed-context"),mode.equals("other-frame-context"));return;}
        if(mode.equals("nested")){nested();return;}if(mode.equals("pause")){pause();return;}
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger sameStarts=new AtomicInteger(),releasedResources=new AtomicInteger(),rootErrors=new AtomicInteger();
        AtomicReference<InvocationTasks> firstOwner=new AtomicReference<>(),secondOwner=new AtomicReference<>();
        AtomicBoolean saveGate=new AtomicBoolean();AtomicBoolean waiterInterrupted=new AtomicBoolean();
        IllegalStateException primary=new IllegalStateException("first-root-primary");
        if(mode.startsWith("save"))AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore(){
            @Override public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){
                if(key.memoryId().equals("same")&&scope.hasState("answer")&&saveGate.compareAndSet(false,true)){
                    gate(entered,release);if(mode.equals("save-failure"))throw primary;
                }
                return super.save(key,scope);
            }
        });
        Object step=AgenticServices.nonAiAgentBuilder(scope->{
            String value=(String)scope.readState("value");
            if(value.equals("first")&&(mode.equals("body")||mode.equals("async")||mode.equals("failure")||mode.equals("interrupt")))gate(entered,release);
            if(value.equals("first")&&mode.equals("failure"))throw primary;
            assertEquals("root inputs changed during execution",value,scope.readState("value"));return value;
        }).name("echo").async(mode.equals("async")).outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").beforeCall(scope->{
            if(!scope.memoryId().equals("same"))return;
            sameStarts.incrementAndGet();String value=(String)scope.readState("value");InvocationTasks owner=((DefaultAgenticScope)scope).invocationTasks();
            if(value.equals("first")){firstOwner.set(owner);owner.register(releasedResources::incrementAndGet);if(mode.equals("before"))gate(entered,release);}
            if(value.equals("second"))secondOwner.set(owner);
        }).listener(new AgentListener(){
            @Override public void afterAgentInvocation(AgentResponse response){if(response.agent().type()==Work.class&&response.agenticScope().memoryId().equals("same")&&response.agenticScope().readState("value").equals("first")&&mode.equals("after"))gate(entered,release);}
            @Override public void onAgentInvocationError(AgentInvocationError error){if(error.agent().type()==Work.class)rootErrors.incrementAndGet();}
        }).build();
        AgenticInitializationJdk8Test.Call first=new AgenticInitializationJdk8Test.Call(()->work.run("same","first"));
        AgenticInitializationJdk8Test.Call second=new AgenticInitializationJdk8Test.Call(()->{try{return work.run("same","second");}finally{waiterInterrupted.set(Thread.currentThread().isInterrupted());}});
        AgenticInitializationJdk8Test.Call other=new AgenticInitializationJdk8Test.Call(()->work.run("other","other"));
        try{
            first.start();assertTrue(entered.await(3,TimeUnit.SECONDS));second.start();
            assertFalse("second root completed while first was active",second.done.await(150,TimeUnit.MILLISECONDS));
            assertEquals("waiting root entered beforeCall",1,sameStarts.get());
            assertEquals("waiting root overwrote active input","first",work.getAgenticScope("same").readState("value"));
            assertFalse("waiter finished/cancelled active owner",firstOwner.get().finished());assertEquals(0,releasedResources.get());
            other.start();other.ok();assertEquals("other",other.result.get());
            if(mode.equals("interrupt")){
                second.thread.interrupt();second.await();assertTrue(second.failure.get() instanceof CancellationException);assertTrue(waiterInterrupted.get());
                assertEquals(1,sameStarts.get());assertFalse(firstOwner.get().finished());assertEquals(0,releasedResources.get());assertEquals(0,rootErrors.get());
            }
            release.countDown();first.await();
            boolean failed=mode.equals("failure")||mode.equals("save-failure");
            if(failed){assertNotNull(first.failure.get());assertSame(primary,original(first.failure.get()));assertEquals(1,releasedResources.get());}
            else{first.ok();assertEquals("first",first.result.get());assertEquals(0,releasedResources.get());}
            if(mode.equals("interrupt")){assertEquals("third",work.run("same","third"));assertEquals(2,sameStarts.get());}
            else{second.ok();assertEquals("second",second.result.get());assertEquals(2,sameStarts.get());assertNotSame("waiter reused prior root owner",firstOwner.get(),secondOwner.get());assertTrue(secondOwner.get().finished());}
            assertEquals(failed?1:0,rootErrors.get());assertTrue(firstOwner.get().finished());
        }finally{release.countDown();first.stop();if(second.thread.getState()!=Thread.State.NEW)second.stop();if(other.thread.getState()!=Thread.State.NEW)other.stop();}
    }
    static void reentrant(boolean worker){
        AtomicReference<Work> reference=new AtomicReference<>();AtomicInteger rejected=new AtomicInteger(),bodies=new AtomicInteger(),released=new AtomicInteger(),errors=new AtomicInteger();
        java.util.function.Consumer<AgenticScope> invoke=scope->{
            InvocationTasks owner=((DefaultAgenticScope)scope).invocationTasks();
            try{reference.get().run("same","nested");fail("recursive root was accepted");}catch(IllegalStateException expected){rejected.incrementAndGet();}
            assertSame(owner,((DefaultAgenticScope)scope).invocationTasks());assertFalse(owner.finished());assertEquals("first",scope.readState("value"));
        };
        Object step=AgenticServices.nonAiAgentBuilder(scope->{bodies.incrementAndGet();if(worker&&scope.readState("value").equals("first"))invoke.accept(scope);return scope.readState("value");}).name("echo").async(worker).outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").beforeCall(scope->{
            if(scope.readState("value").equals("first")){((DefaultAgenticScope)scope).invocationTasks().register(released::incrementAndGet);if(!worker)invoke.accept(scope);}
        }).listener(new AgentListener(){@Override public void onAgentInvocationError(AgentInvocationError e){errors.incrementAndGet();}}).build();reference.set(work);
        assertEquals("first",work.run("same","first"));assertEquals(1,rejected.get());assertEquals(1,bodies.get());assertEquals(0,released.get());assertEquals(0,errors.get());
        assertEquals("next",work.run("same","next"));assertEquals(2,bodies.get());
    }
    static void capturedContext(boolean cancelOld,boolean failNew,boolean otherFrame){
        AtomicReference<InvocationTasks> old=new AtomicReference<>(),fresh=new AtomicReference<>();
        AtomicReference<dev.langchain4j.internal.CancellationContext.Registrar> registrar=new AtomicReference<>();
        DefaultAgenticScope other=DefaultAgenticScope.ephemeralAgenticScope();InvocationTasks otherOwner=other.invocationTasks();
        AtomicInteger releases=new AtomicInteger(),oldReleases=new AtomicInteger();
        IllegalStateException primary=new IllegalStateException("independent-new-root-failure");
        Object step=AgenticServices.nonAiAgentBuilder(scope->{
            if(scope.readState("value").equals("first"))registrar.set(dev.langchain4j.internal.CancellationContext.current());
            else if(failNew)throw primary;
            return scope.readState("value");
        }).name("echo").outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").beforeCall(scope->{
            InvocationTasks owner=((DefaultAgenticScope)scope).invocationTasks();
            if(scope.readState("value").equals("first"))old.set(owner);
            else{fresh.set(owner);assertNotSame("new root inherited old captured owner",old.get(),owner);assertFalse(owner.finished());assertSame(otherOwner,InvocationTasks.bound(other));if(otherFrame){assertTrue(DeferredScopeContext.handles(other,"parent-slot"));assertEquals("parent-value",other.readState("parent-slot"));}owner.register(releases::incrementAndGet);}
        }).build();
        assertEquals("first",work.run("same","first"));assertNotNull(registrar.get());
        DefaultAgenticScope scopeForFrame=(DefaultAgenticScope)work.getAgenticScope("same");
        other.writeState("parent-slot","parent-value");
        DeferredScopeContext.Frame frame=new DeferredScopeContext.Frame(otherFrame?other:scopeForFrame,otherFrame?"parent-slot":"answer");
        otherOwner.call(()->frame.call(()->{
            if(cancelOld)old.get().cancel(new IllegalStateException("old root cancelled after return"));
            return dev.langchain4j.internal.CancellationContext.call(registrar.get(),()->{
            AgenticScope scope=work.getAgenticScope("same");assertSame(old.get(),((DefaultAgenticScope)scope).invocationTasks());
            if(failNew){try{work.run("same","second");fail("new root must fail");}catch(RuntimeException expected){assertSame(primary,original(expected));}}
            else assertEquals("second",work.run("same","second"));
            assertEquals("previous deferred output frame was modified by independent root",otherFrame?"parent-value":"first",DeferredScopeContext.read());
            assertSame("previous captured scope binding was not restored",old.get(),((DefaultAgenticScope)scope).invocationTasks());
            assertSame(otherOwner,InvocationTasks.bound(other));assertSame(registrar.get(),dev.langchain4j.internal.CancellationContext.current());
            if(cancelOld){try{scope.writeState("stale","rejected");fail("old cancellation guard lost");}catch(CancellationException expected){}}
            return null;
            });
        }));
        assertEquals("root output was trapped in previous deferred frame",failNew?"first":"second",scopeForFrame.state().get("answer"));
        assertNull(InvocationTasks.bound((DefaultAgenticScope)work.getAgenticScope("same")));assertNull(InvocationTasks.bound(other));assertNull(dev.langchain4j.internal.CancellationContext.current());
        assertTrue(fresh.get().finished());assertFalse(work.getAgenticScope("same").hasState("stale"));assertEquals(failNew?1:0,releases.get());
        assertFalse(otherOwner.finished());old.get().register(oldReleases::incrementAndGet);assertEquals(cancelOld?1:0,oldReleases.get());
    }
    static void nested(){
        Object step=AgenticServices.nonAiAgentBuilder(scope->scope.readState("value")).name("echo").outputKey("answer").build();
        Work inner=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").build();
        Work outer=AgenticServices.sequenceBuilder(Work.class).subAgents(inner).outputKey("answer").build();
        assertEquals("nested-ok",outer.run("same","nested-ok"));assertEquals("next",outer.run("same","next"));
    }
    static void pause(){
        AtomicInteger asks=new AtomicInteger();Object human=AgenticServices.humanInTheLoopBuilder().outputKey("answer").responseProvider(scope->{asks.incrementAndGet();return new SuspendedResponse<String>("approval");}).build();
        Pausing work=AgenticServices.sequenceBuilder(Pausing.class).subAgents(human).outputKey("answer").build();
        ResultWithAgenticScope<String> paused=work.run("same");assertTrue(paused.suspended());
        ResultWithAgenticScope<String> done=paused.completePendingResponse("approved");assertFalse(done.suspended());assertEquals("approved",done.result());assertEquals(1,asks.get());
    }
}
