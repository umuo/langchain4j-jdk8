import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.invocation.*;
import dev.langchain4j.spi.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class OwnedTaskJdk8Test {
    static final class Owner implements CancellationContext.Registrar {
        final Set<Runnable> actions=ConcurrentHashMap.newKeySet();volatile boolean cancelled;
        public CancellationContext.Registration register(Runnable action){actions.add(action);if(cancelled)action.run();return ()->actions.remove(action);}
        void cancel(){cancelled=true;for(Runnable action:new ArrayList<>(actions))action.run();}
    }
    final Owner owner=new Owner();ExecutorService executor;ExecutorProvider previous;
    @Before public void prepare(){assertTrue(System.getProperty("java.version").startsWith("1.8."));executor=Executors.newSingleThreadExecutor();previous=ExecutorProvider.get();ExecutorProvider.set(new ExecutorProvider(){public java.util.concurrent.Executor executor(){return null;}});}
    @After public void close()throws Exception{ExecutorProvider.set(previous);executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    InvocationContext context(){return CancellationContext.call(owner,()->CapturedContextSupport.captureInto(InvocationContext.builder()).build());}
    void clean()throws Exception{executor.submit(()->assertNull(CancellationContext.current())).get(2,TimeUnit.SECONDS);assertTrue(owner.actions.isEmpty());}
    @Test(timeout=5000) public void rootCancelsRunningSupplierAndReleasesOwnership()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),stop=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
        try{
            CompletableFuture<String> future=CapturedContextSupport.supplyAsync(()->{entered.countDown();try{release.await();return "late";}catch(InterruptedException e){interrupted.set(true);return "interrupted";}finally{stop.countDown();}},executor,context());
            assertTrue(entered.await(2,TimeUnit.SECONDS));owner.cancel();assertTrue(future.isCancelled());assertTrue(stop.await(2,TimeUnit.SECONDS));assertTrue(interrupted.get());clean();assertFalse(executor.isShutdown());
        }finally{release.countDown();}
    }
    @Test(timeout=5000) public void cancelledQueuedFutureTerminatesBeforeExecutorIsReleased()throws Exception{
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        executor.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{
            assertTrue(blocked.await(2,TimeUnit.SECONDS));CompletableFuture<Integer> future=CapturedContextSupport.supplyAsync(calls::incrementAndGet,executor,context());owner.cancel();
            assertTrue(future.isCancelled());try{future.get(100,TimeUnit.MILLISECONDS);fail();}catch(CancellationException expected){}
            assertEquals(0,calls.get());assertTrue(owner.actions.isEmpty());release.countDown();clean();assertEquals(0,calls.get());
        }finally{release.countDown();}
    }
    @Test(timeout=5000) public void cancelFalseKeepsRunningTaskOwnedUntilRootInterrupts()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stop=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
        try{
            CompletableFuture<String> future=CapturedContextSupport.supplyAsync(()->{entered.countDown();try{release.await();return "late";}catch(InterruptedException e){interrupted.set(true);return "interrupted";}finally{stop.countDown();}},executor,context());
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(future.cancel(false));assertFalse(stop.await(100,TimeUnit.MILLISECONDS));assertEquals(1,owner.actions.size());
            owner.cancel();assertTrue(stop.await(2,TimeUnit.SECONDS));assertTrue(interrupted.get());clean();
        }finally{release.countDown();}
    }
    @Test public void rejectedSubmissionPreservesRejectionAndUnregisters(){
        RejectedExecutionException primary=new RejectedExecutionException("fixture");AtomicInteger calls=new AtomicInteger();
        try{CapturedContextSupport.supplyAsync(calls::incrementAndGet,task->{throw primary;},context());fail();}catch(RejectedExecutionException expected){assertSame(primary,expected);}
        assertEquals(0,calls.get());assertTrue(owner.actions.isEmpty());
    }
    @Test public void cancelledOwnerSkipsSubmission(){
        InvocationContext context=context();owner.cancel();AtomicInteger submits=new AtomicInteger(),calls=new AtomicInteger();
        CompletableFuture<Integer> future=CapturedContextSupport.supplyAsync(calls::incrementAndGet,task->{submits.incrementAndGet();task.run();},context);
        assertTrue(future.isCancelled());assertEquals(0,submits.get());assertEquals(0,calls.get());assertTrue(owner.actions.isEmpty());
    }
    @Test(timeout=5000) public void sameCapturedExecutorWrapsHostOnceAndRestoresOnFailure()throws Exception{
        ThreadLocal<String> host=new ThreadLocal<>();AtomicInteger wraps=new AtomicInteger();
        ExecutorProvider.set(new ExecutorProvider(){public java.util.concurrent.Executor executor(){return null;}public CapturedContext captureContext(){String captured=host.get();return task->{wraps.incrementAndGet();return ()->{String old=host.get();host.set(captured);try{task.run();}finally{if(old==null)host.remove();else host.set(old);}};};}});
        host.set("caller");InvocationContext context=context();host.set("submitter");RuntimeException primary=new IllegalStateException("supplier");
        java.util.concurrent.Executor wrapped=CapturedContextSupport.restoringIn(executor,context);
        CompletableFuture<String> future=CapturedContextSupport.supplyAsync(()->{assertEquals("caller",host.get());assertSame(owner,CancellationContext.current());throw primary;},wrapped,context);
        try{future.get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertSame(primary,expected.getCause());}
        clean();assertEquals(1,wraps.get());assertEquals("submitter",host.get());executor.submit(()->assertNull(host.get())).get(2,TimeUnit.SECONDS);host.remove();
    }
    @Test(timeout=5000) public void providerCallbackFailureCompletesFutureWithoutRunningSupplier()throws Exception{
        RuntimeException primary=new IllegalStateException("context before supplier");AtomicInteger calls=new AtomicInteger();
        ExecutorProvider.set(new ExecutorProvider(){public java.util.concurrent.Executor executor(){return null;}public CapturedContext captureContext(){return task->()->{throw primary;};}});
        CompletableFuture<Integer> future=CapturedContextSupport.supplyAsync(calls::incrementAndGet,executor,context());
        try{future.get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertSame(primary,expected.getCause());}
        assertEquals(0,calls.get());clean();
    }
    @Test(timeout=5000) public void providerCleanupErrorStillReachesExecutorAfterResultPublished()throws Exception{
        executor.shutdown();AssertionError primary=new AssertionError("context cleanup");AtomicReference<Throwable> observed=new AtomicReference<>();CountDownLatch reported=new CountDownLatch(1);
        executor=Executors.newSingleThreadExecutor(task->{Thread worker=new Thread(task,"owned-task-context-fixture");worker.setUncaughtExceptionHandler((thread,error)->{observed.set(error);reported.countDown();});return worker;});
        ExecutorProvider.set(new ExecutorProvider(){public java.util.concurrent.Executor executor(){return null;}public CapturedContext captureContext(){return task->()->{try{task.run();}finally{throw primary;}};}});
        CompletableFuture<String> future=CapturedContextSupport.supplyAsync(()->"published",executor,context());
        assertEquals("published",future.get(2,TimeUnit.SECONDS));assertTrue("context cleanup error swallowed",reported.await(2,TimeUnit.SECONDS));assertSame(primary,observed.get());clean();
    }

    @Test(timeout=5000) public void rootReleasesQueuedTaskEvenIfCallerAlreadyCompletedItsFuture()throws Exception{
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        executor.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{
            assertTrue(blocked.await(2,TimeUnit.SECONDS));CompletableFuture<Integer> future=CapturedContextSupport.supplyAsync(calls::incrementAndGet,executor,context());
            assertTrue(future.complete(99));owner.cancel();assertEquals(Integer.valueOf(99),future.get(100,TimeUnit.MILLISECONDS));assertTrue("completed queued task registration retained",owner.actions.isEmpty());
            release.countDown();clean();assertEquals(0,calls.get());
        }finally{release.countDown();}
    }

}
