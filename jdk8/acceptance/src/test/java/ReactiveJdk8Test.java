import dev.langchain4j.internal.reactive.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.reactivestreams.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReactiveJdk8Test {

    static class Probe<T> implements Subscriber<T> {

        Subscription subscription;

        final List<T> values = new CopyOnWriteArrayList<T>();

        volatile Throwable error;

        final AtomicInteger completed = new AtomicInteger();

        final CountDownLatch terminal = new CountDownLatch(1);

        public void onSubscribe(Subscription s) {
            subscription = s;
        }

        public void onNext(T item) {
            values.add(item);
        }

        public void onError(Throwable e) {
            error = e;
            terminal.countDown();
        }

        public void onComplete() {
            completed.incrementAndGet();
            terminal.countDown();
        }
    }

    @Test
    public void buffersUntilDemandAndCompletesOnce() {
        Probe<Integer> p = new Probe<Integer>();
        AtomicInteger released = new AtomicInteger();
        ZeroPublisher.<Integer>create(new TubeConfiguration().withBufferSize(3), tube -> {
            tube.whenTerminates(released::incrementAndGet);
            tube.send(1);
            tube.send(2);
            tube.complete();
            tube.complete();
            tube.fail(new RuntimeException());
        }).subscribe(p);
        assertTrue(p.values.isEmpty());
        assertEquals(0, p.completed.get());
        assertEquals(1, released.get());
        p.subscription.request(1);
        assertEquals(Arrays.asList(1), p.values);
        assertEquals(0, p.completed.get());
        p.subscription.request(1);
        assertEquals(Arrays.asList(1, 2), p.values);
        assertEquals(1, p.completed.get());
        assertNull(p.error);
        p.subscription.request(5);
        assertEquals(1, p.completed.get());
    }

    @Test
    public void invalidDemandFailsBeforeProducerStarts() {
        AtomicBoolean started = new AtomicBoolean();
        Probe<Integer> p = new Probe<Integer>() {

            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                s.request(0);
            }
        };
        ZeroPublisher.<Integer>create(new TubeConfiguration(), tube -> started.set(true)).subscribe(p);
        assertFalse(started.get());
        assertTrue(p.error instanceof IllegalArgumentException);
        assertEquals(0, p.completed.get());
    }

    @Test
    public void invalidDemandOverridesBufferedCompletion() {
        Probe<Integer> p = new Probe<Integer>();
        ZeroPublisher.<Integer>create(new TubeConfiguration(), tube -> {
            tube.send(1);
            tube.complete();
        }).subscribe(p);
        p.subscription.request(-1);
        assertTrue(p.error instanceof IllegalArgumentException);
        assertTrue(p.values.isEmpty());
        assertEquals(0, p.completed.get());
    }

    @Test
    public void overflowFailsWithoutDemandAndReleasesResources() {
        AtomicInteger released = new AtomicInteger();
        Probe<Integer> p = new Probe<Integer>();
        ZeroPublisher.<Integer>create(new TubeConfiguration().withBufferSize(1), tube -> {
            tube.whenTerminates(released::incrementAndGet);
            tube.send(1);
            tube.send(2);
            tube.complete();
        }).subscribe(p);
        assertTrue(p.error instanceof IllegalStateException);
        assertTrue(p.values.isEmpty());
        assertEquals(1, released.get());
    }

    @Test
    public void cancellationRunsLateCleanupAndDropsEvents() {
        Probe<Integer> p = new Probe<Integer>();
        AtomicInteger released = new AtomicInteger();
        AtomicReference<Tube<Integer>> emitter = new AtomicReference<Tube<Integer>>();
        ZeroPublisher.<Integer>create(new TubeConfiguration(), tube -> {
            emitter.set(tube);
            tube.send(1);
        }).subscribe(p);
        p.subscription.cancel();
        p.subscription.cancel();
        emitter.get().whenTerminates(released::incrementAndGet);
        emitter.get().send(2);
        emitter.get().complete();
        assertEquals(1, released.get());
        assertTrue(p.values.isEmpty());
        assertNull(p.error);
        assertEquals(0, p.completed.get());
    }

    @Test
    public void requestFromOnNextIsReentrantAndColdSubscriptionsAreIndependent() {
        Publisher<Integer> publisher = ZeroPublisher.create(new TubeConfiguration(), tube -> {
            for (int i = 0; i < 100; i++) tube.send(i);
            tube.complete();
        });
        Probe<Integer> first = new Probe<Integer>() {

            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                s.request(1);
            }

            public void onNext(Integer i) {
                super.onNext(i);
                subscription.request(1);
            }
        };
        publisher.subscribe(first);
        assertEquals(100, first.values.size());
        assertEquals(1, first.completed.get());
        Probe<Integer> second = new Probe<Integer>();
        publisher.subscribe(second);
        assertTrue(second.values.isEmpty());
        second.subscription.request(Long.MAX_VALUE);
        assertEquals(first.values, second.values);
    }

    @Test
    public void concurrentRequestAndEmissionAreSerialized() throws Exception {
        Probe<Integer> p = new Probe<Integer>();
        AtomicReference<Tube<Integer>> emitter = new AtomicReference<Tube<Integer>>();
        ZeroPublisher.<Integer>create(new TubeConfiguration().withBufferSize(2000), emitter::set).subscribe(p);
        Thread producer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) emitter.get().send(i);
            emitter.get().complete();
        });
        Thread requester = new Thread(() -> {
            for (int i = 0; i < 1000; i++) p.subscription.request(1);
        });
        producer.start();
        requester.start();
        producer.join();
        requester.join();
        assertTrue(p.terminal.await(5, TimeUnit.SECONDS));
        assertNull(p.error);
        assertEquals(1000, p.values.size());
        assertEquals(1, p.completed.get());
        for (int i = 0; i < 1000; i++) assertEquals(Integer.valueOf(i), p.values.get(i));
    }
}
