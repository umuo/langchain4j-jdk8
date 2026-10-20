package dev.langchain4j.model.chat.router;

import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import dev.langchain4j.Experimental;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A route of a {@link RoutingChatModel} or {@link RoutingStreamingChatModel}, as seen by a {@link ChatModelRouter}.
 *
 * @since 1.21.0
 */
@Experimental
public final class ChatModelRoute {

    private final String name;

    private final List<String> descriptions;

    /**
     * @param name        the unique name of the route, for example {@code "simple"} or {@code "complex"}.
     * @param description what kind of requests the route is meant for, or {@code null}.
     */
    public ChatModelRoute(String name, String description) {
        this(name, description == null ? dev.langchain4j.internal.Jdk8.list() : dev.langchain4j.internal.Jdk8.list(description));
    }

    /**
     * @param name         the unique name of the route, for example {@code "simple"} or {@code "complex"}.
     * @param descriptions the kinds of requests the route is meant for, for example one description per topic. Can
     *                     be empty.
     */
    public ChatModelRoute(String name, List<String> descriptions) {
        this.name = ensureNotBlank(name, "name");
        List<String> copy = new ArrayList<>();
        if (descriptions != null) {
            descriptions.forEach(description -> copy.add(ensureNotBlank(description, "description of route " + name)));
        }
        this.descriptions = dev.langchain4j.internal.Jdk8.copyList(copy);
    }

    /**
     * The unique name of the route.
     */
    public String name() {
        return name;
    }

    /**
     * The kinds of requests the route is meant for. Empty if the route has no description.
     */
    public List<String> descriptions() {
        return descriptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ChatModelRoute))
            return false;
        return Objects.equals(name, ((ChatModelRoute) o).name) && Objects.equals(descriptions, ((ChatModelRoute) o).descriptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, descriptions);
    }

    @Override
    public String toString() {
        return "ChatModelRoute{name=" + name + ", descriptions=" + descriptions + '}';
    }
}
