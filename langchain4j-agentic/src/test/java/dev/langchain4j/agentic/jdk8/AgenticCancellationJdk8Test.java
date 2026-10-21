package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.internal.PendingResponse;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticCancellationJdk8Test {
    public interface Work extends AgenticScopeAccess {@Agent String run(@MemoryId String id);}
    @Test(timeout=15000) public void failedRootInterruptsOwnedAsyncWorker() throws Exception {child("interrupt");}
    @Test(timeout=15000) public void overwrittenOutputDoesNotLoseWorkerOwnership() throws Exception {child("overwritten");}
    @Test(timeout=15000) public void cancelledWorkerCannotMutateScopeAfterFailure() throws Exception {child("stubborn");}
    @Test(timeout=15000) public void cancellingOneMemoryIdDoesNotCancelAnother() throws Exception {child("isolation");}
    @Test(timeout=15000) public void ownedWorkerWaitingOnExternalPendingResponseIsInterrupted() throws Exception {child("pending");}
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"agentic-cancel-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticCancellationJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("child timed out: "+mode,p.waitFor(9,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally {if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stopped=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        CountDownLatch enteredOther=new CountDownLatch(1),releaseOther=new CountDownLatch(1),doneOther=new CountDownLatch(1);
        AtomicInteger rejected=new AtomicInteger(),errors=new AtomicInteger(),otherInterrupts=new AtomicInteger();AtomicReference<Throwable> otherFailure=new AtomicReference<>();AtomicReference<String> otherResult=new AtomicReference<>();
        PendingResponse<String> answer=new PendingResponse<>("approval");
        Object producer=AgenticServices.nonAiAgentBuilder(scope->{
            String id=scope.memoryId().toString();
            if(id.equals("other")){enteredOther.countDown();try {assertTrue(releaseOther.await(5,TimeUnit.SECONDS));return "other";}catch(InterruptedException e){otherInterrupts.incrementAndGet();throw e;}}
            entered.countDown();try {
                if(mode.equals("pending"))return scope.readState("approval");
                if(mode.equals("stubborn")){
                    boolean released=false;while(!released){try {released=release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted.countDown();}}
                    List<Runnable> writes=Arrays.asList(()->scope.writeState("late","stale"),()->scope.writeStateIfAbsent("absent","stale"),()->scope.writeStates(Collections.singletonMap("bulk","stale")));
                    for(Runnable write:writes){try {write.run();}catch(CancellationException expected){rejected.incrementAndGet();}}
                    return "ignored";
                }
                assertTrue(release.await(4,TimeUnit.SECONDS));return "released";
            }catch(InterruptedException e){interrupted.countDown();throw e;}finally {stopped.countDown();}
        }).name("producer").async(true).outputKey("x").build();
        Object finish=AgenticServices.nonAiAgentBuilder(scope->{
            if(scope.memoryId().equals("other"))return scope.readState("x");
            assertTrue(entered.await(3,TimeUnit.SECONDS));if(mode.equals("overwritten"))scope.writeState("x","replacement");throw new IllegalStateException("primary failure");
        }).name("finish").outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(producer,finish).beforeCall(scope->{if(mode.equals("pending"))scope.writeStateIfAbsent("approval",answer);}).outputKey("answer")
                .errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
        Thread other=new Thread(()->{try {otherResult.set(work.run("other"));}catch(Throwable e){otherFailure.set(e);}finally {doneOther.countDown();}},"other-scope");
        try {
            if(mode.equals("isolation")){other.start();assertTrue(enteredOther.await(3,TimeUnit.SECONDS));}
            try {work.run("failed");fail("must fail");}catch(AgentInvocationException e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();assertEquals("primary failure",cause.getMessage());}
            AgenticScope scope=work.getAgenticScope("failed");assertNotNull(scope);
            if(mode.equals("stubborn")){
                assertTrue(interrupted.await(2,TimeUnit.SECONDS));scope.writeState("late","fresh");release.countDown();assertTrue(stopped.await(2,TimeUnit.SECONDS));assertEquals(3,rejected.get());assertEquals("fresh",scope.readState("late"));assertFalse(scope.hasState("absent"));assertFalse(scope.hasState("bulk"));
            }else {assertTrue("owned worker still running",stopped.await(2,TimeUnit.SECONDS));if(!mode.equals("pending"))assertTrue(interrupted.await(1,TimeUnit.SECONDS));}
            if(mode.equals("overwritten"))assertEquals("replacement",scope.readState("x"));
            if(mode.equals("pending")){assertFalse(answer.isDone());assertEquals(Collections.singleton("approval"),scope.pendingResponseIds());}
            if(mode.equals("isolation")){assertEquals(1,doneOther.getCount());assertEquals(0,otherInterrupts.get());releaseOther.countDown();assertTrue(doneOther.await(3,TimeUnit.SECONDS));assertNull(otherFailure.get());assertEquals("other",otherResult.get());}
            assertEquals("cancellation must not enter agent recovery",1,errors.get());
        } finally {release.countDown();releaseOther.countDown();answer.complete("fixture cleanup");if(other.isAlive())other.join(2000);assertFalse(other.isAlive());}
    }
}
