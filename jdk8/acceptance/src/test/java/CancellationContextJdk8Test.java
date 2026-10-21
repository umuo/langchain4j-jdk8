import dev.langchain4j.internal.CancellationContext;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CancellationContextJdk8Test {
    @Test public void absentOwnerDoesNotCancelUnrelatedResources() {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        assertNull(CancellationContext.current());
        CancellationContext.register(() -> fail("unowned resource cancelled")).close();
    }
    @Test public void nestedOwnersAndExceptionsRestorePreviousContext() {
        AtomicInteger outerClosed=new AtomicInteger(),innerClosed=new AtomicInteger();
        CancellationContext.Registrar outer=cancel -> outerClosed::incrementAndGet;
        CancellationContext.Registrar inner=cancel -> innerClosed::incrementAndGet;
        CancellationContext.run(outer,()->{
            try {CancellationContext.run(inner,()->{assertSame(inner,CancellationContext.current());CancellationContext.register(()->{}).close();throw new IllegalStateException("fixture");});fail();}
            catch(IllegalStateException expected){assertEquals("fixture",expected.getMessage());}
            assertSame(outer,CancellationContext.current());CancellationContext.register(()->{}).close();
        });
        assertNull(CancellationContext.current());assertEquals(1,outerClosed.get());assertEquals(1,innerClosed.get());
    }
    @Test public void executorCallbacksRequireCaptureAndDoNotLeakOwnership() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        CancellationContext.Registrar owner=cancel -> () -> {};
        try {
            Future<?> first=CancellationContext.call(owner,()->executor.submit(()->assertNull(CancellationContext.current())));first.get(2,TimeUnit.SECONDS);
            executor.submit(()->{CancellationContext.run(owner,()->assertSame(owner,CancellationContext.current()));assertNull(CancellationContext.current());}).get(2,TimeUnit.SECONDS);
            executor.submit(()->assertNull(CancellationContext.current())).get(2,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }
}
