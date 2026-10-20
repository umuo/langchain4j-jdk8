import dev.langchain4j.internal.reactive.Tube;
import dev.langchain4j.internal.reactive.TubeConfiguration;
import dev.langchain4j.internal.reactive.ZeroPublisher;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

/** Unmodified official PublisherVerification rules against the actual bounded bridge. */
public class ZeroPublisherTckTest extends PublisherVerification<Long> {
    public ZeroPublisherTckTest() { super(new TestEnvironment(1000)); }

    @Override public Publisher<Long> createPublisher(long elements) {
        // Configure finite buffering for the requested stream; no demand/filtering wrapper around State.
        // The huge-demand TCK case cancels after a few onNext calls; never preallocate that many elements.
        int capacity = (int) Math.max(1, Math.min(elements, Integer.MAX_VALUE));
        AtomicReference<Tube<Long>> retainedEmitter = new AtomicReference<>();
        return ZeroPublisher.create(new TubeConfiguration().withBufferSize(capacity), tube -> {
            // External asynchronous sources may retain the emitter after cancel. It must release its subscriber.
            retainedEmitter.set(tube);
            for (long i = 0; i < elements && !tube.cancelled(); i++) tube.send(i);
            tube.complete();
        });
    }

    @Override public Publisher<Long> createFailedPublisher() {
        return ZeroPublisher.create(new TubeConfiguration(), tube -> tube.fail(new IllegalStateException("fixture failure")));
    }
}
