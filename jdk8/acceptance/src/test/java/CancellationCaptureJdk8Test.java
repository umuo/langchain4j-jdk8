import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.invocation.*;
import dev.langchain4j.spi.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class CancellationCaptureJdk8Test {
    final ThreadLocal<String> host=new ThreadLocal<>();
    final CancellationContext.Registrar owner=cancel -> () -> {};
    ExecutorProvider previous;
    ExecutorService executor;
    @Before public void prepare(){assertTrue(System.getProperty("java.version").startsWith("1.8."));previous=ExecutorProvider.get();executor=Executors.newSingleThreadExecutor();ExecutorProvider.set(new ExecutorProvider(){public java.util.concurrent.Executor executor(){return null;}});}
    @After public void close() throws Exception {ExecutorProvider.set(previous);host.remove();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    InvocationContext capture(){return CancellationContext.call(owner,()->CapturedContextSupport.captureInto(InvocationContext.builder()).build());}
    void provider(){ExecutorProvider.set(new ExecutorProvider(){
        public java.util.concurrent.Executor executor(){return executor;}
        public CapturedContext captureContext(){String captured=host.get();return task -> ()->{String old=host.get();host.set(captured);try{task.run();}finally{if(old==null)host.remove();else host.set(old);}};}
    });}
    @Test(timeout=5000) public void invocationCopyCarriesOwnerWithoutHostProvider() throws Exception {
        InvocationContext context=capture().toBuilder().build();
        CompletableFuture<Void> done=new CompletableFuture<>();
        CapturedContextSupport.restoringIn(executor,context).execute(()->{try{assertSame(owner,CancellationContext.current());done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});
        done.get(2,TimeUnit.SECONDS);executor.submit(()->assertNull(CancellationContext.current())).get(2,TimeUnit.SECONDS);
    }
    @Test(timeout=5000) public void combinesHostSnapshotAndOwnerThenRestoresWorker() throws Exception {
        provider();host.set("caller");InvocationContext context=capture();host.set("submitter");
        executor.submit(()->host.set("worker")).get(2,TimeUnit.SECONDS);
        CompletableFuture<Void> done=new CompletableFuture<>();
        CapturedContextSupport.restoringIn(executor,context).execute(()->{try{assertSame(owner,CancellationContext.current());assertEquals("caller",host.get());done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});
        done.get(2,TimeUnit.SECONDS);executor.submit(()->{assertNull(CancellationContext.current());assertEquals("worker",host.get());}).get(2,TimeUnit.SECONDS);assertEquals("submitter",host.get());
    }
    @Test(timeout=5000) public void callbackErrorRestoresHostAndOwnerWithoutReplacingError(){
        provider();host.set("caller");InvocationContext context=capture();host.set("submitter");AssertionError primary=new AssertionError("fixture");
        try {CapturedContextSupport.restoringIn(Runnable::run,context).execute(()->{assertSame(owner,CancellationContext.current());assertEquals("caller",host.get());throw primary;});fail();}
        catch(AssertionError error){assertSame(primary,error);}
        assertNull(CancellationContext.current());assertEquals("submitter",host.get());
    }
    @Test public void noContextRetainsOriginalExecutorAndNullContract(){
        InvocationContext context=CapturedContextSupport.captureInto(InvocationContext.builder()).build();java.util.concurrent.Executor direct=Runnable::run;
        assertSame(direct,CapturedContextSupport.restoringIn(direct,context));assertNull(CapturedContextSupport.restoringIn(null,context));
    }
}
