package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.agent.*;
import dev.langchain4j.agentic.internal.*;
import dev.langchain4j.agentic.observability.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticSuccessDrainJdk8Test {
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    @Test(timeout=15000) public void overwrittenSuccessWaitsBeforeRootCompletion() throws Exception {child("success");}
    @Test(timeout=15000) public void overwrittenLateFailureFailsRootBeforeSuccessNotification() throws Exception {child("late-failure");}
    @Test(timeout=15000) public void overwrittenAlreadyFailedTaskStillFailsRoot() throws Exception {child("early-failure");}
    @Test(timeout=15000) public void overwrittenSiblingFailureCancelsPendingComputation() throws Exception {child("fail-fast");}
    @Test(timeout=15000) public void nextRootDoesNotReconsumePreviousRootFailure() throws Exception {child("reuse");}
    @Test(timeout=15000) public void successfulDrainPreservesExternalMarkerOwnership() throws Exception {child("marker");}
    @Test(timeout=15000) public void recoveredAsyncFailureIsNotRethrownByDrain() throws Exception {child("recovered");}
    static void child(String mode)throws Exception{
        Path log=Files.createTempFile(Paths.get("target"),"success-drain-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticSuccessDrainJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue("child timed out: "+mode,p.waitFor(10,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args)throws Exception{
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stopped=new CountDownLatch(1),finish=new CountDownLatch(1),returned=new CountDownLatch(1),recoverySeen=new CountDownLatch(1);
        AtomicBoolean failing=new AtomicBoolean(!mode.equals("success")&&!mode.equals("marker"));
        AtomicInteger rootSuccess=new AtomicInteger(),rootErrors=new AtomicInteger(),recovery=new AtomicInteger(),interrupts=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<String> result=new AtomicReference<>();
        PendingResponse<String> marker=new PendingResponse<>("external");
        Object producer=AgenticServices.nonAiAgentBuilder(scope->{entered.countDown();try{
            if(!mode.equals("early-failure")&&!mode.equals("reuse")&&!mode.equals("marker")&&!mode.equals("recovered"))assertTrue(release.await(5,TimeUnit.SECONDS));
            if(failing.get()&&!mode.equals("fail-fast"))throw new IllegalStateException("background failure");
            return mode.equals("marker")?marker:"background";
        }catch(InterruptedException error){interrupts.incrementAndGet();throw error;}finally{stopped.countDown();}}).name("producer").async(true).outputKey("x").build();
        Object bad=AgenticServices.nonAiAgentBuilder(scope->{assertTrue(entered.await(3,TimeUnit.SECONDS));throw new IllegalStateException("sibling failure");}).name("bad").async(true).outputKey("y").build();
        Object complete=AgenticServices.nonAiAgentBuilder(scope->{assertTrue(entered.await(3,TimeUnit.SECONDS));
            if(mode.equals("early-failure")||mode.equals("recovered")||mode.equals("reuse")&&failing.get())assertTrue(recoverySeen.await(3,TimeUnit.SECONDS));
            scope.writeState("x","replacement");scope.writeState("y","replacement");finish.countDown();return "done";
        }).name("finish").outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(mode.equals("fail-fast")?new Object[]{producer,bad,complete}:new Object[]{producer,complete}).outputKey("answer")
                .errorHandler(error->{recovery.incrementAndGet();recoverySeen.countDown();return mode.equals("recovered")?ErrorRecoveryResult.result("recovered"):ErrorRecoveryResult.throwException();})
                .listener(new AgentListener(){
                    @Override public void afterAgentInvocation(AgentResponse response){if(response.agent().type()==Work.class)rootSuccess.incrementAndGet();}
                    @Override public void onAgentInvocationError(AgentInvocationError error){if(error.agent().type()==Work.class)rootErrors.incrementAndGet();}
                }).build();
        Thread caller=new Thread(()->{try{result.set(work.run("session"));}catch(Throwable error){failure.set(error);}finally{returned.countDown();}},"root-caller");
        try{
            caller.start();assertTrue(finish.await(3,TimeUnit.SECONDS));
            if(mode.equals("success")||mode.equals("late-failure")){
                assertFalse("root returned while overwritten task still running",returned.await(150,TimeUnit.MILLISECONDS));assertEquals("success listener fired before drain",0,rootSuccess.get());release.countDown();
            }
            assertTrue("root did not settle",returned.await(3,TimeUnit.SECONDS));
            if(failing.get()&&!mode.equals("recovered")){
                assertTrue(String.valueOf(failure.get()),failure.get() instanceof AgentInvocationException);assertNull(result.get());
                Throwable cause=failure.get();while(cause.getCause()!=null)cause=cause.getCause();assertEquals(mode.equals("fail-fast")?"sibling failure":"background failure",cause.getMessage());
                assertEquals(0,rootSuccess.get());assertEquals(1,rootErrors.get());assertEquals(1,recovery.get());
            }else{assertNull(failure.get());assertEquals("done",result.get());assertEquals(1,rootSuccess.get());assertEquals(0,rootErrors.get());assertEquals(mode.equals("recovered")?1:0,recovery.get());}
            assertTrue("owned task still running after root return",stopped.await(2,TimeUnit.SECONDS));
            if(mode.equals("fail-fast"))assertEquals(1,interrupts.get());else assertEquals(0,interrupts.get());
            assertEquals("replacement",work.getAgenticScope("session").readState("x"));
            if(mode.equals("marker"))assertFalse("external marker was cancelled/completed by drain",marker.isDone());
            if(mode.equals("reuse")){
                InvocationTasks old=((DefaultAgenticScope)work.getAgenticScope("session")).invocationTasks();failing.set(false);
                assertEquals("done",work.run("session"));assertNotSame(old,((DefaultAgenticScope)work.getAgenticScope("session")).invocationTasks());assertEquals(1,rootSuccess.get());assertEquals(1,rootErrors.get());assertEquals(1,recovery.get());
            }
        }finally{release.countDown();marker.complete("fixture cleanup");caller.join(2000);assertFalse(caller.isAlive());}
    }
}
