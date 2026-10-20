package dev.langchain4j.model.chat.response;

/**
 * Default {@link RawStreamingEvent} implementation, wrapping the raw provider event. Package-private;
 * created via {@link RawStreamingEvent#of(Object)}.
 *
 * @param rawEvent the raw provider streaming event; see {@link RawStreamingEvent#rawEvent()}
 */
final class DefaultRawStreamingEvent implements RawStreamingEvent {

    private final Object rawEvent;

    public Object rawEvent() {
        return rawEvent;
    }

    public DefaultRawStreamingEvent(Object rawEvent) {
        this.rawEvent = rawEvent;
    }

    public boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(rawEvent, ((DefaultRawStreamingEvent) obj).rawEvent));
    }

    public int hashCode() {
        return (31 * 0 + java.util.Objects.hashCode(rawEvent));
    }

    public String toString() {
        return "DefaultRawStreamingEvent[" + "rawEvent=" + rawEvent + "]";
    }
}
