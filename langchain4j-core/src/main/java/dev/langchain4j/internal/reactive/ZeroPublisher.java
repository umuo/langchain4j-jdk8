package dev.langchain4j.internal.reactive;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.reactivestreams.*;

/**
 * Cold bounded Reactive Streams bridge; upstream is released on every terminal signal.
 */
public final class ZeroPublisher {

    private ZeroPublisher() {
    }

    public static <T> Publisher<T> create(TubeConfiguration config, Consumer<Tube<T>> producer) {
        final int capacity = Objects.requireNonNull(config).bufferSize();
        Objects.requireNonNull(producer);
        return downstream -> {
            Objects.requireNonNull(downstream, "subscriber");
            State<T> state = new State<T>(downstream, capacity);
            downstream.onSubscribe(state);
            if (!state.cancelled())
                try {
                    producer.accept(state);
                } catch (Throwable error) {
                    state.fail(error);
                }
        };
    }

    private static final class State<T> extends AtomicInteger implements Subscription, Tube<T> {

        private Subscriber<? super T> downstream;

        private final int capacity;

        private final ArrayDeque<T> queue = new ArrayDeque<T>();

        private final List<Runnable> cleanup = new ArrayList<Runnable>();

        private final List<Runnable> cancellationActions = new ArrayList<Runnable>();

        private long demand;

        private boolean done, cancelled, terminated, resourcesReleased;

        private Throwable error;

        State(Subscriber<? super T> downstream, int capacity) {
            this.downstream = downstream;
            this.capacity = capacity;
        }

        public void request(long n) {
            if (n <= 0) {
                synchronized (this) {
                    if (cancelled || terminated)
                        return;
                    error = new IllegalArgumentException("Reactive Streams rule 3.9: non-positive subscription request");
                    done = true;
                    queue.clear();
                }
                release();
                drain();
                return;
            }
            synchronized (this) {
                if (cancelled || terminated)
                    return;
                long next = demand + n;
                demand = next < 0 ? Long.MAX_VALUE : next;
            }
            drain();
        }

        public void cancel() {
            List<Runnable> actions;
            synchronized (this) {
                if (cancelled || terminated)
                    return;
                cancelled = true;
                downstream = null;
                queue.clear();
                actions = new ArrayList<Runnable>(cancellationActions);
                cancellationActions.clear();
            }
            release();
            for (Runnable action : actions) safeRun(action);
        }

        public void whenCancelled(Runnable action) {
            Objects.requireNonNull(action);
            boolean run;
            synchronized (this) {
                run = cancelled;
                if (!run && !terminated)
                    cancellationActions.add(action);
            }
            if (run)
                safeRun(action);
        }

        public boolean cancelled() {
            synchronized (this) {
                return cancelled || done || terminated;
            }
        }

        public void send(T item) {
            Objects.requireNonNull(item, "item");
            boolean overflow = false;
            synchronized (this) {
                if (done || cancelled || terminated)
                    return;
                if (queue.size() == capacity) {
                    error = new IllegalStateException("Streaming buffer overflow (capacity=" + capacity + ")");
                    done = true;
                    queue.clear();
                    overflow = true;
                } else
                    queue.offer(item);
            }
            if (overflow)
                release();
            drain();
        }

        public void fail(Throwable failure) {
            Objects.requireNonNull(failure, "error");
            synchronized (this) {
                if (done || cancelled || terminated)
                    return;
                error = failure;
                done = true;
                queue.clear();
            }
            release();
            drain();
        }

        public void complete() {
            synchronized (this) {
                if (done || cancelled || terminated)
                    return;
                done = true;
            }
            release();
            drain();
        }

        public void whenTerminates(Runnable action) {
            Objects.requireNonNull(action);
            boolean run;
            synchronized (this) {
                run = resourcesReleased;
                if (!run)
                    cleanup.add(action);
            }
            if (run)
                safeRun(action);
        }

        private void release() {
            List<Runnable> actions;
            synchronized (this) {
                if (resourcesReleased)
                    return;
                resourcesReleased = true;
                actions = new ArrayList<Runnable>(cleanup);
                cleanup.clear();
            }
            for (Runnable r : actions) safeRun(r);
        }

        private static void safeRun(Runnable action) {
            try {
                action.run();
            } catch (Throwable ignored) {
                /* cleanup must not prevent terminal delivery */
            }
        }

        private void drain() {
            if (getAndIncrement() != 0)
                return;
            int missed = 1;
            for (; ; ) {
                for (; ; ) {
                    T next;
                    Throwable failure;
                    boolean finish;
                    Subscriber<? super T> target;
                    synchronized (this) {
                        if (cancelled) {
                            queue.clear();
                            return;
                        }
                        target = downstream;
                        finish = done && (error != null || queue.isEmpty());
                        failure = error;
                        if (finish) {
                            if (terminated)
                                return;
                            terminated = true;
                            downstream = null;
                            cancellationActions.clear();
                            next = null;
                        } else if (demand > 0 && !queue.isEmpty()) {
                            next = queue.poll();
                            if (demand != Long.MAX_VALUE)
                                demand--;
                        } else
                            break;
                    }
                    if (finish) {
                        try {
                            if (failure != null)
                                target.onError(failure);
                            else
                                target.onComplete();
                        } finally {
                            release();
                        }
                        return;
                    }
                    try {
                        target.onNext(next);
                    } catch (Throwable subscriberError) {
                        cancel();
                        return;
                    }
                }
                missed = addAndGet(-missed);
                if (missed == 0)
                    return;
            }
        }
    }
}
