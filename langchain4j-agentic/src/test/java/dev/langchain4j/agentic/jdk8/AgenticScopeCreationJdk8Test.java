package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.AgenticScopeOwner;
import dev.langchain4j.agentic.internal.InvocationTasks;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.util.Optional;
import java.util.concurrent.atomic.*;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticScopeCreationJdk8Test {
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    public interface Ephemeral { @Agent String run(); }
    @After public void reset() { AgenticScopePersister.setStore(null); }
    @Test(timeout=8000) public void saveRuntimeFailureDropsFailedRegistration() { save(false, false, false); }
    @Test(timeout=8000) public void saveErrorDropsFailedRegistration() { save(true, false, false); }
    @Test(timeout=8000) public void failedReplacementRetainsPriorScope() { save(false, true, false); }
    @Test(timeout=8000) public void failedReplacementErrorRetainsPriorScope() { save(true, true, false); }
    @Test(timeout=8000) public void failedReentrantSaveDoesNotRemoveNewReplacement() { save(false, false, true); }
    @Test(timeout=8000) public void loadRuntimeFailureDoesNotCreateOrRetry() { load(false); }
    @Test(timeout=8000) public void loadErrorDoesNotCreateOrRetry() { load(true); }
    @Test(timeout=8000) public void ephemeralCreationListenerErrorReleasesAndEvicts() { listener("ephemeral"); }
    @Test(timeout=8000) public void registeredCreationListenerErrorReleasesResources() { listener("registered"); }
    @Test(timeout=8000) public void persistentCreationListenerErrorFlushesFailedState() { listener("persistent"); }
    @Test(timeout=8000) public void creationListenerRuntimeFailureKeepsLogAndContinue() { listener("runtime"); }
    @Test(timeout=8000) public void failedCreationFlushPreservesListenerError() { listener("flush"); }

    private static void save(boolean error, boolean previous, boolean reentrant) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        Throwable primary = error ? new AssertionError("save-primary") : new IllegalStateException("save-primary");
        AssertionError cleanup = new AssertionError("cancel-secondary");
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger releases = new AtomicInteger(), priorReleases = new AtomicInteger(), deletes = new AtomicInteger(), saves = new AtomicInteger();
        AtomicReference<DefaultAgenticScope> failed = new AtomicReference<>(), fresh = new AtomicReference<>();
        AtomicReference<AgenticScopeRegistry> registry = new AtomicReference<>();
        AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore() {
            @Override public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) {
                saves.incrementAndGet();
                if (armed.getAndSet(false)) {
                    failed.set(scope);scope.invocationTasks().register(() -> { releases.incrementAndGet(); throw cleanup; });
                    if (reentrant) fresh.set(registry.get().create("same"));
                    throwUnchecked(primary);
                }
                return true;
            }
            @Override public boolean delete(AgenticScopeKey key) { deletes.incrementAndGet(); return true; }
        });
        registry.set(new AgenticScopeRegistry("fixture"));
        DefaultAgenticScope old = previous ? registry.get().create("same") : null;
        if (old != null) old.invocationTasks().register(priorReleases::incrementAndGet);
        armed.set(true);
        try { registry.get().create("same"); fail("expected failure"); }
        catch (RuntimeException | Error actual) { assertSame(primary, actual); }
        assertEquals("failed creation resource leaked", 1, releases.get());
        assertTrue(failed.get().invocationTasks().finished());
        assertTrue(java.util.Arrays.asList(primary.getSuppressed()).contains(cleanup));
        assertEquals(0, deletes.get()); // save may already have written; no blind store rollback.
        assertEquals((previous ? 1 : 0) + (reentrant ? 1 : 0) + 1, saves.get());
        assertSame(reentrant ? fresh.get() : old, registry.get().get("same"));
        assertEquals(previous || reentrant ? 1 : 0, registry.get().getAllAgenticScopeKeysInMemory().size());
        assertEquals(0, priorReleases.get());
        if (old != null) old.invocationTasks().cancel(new IllegalStateException("fixture cleanup"));
    }
    private static void load(boolean error) {
        Throwable primary = error ? new AssertionError("load-primary") : new IllegalStateException("load-primary");
        AtomicInteger loads = new AtomicInteger(), saves = new AtomicInteger(), calls = new AtomicInteger();
        AgenticScopePersister.setStore(new AgenticRecoveryJdk8Test.JsonStore() {
            @Override public Optional<DefaultAgenticScope> load(AgenticScopeKey key) { loads.incrementAndGet();throwUnchecked(primary);return Optional.empty(); }
            @Override public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) { saves.incrementAndGet();return true; }
        });
        Object step = AgenticServices.nonAiAgentBuilder(scope -> {calls.incrementAndGet();return "ok";}).name("step").outputKey("answer").build();
        Work work = AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").build();
        try { work.run("same");fail("expected load failure"); }catch (RuntimeException | Error actual) {assertSame(primary,actual);}
        assertEquals(1,loads.get());assertEquals(0,saves.get());assertEquals(0,calls.get());
        assertTrue(((AgenticScopeOwner)work).registry().getAllAgenticScopeKeysInMemory().isEmpty());
    }
    private static void listener(String mode) {
        boolean success=mode.equals("runtime"), persistent=mode.equals("persistent")||mode.equals("flush");
        Throwable primary=success?new IllegalStateException("listener-primary"):new AssertionError("listener-primary");
        AssertionError cleanup=new AssertionError("flush-secondary");
        AtomicReference<DefaultAgenticScope> captured=new AtomicReference<>();AtomicReference<InvocationTasks> owner=new AtomicReference<>();
        AtomicInteger releases=new AtomicInteger(),creates=new AtomicInteger(),body=new AtomicInteger(),saves=new AtomicInteger();
        AgenticRecoveryJdk8Test.JsonStore store=new AgenticRecoveryJdk8Test.JsonStore(){
            @Override public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){
                int n=saves.incrementAndGet();if(mode.equals("flush")&&n>1)throw cleanup;return super.save(key,scope);
            }
        };
        if(persistent)AgenticScopePersister.setStore(store);
        AgentListener listener=new AgentListener(){
            @Override public void afterAgenticScopeCreated(AgenticScope scope){
                creates.incrementAndGet();captured.set((DefaultAgenticScope)scope);owner.set(captured.get().invocationTasks());
                scope.writeState("initialized","partial");owner.get().register(releases::incrementAndGet);throwUnchecked(primary);
            }
        };
        Object step=AgenticServices.nonAiAgentBuilder(scope->{body.incrementAndGet();return "ok";}).name("step").outputKey("answer").build();
        Object work=mode.equals("ephemeral")?AgenticServices.sequenceBuilder(Ephemeral.class).subAgents(step).outputKey("answer").listener(listener).build()
                :AgenticServices.sequenceBuilder(Work.class).subAgents(step).outputKey("answer").listener(listener).build();
        if(success){assertEquals("ok",((Work)work).run("same"));assertEquals(0,releases.get());assertEquals(1,body.get());owner.get().cancel(new IllegalStateException("fixture cleanup"));return;}
        try{if(work instanceof Work)((Work)work).run("same");else((Ephemeral)work).run();fail("expected listener failure");}
        catch(RuntimeException|Error actual){assertSame(primary,actual);}
        assertEquals(1,creates.get());assertEquals(0,body.get());assertEquals("creation resource leaked",1,releases.get());assertTrue(owner.get().finished());
        AtomicInteger late=new AtomicInteger();owner.get().register(late::incrementAndGet);assertEquals(1,late.get());
        AgenticScopeRegistry registry=((AgenticScopeOwner)work).registry();
        if(mode.equals("ephemeral"))assertTrue(registry.getAllAgenticScopeKeysInMemory().isEmpty());else assertSame(captured.get(),registry.get("same"));
        if(persistent){assertEquals(2,saves.get());if(mode.equals("flush"))assertTrue(java.util.Arrays.asList(primary.getSuppressed()).contains(cleanup));
            else assertEquals("partial",store.load(new AgenticScopeKey(Work.class.getName(),"same")).get().readState("initialized"));}
    }
    private static void throwUnchecked(Throwable error){if(error instanceof Error)throw(Error)error;throw(RuntimeException)error;}
}
