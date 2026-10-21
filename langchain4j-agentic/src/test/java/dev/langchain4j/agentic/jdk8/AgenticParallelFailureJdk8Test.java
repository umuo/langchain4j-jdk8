package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticParallelFailureJdk8Test {
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    @Test(timeout=15000) public void defaultParallelDispatchFailsFastAndInterruptsSibling() throws Exception { child("default"); }
    @Test(timeout=15000) public void customExecutorDispatchFailsFastAndInterruptsSibling() throws Exception { child("custom"); }
    @Test(timeout=15000) public void rejectedDispatchCancelsAlreadySubmittedSibling() throws Exception { child("rejected"); }
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"parallel-failure-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticParallelFailureJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("child timed out: "+mode,p.waitFor(9,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally {if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stopped=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        AtomicInteger errors=new AtomicInteger(),submissions=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        Object slow=AgenticServices.nonAiAgentBuilder(scope->{entered.countDown();try {assertTrue(release.await(6,TimeUnit.SECONDS));return "late";}catch(InterruptedException e){interrupted.countDown();throw e;}finally {stopped.countDown();}}).name("slow").outputKey("slow").build();
        Object bad=AgenticServices.nonAiAgentBuilder(scope->{assertTrue(entered.await(3,TimeUnit.SECONDS));throw new IllegalStateException("primary failure");}).name("bad").outputKey("bad").build();
        Executor custom=task->{if(mode.equals("rejected")&&submissions.incrementAndGet()>1){try {assertTrue(entered.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}throw new RejectedExecutionException("dispatch rejected");}pool.execute(task);};
        try {
            Work work=AgenticServices.parallelBuilder(Work.class).subAgents(slow,bad).executor(mode.equals("default")?null:custom).outputKey("bad").errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
            long started=System.nanoTime();
            try {work.run("failed");fail("must fail");}catch(RuntimeException e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();assertEquals(mode.equals("rejected")?"dispatch rejected":"primary failure",cause.getMessage());}
            assertTrue("root waited for slow sibling",System.nanoTime()-started<TimeUnit.SECONDS.toNanos(3));
            assertTrue("sibling not interrupted",interrupted.await(2,TimeUnit.SECONDS));assertTrue(stopped.await(2,TimeUnit.SECONDS));
            assertFalse(work.getAgenticScope("failed").hasState("slow"));assertEquals(mode.equals("rejected")?0:1,errors.get());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
}
