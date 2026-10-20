package dev.langchain4j.internal.reactive;

/**
 * Internal bounded publisher emitter.
 */
public interface Tube<T> {

    void send(T item);

    void fail(Throwable error);

    void complete();

    boolean cancelled();

    void whenTerminates(Runnable action);

    void whenCancelled(Runnable action);
}
