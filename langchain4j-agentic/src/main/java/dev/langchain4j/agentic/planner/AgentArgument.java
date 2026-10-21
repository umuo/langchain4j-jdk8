package dev.langchain4j.agentic.planner;

import dev.langchain4j.agentic.internal.AgentUtil;
import java.lang.reflect.Type;

public final class AgentArgument {

    public AgentArgument(Type type, String name, Object defaultValue, boolean isOptional) {
        this(type, name, defaultValue, isOptional, null);
    }

    public AgentArgument(Type type, String name) {
        this(type, name, null);
    }

    public AgentArgument(Type type, String name, Object defaultValue) {
        this(type, name, defaultValue, false);
    }

    public AgentArgument withDescription(String description) {
        return new AgentArgument(type, name, defaultValue, isOptional, description);
    }

    public Class<?> rawType() {
        return AgentUtil.rawType(type);
    }

    private final Type type;

    public Type type() {
        return type;
    }

    private final String name;

    public String name() {
        return name;
    }

    private final Object defaultValue;

    public Object defaultValue() {
        return defaultValue;
    }

    private final boolean isOptional;

    public boolean isOptional() {
        return isOptional;
    }

    private final String description;

    public String description() {
        return description;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public AgentArgument(@com.fasterxml.jackson.annotation.JsonProperty("type") Type type, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("defaultValue") Object defaultValue, @com.fasterxml.jackson.annotation.JsonProperty("isOptional") boolean isOptional, @com.fasterxml.jackson.annotation.JsonProperty("description") String description) {
        this.type = type;
        this.name = name;
        this.defaultValue = defaultValue;
        this.isOptional = isOptional;
        this.description = description;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(type, ((AgentArgument) obj).type) && java.util.Objects.equals(name, ((AgentArgument) obj).name) && java.util.Objects.equals(defaultValue, ((AgentArgument) obj).defaultValue) && java.util.Objects.equals(isOptional, ((AgentArgument) obj).isOptional) && java.util.Objects.equals(description, ((AgentArgument) obj).description));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * (31 * (31 * 0 + java.util.Objects.hashCode(type)) + java.util.Objects.hashCode(name)) + java.util.Objects.hashCode(defaultValue)) + java.util.Objects.hashCode(isOptional)) + java.util.Objects.hashCode(description));
    }

    public final String toString() {
        return "AgentArgument[" + "type=" + type + ", name=" + name + ", defaultValue=" + defaultValue + ", isOptional=" + isOptional + ", description=" + description + "]";
    }
}
