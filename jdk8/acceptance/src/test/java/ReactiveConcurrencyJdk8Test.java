import dev.langchain4j.internal.reactive.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.reactivestreams.*;
import static org.junit.Assert.*;

/** Bounded stress and deterministic races, in addition to the official protocol suite. */
public class ReactiveConcurrencyJdk8Test {
    @Test public void concurrentProducersAndRequestersSerializeEverySignal() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            for (int round=0; round<100; round++) {
                AtomicReference<Tube<Integer>> tube = new AtomicReference<>();
                AtomicInteger inCallback = new AtomicInteger(), overlap = new AtomicInteger(), terminals = new AtomicInteger(), deliveries = new AtomicInteger();
                Set<Integer> seen = ConcurrentHashMap.newKeySet();
                ReactiveJdk8Test.Probe<Integer> probe = new ReactiveJdk8Test.Probe<Integer>() {
                    public void onNext(Integer value) {
                        if (inCallback.incrementAndGet()!=1) overlap.incrementAndGet();
                        seen.add(value); deliveries.incrementAndGet(); inCallback.decrementAndGet();
                    }
                    public void onComplete() {
                        if (inCallback.incrementAndGet()!=1) overlap.incrementAndGet();
                        terminals.incrementAndGet(); inCallback.decrementAndGet(); super.onComplete();
                    }
                };
                ZeroPublisher.<Integer>create(new TubeConfiguration().withBufferSize(1000), tube::set).subscribe(probe);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> p1=workers.submit(() -> { await(go); for(int i=0;i<500;i++) tube.get().send(i); });
                Future<?> p2=workers.submit(() -> { await(go); for(int i=500;i<1000;i++) tube.get().send(i); });
                Future<?> r1=workers.submit(() -> { await(go); for(int i=0;i<500;i++) probe.subscription.request(1); });
                Future<?> r2=workers.submit(() -> { await(go); for(int i=0;i<500;i++) probe.subscription.request(1); });
                go.countDown();
                for(Future<?> task:Arrays.asList(p1,p2,r1,r2)) task.get(5,TimeUnit.SECONDS);
                tube.get().complete();
                assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));
                assertNull(probe.error); assertEquals(1000,seen.size()); assertEquals(1000,deliveries.get()); assertEquals(0,overlap.get()); assertEquals(1,terminals.get());
            }
        } finally { workers.shutdownNow(); }
    }
    @Test public void failureWaitsForInFlightOnNextAndTerminatesOnce() throws Exception {
        AtomicReference<Tube<Integer>> tube = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger(), errors = new AtomicInteger();
        ReactiveJdk8Test.Probe<Integer> probe = new ReactiveJdk8Test.Probe<Integer>() {
            public void onNext(Integer value) { entered.countDown(); await(leave); super.onNext(value); }
            public void onError(Throwable error) { errors.incrementAndGet(); super.onError(error); }
        };
        ZeroPublisher.<Integer>create(new TubeConfiguration(), t -> { tube.set(t); t.whenTerminates(releases::incrementAndGet); }).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task=worker.submit(() -> tube.get().send(1));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            tube.get().fail(new IllegalStateException("first error")); tube.get().fail(new IllegalStateException("late error")); tube.get().complete();
            assertEquals(1,releases.get()); assertNull(probe.error); assertEquals(0,probe.completed.get());
            leave.countDown(); task.get(5,TimeUnit.SECONDS);
            assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));
            assertEquals("first error",probe.error.getMessage()); assertEquals(1,errors.get()); assertEquals(0,probe.completed.get());
        } finally { leave.countDown(); worker.shutdownNow(); }
    }
    @Test public void cancellationDuringOnNextReleasesOnceAndDropsQueuedSignals() throws Exception {
        AtomicReference<Tube<Integer>> tube = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger(), cancels = new AtomicInteger();
        ReactiveJdk8Test.Probe<Integer> probe = new ReactiveJdk8Test.Probe<Integer>() {
            public void onNext(Integer value) { entered.countDown(); await(leave); super.onNext(value); }
        };
        ZeroPublisher.<Integer>create(new TubeConfiguration(), t -> {
            tube.set(t); t.whenTerminates(releases::incrementAndGet); t.whenCancelled(cancels::incrementAndGet);
        }).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task=worker.submit(() -> tube.get().send(1));
            assertTrue(entered.await(5,TimeUnit.SECONDS)); tube.get().send(2);
            probe.subscription.cancel(); probe.subscription.cancel(); tube.get().complete();
            leave.countDown(); task.get(5,TimeUnit.SECONDS);
            assertEquals(Collections.singletonList(1),probe.values); assertNull(probe.error); assertEquals(0,probe.completed.get());
            assertEquals(1,releases.get()); assertEquals(1,cancels.get());
        } finally { leave.countDown(); worker.shutdownNow(); }
    }
    @Test public void concurrentTerminalSignalsHaveSingleWinnerAndSingleCleanup() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(3);
        try {
            for(int round=0;round<300;round++) {
                AtomicReference<Tube<Integer>> tube=new AtomicReference<>();
                AtomicInteger terminals=new AtomicInteger(), releases=new AtomicInteger();
                ReactiveJdk8Test.Probe<Integer> probe=new ReactiveJdk8Test.Probe<Integer>() {
                    public void onError(Throwable error) { terminals.incrementAndGet(); super.onError(error); }
                    public void onComplete() { terminals.incrementAndGet(); super.onComplete(); }
                };
                ZeroPublisher.<Integer>create(new TubeConfiguration(), t -> { tube.set(t); t.whenTerminates(releases::incrementAndGet); }).subscribe(probe);
                CountDownLatch go=new CountDownLatch(1);
                Future<?> complete=workers.submit(() -> { await(go); tube.get().complete(); });
                Future<?> fail=workers.submit(() -> { await(go); tube.get().fail(new IllegalStateException("race")); });
                Future<?> request=workers.submit(() -> { await(go); probe.subscription.request(Long.MAX_VALUE); });
                go.countDown(); for(Future<?> task:Arrays.asList(complete,fail,request)) task.get(5,TimeUnit.SECONDS);
                assertTrue(probe.terminal.await(5,TimeUnit.SECONDS)); assertEquals(1,terminals.get()); assertEquals(1,releases.get());
            }
        } finally { workers.shutdownNow(); }
    }

    public interface Events { Publisher<dev.langchain4j.service.AiServiceStreamingEvent> chat(String question); }
    @Test public void invalidDemandWhileRagIsPendingFailsAndCancelsRetrieval() throws Exception {
        CompletableFuture<List<dev.langchain4j.rag.content.Content>> pending = new CompletableFuture<>();
        dev.langchain4j.rag.content.retriever.ContentRetriever retriever = new dev.langchain4j.rag.content.retriever.ContentRetriever() {
            public List<dev.langchain4j.rag.content.Content> retrieve(dev.langchain4j.rag.query.Query query) { throw new AssertionError("Async only"); }
            public CompletableFuture<List<dev.langchain4j.rag.content.Content>> retrieveAsync(dev.langchain4j.rag.query.Query query) { return pending; }
        };
        Events service = dev.langchain4j.service.AiServices.builder(Events.class)
                .streamingChatModel(AiServicesJdk8Test.streamingModel()).contentRetriever(retriever).build();
        ReactiveJdk8Test.Probe<dev.langchain4j.service.AiServiceStreamingEvent> probe = new ReactiveJdk8Test.Probe<>();
        service.chat("question").subscribe(probe);
        probe.subscription.request(0);
        assertTrue("Invalid demand must fail before retrieval finishes", probe.terminal.await(1, TimeUnit.SECONDS));
        assertTrue(probe.error instanceof IllegalArgumentException); assertTrue(pending.isCancelled());
        assertTrue(probe.values.isEmpty()); assertEquals(0,probe.completed.get());
    }
    @Test public void immediateEventCancellationDoesNotStartRag() {
        AtomicInteger starts = new AtomicInteger();
        dev.langchain4j.rag.content.retriever.ContentRetriever retriever = new dev.langchain4j.rag.content.retriever.ContentRetriever() {
            public List<dev.langchain4j.rag.content.Content> retrieve(dev.langchain4j.rag.query.Query query) { throw new AssertionError("Async only"); }
            public CompletableFuture<List<dev.langchain4j.rag.content.Content>> retrieveAsync(dev.langchain4j.rag.query.Query query) {
                starts.incrementAndGet(); return new CompletableFuture<>();
            }
        };
        Events service = dev.langchain4j.service.AiServices.builder(Events.class)
                .streamingChatModel(AiServicesJdk8Test.streamingModel()).contentRetriever(retriever).build();
        service.chat("question").subscribe(new ReactiveJdk8Test.Probe<dev.langchain4j.service.AiServiceStreamingEvent>() {
            public void onSubscribe(Subscription subscription) { super.onSubscribe(subscription); subscription.cancel(); }
        });
        assertEquals("Cancelled subscribers must not start retrieval", 0, starts.get());
    }

    @Test public void realSseBufferOverflowWithoutDemandReleasesHttp() throws Exception {
        OpenAiJdk8Test http = new OpenAiJdk8Test(); http.start();
        try {
            dev.langchain4j.model.openai.OpenAiStreamingChatModel model = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                    .apiKey("synthetic").modelName("mock-model").baseUrl(http.base).httpClientBuilder(http.http())
                    .timeout(java.time.Duration.ofSeconds(3)).streamingBufferSize(1).build();
            ReactiveJdk8Test.Probe<String> probe = new ReactiveJdk8Test.Probe<>();
            model.chat("overflow").subscribe(probe);
            assertTrue(probe.terminal.await(5,TimeUnit.SECONDS));
            assertTrue(probe.error instanceof IllegalStateException);
            assertTrue(probe.error.getMessage().contains("overflow")); assertTrue(probe.values.isEmpty());
            assertEquals(0,probe.completed.get()); http.awaitReleased();
        } finally { http.stop(); }
    }
    @Test public void repeatedSseRequestCancelRacesReleaseEveryHttpCall() throws Exception {
        OpenAiJdk8Test http = new OpenAiJdk8Test(); http.start(); http.mode="long";
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            dev.langchain4j.model.openai.OpenAiStreamingChatModel model=http.streaming();
            for(int round=0;round<30;round++) {
                ReactiveJdk8Test.Probe<String> probe=new ReactiveJdk8Test.Probe<>();
                model.chat("race").subscribe(probe);
                CountDownLatch go=new CountDownLatch(1);
                Future<?> request=workers.submit(() -> { await(go); probe.subscription.request(Long.MAX_VALUE); });
                Future<?> cancel=workers.submit(() -> { await(go); probe.subscription.cancel(); });
                go.countDown(); request.get(5,TimeUnit.SECONDS); cancel.get(5,TimeUnit.SECONDS);
                http.awaitReleased(); assertNull(probe.error); assertEquals(0,probe.completed.get());
            }
        } finally { workers.shutdownNow(); http.stop(); }
    }

    @Test public void invalidDemandAndRetrievalFailureRaceHasOneTerminal() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            for(int round=0;round<100;round++) {
                CompletableFuture<List<dev.langchain4j.rag.content.Content>> pending=new CompletableFuture<>();
                dev.langchain4j.rag.content.retriever.ContentRetriever retriever=new dev.langchain4j.rag.content.retriever.ContentRetriever() {
                    public List<dev.langchain4j.rag.content.Content> retrieve(dev.langchain4j.rag.query.Query query) { throw new AssertionError("Async only"); }
                    public CompletableFuture<List<dev.langchain4j.rag.content.Content>> retrieveAsync(dev.langchain4j.rag.query.Query query) { return pending; }
                };
                Events service=dev.langchain4j.service.AiServices.builder(Events.class)
                        .streamingChatModel(AiServicesJdk8Test.streamingModel()).contentRetriever(retriever).build();
                AtomicInteger errors=new AtomicInteger();
                ReactiveJdk8Test.Probe<dev.langchain4j.service.AiServiceStreamingEvent> probe=new ReactiveJdk8Test.Probe<dev.langchain4j.service.AiServiceStreamingEvent>() {
                    public void onError(Throwable error) { errors.incrementAndGet(); super.onError(error); }
                };
                service.chat("race").subscribe(probe);
                CountDownLatch go=new CountDownLatch(1);
                Future<?> invalid=workers.submit(() -> { await(go); probe.subscription.request(-1); });
                Future<?> failure=workers.submit(() -> { await(go); pending.completeExceptionally(new IllegalStateException("retrieval failed")); });
                go.countDown(); invalid.get(5,TimeUnit.SECONDS); failure.get(5,TimeUnit.SECONDS);
                assertTrue(probe.terminal.await(5,TimeUnit.SECONDS)); assertEquals(1,errors.get()); assertEquals(0,probe.completed.get());
            }
        } finally { workers.shutdownNow(); }
    }
    @Test public void retainedEmittersDoNotRetainSubscribersAfterCompletionOrError() throws Exception {
        for(boolean fail:Arrays.asList(false,true)) {
            AtomicReference<Tube<Integer>> retained=new AtomicReference<>();
            java.lang.ref.ReferenceQueue<Subscriber<Integer>> queue=new java.lang.ref.ReferenceQueue<>();
            java.lang.ref.WeakReference<Subscriber<Integer>> reference=finishedSubscriber(retained,queue,fail);
            System.gc();
            assertSame("Terminated emitters must release subscribers",reference,queue.remove(2000));
            assertNotNull(retained.get());
        }
    }
    static java.lang.ref.WeakReference<Subscriber<Integer>> finishedSubscriber(AtomicReference<Tube<Integer>> retained,
            java.lang.ref.ReferenceQueue<Subscriber<Integer>> queue,boolean fail) {
        ReactiveJdk8Test.Probe<Integer> probe=new ReactiveJdk8Test.Probe<>();
        ZeroPublisher.<Integer>create(new TubeConfiguration(),retained::set).subscribe(probe);
        if(fail) retained.get().fail(new IllegalStateException("finished")); else retained.get().complete();
        return new java.lang.ref.WeakReference<Subscriber<Integer>>(probe,queue);
    }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test barrier"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
