import org.reactivestreams.Publisher;

/** Same official Publisher rules with callbacks produced on a separate Java 8 thread. */
public class AsyncZeroPublisherTckTest extends ZeroPublisherTckTest {
    @Override public Publisher<Long> createPublisher(long elements) { return publisher(elements, true); }
}
