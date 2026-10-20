import dev.langchain4j.internal.reactive.Tube;
import dev.langchain4j.internal.reactive.TubeConfiguration;
import dev.langchain4j.internal.reactive.ZeroPublisher;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.*;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

/** Unmodified official PublisherVerification rules against the actual bounded bridge. */
public class ZeroPublisherTckTest extends PublisherVerification<Long> {
    public ZeroPublisherTckTest() { super(new TestEnvironment(500)); }

    @Override public Publisher<Long> createPublisher(long elements) { return publisher(elements, false); }

    static Publisher<Long> publisher(long elements, boolean asynchronous) {
        AtomicReference<Tube<Long>> retainedEmitter = new AtomicReference<>();
        return subscriber -> {
            // TCK's Integer.MAX_VALUE stream must be lazy: produce at most one item ahead of delivery.
            // Only the producer is paced. The actual State subscription is passed through unchanged.
            boolean paced = elements > 1024;
            Semaphore delivered = new Semaphore(0);
            int capacity = (int) Math.max(1, Math.min(elements, 1024));
            Publisher<Long> source = ZeroPublisher.create(new TubeConfiguration().withBufferSize(capacity), tube -> {
                // External sources may retain an emitter after cancellation. State must release its subscriber.
                retainedEmitter.set(tube);
                Runnable emit = () -> {
                    try {
                        for (long i=0; i<elements && !tube.cancelled(); i++) {
                            tube.send(i);
                            if (paced && !tube.cancelled()) delivered.acquire();
                        }
                        tube.complete();
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        if (!tube.cancelled()) tube.fail(stopped);
                    }
                };
                if (asynchronous || paced) {
                    Thread worker = new Thread(emit, "jdk8-tck-source");
                    worker.setDaemon(true);
                    tube.whenTerminates(() -> { delivered.release(); worker.interrupt(); });
                    worker.start();
                } else emit.run();
            });
            source.subscribe(new Subscriber<Long>() {
                public void onSubscribe(Subscription subscription) { subscriber.onSubscribe(subscription); }
                public void onNext(Long item) { subscriber.onNext(item); delivered.release(); }
                public void onError(Throwable error) { subscriber.onError(error); }
                public void onComplete() { subscriber.onComplete(); }
            });
        };
    }
    @Override public Publisher<Long> createFailedPublisher() {
        return ZeroPublisher.create(new TubeConfiguration(), tube -> tube.fail(new IllegalStateException("fixture failure")));
    }
}
