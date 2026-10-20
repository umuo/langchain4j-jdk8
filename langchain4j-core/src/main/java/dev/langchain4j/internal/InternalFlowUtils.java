package dev.langchain4j.internal;

import dev.langchain4j.Internal;
import org.reactivestreams.Subscription;

@Internal
public class InternalFlowUtils {

    public static final Subscription EMPTY_SUBSCRIPTION = new Subscription() {

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    };
}
