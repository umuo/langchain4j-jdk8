package dev.langchain4j.service;

import dev.langchain4j.Internal;
import org.reactivestreams.Subscription;

/**
 * A {@link Subscription} that can be handed to a subscriber <b>before</b> the real upstream subscription
 * exists, and later switched onto it. Used by the reactive AI Service publisher so that {@code onSubscribe} is
 * signalled synchronously at {@code subscribe()} time — before the asynchronous prologue (RAG augmentation, input
 * guardrails, memory assembly) runs — giving the subscriber something to {@code cancel()} throughout that window.
 * <p>
 * Behavior:
 * <ul>
 *   <li>{@link #request(long)} before the real subscription arrives is accumulated and replayed once it does;
 *       afterwards it is forwarded directly.</li>
 *   <li>{@link #cancel()} before the real subscription arrives runs the current {@link #setCancelAction cancel
 *       action} (which aborts the in-flight prologue future); afterwards it cancels the real subscription. Idempotent.</li>
 *   <li>{@link #setSubscription} hands over the real subscription: if already cancelled it is cancelled immediately,
 *       otherwise accumulated demand is replayed to it.</li>
 * </ul>
 * Signals are computed under a lock but delivered to the delegate / cancel action outside it, so no downstream
 * callback runs while the monitor is held.
 *
 * @since 1.20.0
 */
@Internal
final class DeferredSubscription implements Subscription {

    private Subscription delegate;

    private Runnable cancelAction;

    private long pendingDemand;

    private boolean cancelled;

    private boolean terminated;

    private java.util.function.Consumer<Throwable> earlyError;

    DeferredSubscription() { this(error -> { }); }

    DeferredSubscription(java.util.function.Consumer<Throwable> earlyError) {
        this.earlyError = java.util.Objects.requireNonNull(earlyError);
    }

    /** Claims the only terminal signal and releases references retained by this subscription. */
    synchronized boolean tryTerminate() {
        if (cancelled || terminated) return false;
        terminated = true;
        delegate = null;
        cancelAction = null;
        earlyError = null;
        return true;
    }

    /**
     * Sets the action that {@link #cancel()} runs while the real subscription is not yet available — typically
     * cancelling the in-flight prologue future. Updated as the prologue advances from one stage to the next. If the
     * subscription has already been cancelled, the action runs immediately.
     */
    void setCancelAction(Runnable action) {
        boolean runNow;
        synchronized (this) {
            runNow = cancelled || terminated;
            if (!runNow) {
                this.cancelAction = action;
            }
        }
        if (runNow) {
            action.run();
        }
    }

    /**
     * Hands over the real upstream subscription. If this subscription was already cancelled, the real one is
     * cancelled immediately; otherwise any demand requested so far is replayed onto it.
     */
    void setSubscription(Subscription subscription) {
        boolean cancelNow;
        long demand;
        synchronized (this) {
            cancelNow = cancelled || terminated;
            if (!cancelNow) {
                this.delegate = subscription;
                this.cancelAction = null;
                demand = pendingDemand;
                pendingDemand = 0;
            } else {
                demand = 0;
            }
        }
        if (cancelNow) {
            subscription.cancel();
        } else if (demand > 0) {
            subscription.request(demand);
        }
    }

    boolean isCancelled() {
        synchronized (this) {
            return cancelled || terminated;
        }
    }

    @Override
    public void request(long n) {
        Subscription d;
        Runnable action = null;
        java.util.function.Consumer<Throwable> errorHandler = null;
        synchronized (this) {
            if (cancelled || terminated) return;
            d = delegate;
            if (d == null) {
                if (n > 0) {
                    long sum = pendingDemand + n;
                    pendingDemand = sum < 0 ? Long.MAX_VALUE : sum;
                } else {
                    // There may never be a delegate (e.g. RAG is pending). Signal rule 3.9 now.
                    cancelled = true;
                    pendingDemand = 0;
                    action = cancelAction;
                    cancelAction = null;
                    errorHandler = earlyError;
                    earlyError = null;
                }
            }
        }
        if (d != null) d.request(n);
        if (action != null) action.run();
        if (errorHandler != null) errorHandler.accept(
                new IllegalArgumentException("Reactive Streams rule 3.9: non-positive subscription request"));
    }

    @Override
    public void cancel() {
        Subscription d = null;
        Runnable action = null;
        synchronized (this) {
            if (cancelled || terminated) {
                return;
            }
            cancelled = true;
            pendingDemand = 0;
            earlyError = null;
            if (delegate != null) {
                d = delegate;
                delegate = null;
            } else if (cancelAction != null) {
                action = cancelAction;
                cancelAction = null;
            }
        }
        if (d != null) {
            d.cancel();
        }
        if (action != null) {
            action.run();
        }
    }
}
