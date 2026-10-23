package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.AgenticScopeOwner;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticInitializationJdk8Test {
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    public interface Ephemeral { @Agent String run(); }
    @Test(timeout=15000) public void concurrentFirstLoadCreatesAndInitializesOnce()throws Exception{child("load");}
    @Test(timeout=15000) public void initialSaveDoesNotPublishHalfInitializedScope()throws Exception{child("save");}
    @Test(timeout=15000) public void creationListenerIsAVisibilityBarrier()throws Exception{child("init");}
    @Test(timeout=15000) public void failedInitializerIsNotSilentlyReusedByWaiter()throws Exception{child("fail-init");}
    @Test(timeout=15000) public void failedInitialSaveIsNotRetriedByWaiter()throws Exception{child("fail-save");}
    @Test(timeout=15000) public void falseInitialSaveIsNotTreatedAsSuccess()throws Exception{child("false-save");}
    @Test(timeout=15000) public void concurrentStoredScopeLoadsUseOneObject()throws Exception{child("loaded");}
    @Test(timeout=15000) public void interruptedKeyWaiterDoesNotCancelCreator()throws Exception{child("interrupt");}
    @Test(timeout=15000) public void ephemeralInitializationBlocksScopeReadersOnlyForThatKey()throws Exception{child("ephemeral");}
    @Test(timeout=15000) public void reentrantRootCannotRunInsideItsOwnInitialization()throws Exception{child("reentrant");}
    @Test(timeout=15000) public void falseFinalSaveFailsAndAttemptsFailureSnapshot()throws Exception{child("false-update");}
    static void child(String mode)throws Exception{
        Path log=Files.createTempFile(Paths.get("target"),"initialization-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticInitializationJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue("child timed out: "+mode,p.waitFor(10,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    static class Call {
        final AtomicReference<Object> result=new AtomicReference<>();final AtomicReference<Throwable> failure=new AtomicReference<>();final CountDownLatch done=new CountDownLatch(1),started=new CountDownLatch(1);
        final Thread thread;
        Call(Callable<?> action){thread=new Thread(()->{started.countDown();try{result.set(action.call());}catch(Throwable e){failure.set(e);}finally{done.countDown();}},"initialization-caller");}
        void start()throws Exception{thread.start();assertTrue(started.await(1,TimeUnit.SECONDS));}
        void await()throws Exception{assertTrue("call did not finish",done.await(3,TimeUnit.SECONDS));}
        void ok()throws Exception{await();if(failure.get()!=null)throw new AssertionError("unexpected failure",failure.get());}
        void stop()throws Exception{thread.join(2000);assertFalse(thread.isAlive());}
    }
    public static void main(String[] args)throws Exception{
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        if(mode.equals("loaded")){loaded();return;}if(mode.equals("interrupt")){interrupt();return;}if(mode.equals("ephemeral")){ephemeral();return;}if(mode.equals("reentrant")){reentrant();return;}if(mode.equals("false-update")){falseUpdate();return;}
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger loads=new AtomicInteger(),saves=new AtomicInteger(),created=new AtomicInteger(),body=new AtomicInteger();
        AtomicReference<AgenticScope> same=new AtomicReference<>();List<AgenticScope> observed=new CopyOnWriteArrayList<>();AtomicBoolean failInit=new AtomicBoolean(mode.equals("fail-init"));
        Throwable primary=mode.equals("fail-init")?new AssertionError("init-primary"):new IllegalStateException("save-primary");
        boolean storeMode=mode.equals("load")||mode.contains("save");
        if(storeMode)AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore(){
            @Override public Optional<DefaultAgenticScope> load(AgenticScopeKey key){if(key.memoryId().equals("same")){loads.incrementAndGet();if(mode.equals("load")){entered.countDown();awaitRelease(release);}}return Optional.empty();}
            @Override public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){
                if(key.memoryId().equals("same")){saves.incrementAndGet();if(!mode.equals("load")){entered.countDown();awaitRelease(release);if(mode.equals("fail-save"))throw (RuntimeException)primary;if(mode.equals("false-save"))return false;}}
                return true;
            }
        });
        Object step=AgenticServices.nonAiAgentBuilder(scope->{body.incrementAndGet();assertEquals("body saw partial scope",Boolean.TRUE,scope.readState("initialized"));if(scope.memoryId().equals("same"))observed.add(scope);return "ok";}).name("body").outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").listener(new AgentListener(){
            @Override public void afterAgenticScopeCreated(AgenticScope scope){
                created.incrementAndGet();if(scope.memoryId().equals("same")){same.set(scope);scope.writeState("partial","seen");if(!storeMode){entered.countDown();awaitRelease(release);}if(failInit.get())throw (Error)primary;}
                scope.writeState("initialized",true);
            }
        }).build();
        Call first=new Call(()->work.run("same")),second=new Call(()->work.run("same")),other=new Call(()->work.run("other"));
        Call inspector=new Call(()->((AgenticScopeOwner)work).registry().get("same"));
        try{
            first.start();assertTrue(entered.await(3,TimeUnit.SECONDS));second.start();
            assertFalse("waiter observed in-progress scope",second.done.await(150,TimeUnit.MILLISECONDS));assertEquals(0,body.get());
            if(mode.equals("load"))assertEquals("duplicate initial load",1,loads.get());
            inspector.start();assertFalse("get exposed in-progress scope",inspector.done.await(150,TimeUnit.MILLISECONDS));
            other.start();other.ok();assertEquals("ok",other.result.get());assertEquals(1,body.get());
            release.countDown();first.await();second.await();inspector.ok();
            if(mode.startsWith("fail")||mode.equals("false-save")){
                assertNotNull(first.failure.get());assertSame("waiting root retried failed initialization",first.failure.get(),second.failure.get());
                if(!mode.equals("false-save"))assertSame(primary,first.failure.get());else assertTrue(first.failure.get() instanceof IllegalStateException);
                assertEquals("failed key entered body",1,body.get());
                if(mode.equals("fail-init")){assertSame(same.get(),inspector.result.get());failInit.set(false);try{work.run("same");fail("failed initialization silently reused");}catch(Error expected){assertSame(primary,expected);}assertTrue(work.evictAgenticScope("same"));assertEquals("ok",work.run("same"));}
                else assertNull(inspector.result.get());
            }else{
                first.ok();second.ok();assertEquals("ok",first.result.get());assertEquals("ok",second.result.get());assertEquals(3,body.get());assertEquals(2,created.get());assertEquals(2,observed.size());assertSame(observed.get(0),observed.get(1));assertSame(observed.get(0),inspector.result.get());
                if(mode.equals("load"))assertEquals(1,loads.get());
            }
        }finally{release.countDown();first.stop();if(second.thread.getState()!=Thread.State.NEW)second.stop();if(other.thread.getState()!=Thread.State.NEW)other.stop();if(inspector.thread.getState()!=Thread.State.NEW)inspector.stop();}
    }
    static void awaitRelease(CountDownLatch release){try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void loaded()throws Exception{
        DefaultAgenticScope seed=new AgenticScopeRegistry("seed").create("same");AgenticRecoveryJdk8Test.JsonStore stored=new AgenticRecoveryJdk8Test.JsonStore();stored.save(new AgenticScopeKey("fixture","same"),seed);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger loads=new AtomicInteger();
        AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore(){@Override public Optional<DefaultAgenticScope> load(AgenticScopeKey key){if(key.memoryId().equals("same")){loads.incrementAndGet();entered.countDown();awaitRelease(release);return stored.load(key);}return Optional.empty();}});
        AgenticScopeRegistry registry=new AgenticScopeRegistry("fixture");Call a=new Call(()->registry.get("same")),b=new Call(()->registry.get("same")),other=new Call(()->registry.get("other"));
        try{a.start();assertTrue(entered.await(3,TimeUnit.SECONDS));b.start();assertFalse(b.done.await(150,TimeUnit.MILLISECONDS));assertEquals("duplicate persisted load",1,loads.get());other.start();other.ok();release.countDown();a.ok();b.ok();assertSame(a.result.get(),b.result.get());assertEquals(1,loads.get());}
        finally{release.countDown();a.stop();if(b.thread.getState()!=Thread.State.NEW)b.stop();if(other.thread.getState()!=Thread.State.NEW)other.stop();}
    }
    static void interrupt()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
        AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore(){@Override public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){if(key.memoryId().equals("same")){entered.countDown();awaitRelease(release);}return true;}@Override public Optional<DefaultAgenticScope> load(AgenticScopeKey key){return Optional.empty();}});
        AgenticScopeRegistry registry=new AgenticScopeRegistry("fixture");Call a=new Call(()->registry.create("same")),b=new Call(()->{try{return registry.get("same");}finally{interrupted.set(Thread.currentThread().isInterrupted());}});
        try{a.start();assertTrue(entered.await(3,TimeUnit.SECONDS));b.start();assertFalse("get ignored initialization",b.done.await(150,TimeUnit.MILLISECONDS));b.thread.interrupt();b.await();assertTrue(b.failure.get() instanceof CancellationException);assertTrue(interrupted.get());assertEquals(1,a.done.getCount());assertNull(registry.get("other"));release.countDown();a.ok();assertSame(a.result.get(),registry.get("same"));}
        finally{release.countDown();a.stop();if(b.thread.getState()!=Thread.State.NEW)b.stop();}
    }
    static void ephemeral()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<AgenticScope> first=new AtomicReference<>();AtomicInteger bodies=new AtomicInteger();
        Object step=AgenticServices.nonAiAgentBuilder(scope->{assertEquals(Boolean.TRUE,scope.readState("initialized"));bodies.incrementAndGet();return "ok";}).name("body").outputKey("answer").build();
        Ephemeral work=AgenticServices.sequenceBuilder(Ephemeral.class).subAgents(step).outputKey("answer").listener(new AgentListener(){@Override public void afterAgenticScopeCreated(AgenticScope scope){if(first.compareAndSet(null,scope)){entered.countDown();awaitRelease(release);}scope.writeState("initialized",true);}}).build();
        Call a=new Call(work::run),reader=new Call(()->((AgenticScopeOwner)work).registry().get(first.get().memoryId())),other=new Call(work::run);
        try{a.start();assertTrue(entered.await(3,TimeUnit.SECONDS));reader.start();assertFalse("ephemeral scope exposed before listener finished",reader.done.await(150,TimeUnit.MILLISECONDS));other.start();other.ok();assertEquals(1,bodies.get());release.countDown();a.ok();reader.ok();assertEquals(2,bodies.get());if(reader.result.get()!=null){assertSame(first.get(),reader.result.get());assertEquals(Boolean.TRUE,((AgenticScope)reader.result.get()).readState("initialized"));}}
        finally{release.countDown();a.stop();if(reader.thread.getState()!=Thread.State.NEW)reader.stop();if(other.thread.getState()!=Thread.State.NEW)other.stop();}
    }
    static void reentrant(){
        AtomicReference<Work> reference=new AtomicReference<>();AtomicInteger bodies=new AtomicInteger();AtomicReference<Throwable> nested=new AtomicReference<>();
        Object step=AgenticServices.nonAiAgentBuilder(scope->{bodies.incrementAndGet();assertEquals(Boolean.TRUE,scope.readState("initialized"));return "ok";}).name("body").outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").listener(new AgentListener(){@Override public void afterAgenticScopeCreated(AgenticScope scope){assertSame(scope,reference.get().getAgenticScope("same"));try{reference.get().run("same");fail("recursive initialization ran body");}catch(IllegalStateException expected){nested.set(expected);}scope.writeState("initialized",true);}}).build();reference.set(work);
        assertEquals("ok",work.run("same"));assertNotNull(nested.get());assertEquals(1,bodies.get());
    }
    static void falseUpdate(){
        AtomicInteger saves=new AtomicInteger(),body=new AtomicInteger();AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore(){@Override public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){return saves.incrementAndGet()==1;}});
        Object step=AgenticServices.nonAiAgentBuilder(scope->{body.incrementAndGet();return "ok";}).name("body").outputKey("answer").build();Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").build();
        try{work.run("same");fail("false final save accepted");}catch(IllegalStateException expected){assertEquals(1,expected.getSuppressed().length);}assertEquals(1,body.get());assertEquals(3,saves.get());
    }
}
