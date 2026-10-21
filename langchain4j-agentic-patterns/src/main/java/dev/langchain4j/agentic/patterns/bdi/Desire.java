package dev.langchain4j.agentic.patterns.bdi;

import dev.langchain4j.agentic.scope.AgenticScope;

import java.util.List;
import java.util.function.Predicate;

/**
 * A prioritized goal for the {@link BDIPlanner}. Each desire declares when it is achievable, when
 * it is satisfied, and the ordered sequence of agent types that form its intention. Higher priority
 * values take precedence; among equal priorities, declaration order wins (stable ordering).
 *
 * @param name       human-readable label, used in log messages and error diagnostics
 * @param priority   higher value = more important; strictly higher priority triggers preemption
 * @param achievable predicate on {@link dev.langchain4j.agentic.scope.AgenticScope} — can this desire be pursued now?
 * @param satisfied  predicate on {@link dev.langchain4j.agentic.scope.AgenticScope} — has this desire been achieved?
 * @param agentTypes ordered agent classes forming the intention; resolved to instances at init time
 */
public final class Desire {
        private final String name;
        public String name() { return name; }
        private final int priority;
        public int priority() { return priority; }
        private final Predicate<AgenticScope> achievable;
        public Predicate<AgenticScope> achievable() { return achievable; }
        private final Predicate<AgenticScope> satisfied;
        public Predicate<AgenticScope> satisfied() { return satisfied; }
        private final List<Class<?>> agentTypes;
        public List<Class<?>> agentTypes() { return agentTypes; }
        @com.fasterxml.jackson.annotation.JsonCreator
        public Desire(@com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                @com.fasterxml.jackson.annotation.JsonProperty("priority") int priority,
                @com.fasterxml.jackson.annotation.JsonProperty("achievable") Predicate<AgenticScope> achievable,
                @com.fasterxml.jackson.annotation.JsonProperty("satisfied") Predicate<AgenticScope> satisfied,
                @com.fasterxml.jackson.annotation.JsonProperty("agentTypes") List<Class<?>> agentTypes) {
            if (agentTypes == null || agentTypes.isEmpty()) {
                throw new IllegalArgumentException("Desire '" + name + "' must have at least one agent type");
            }
            this.name = name;
            this.priority = priority;
            this.achievable = achievable;
            this.satisfied = satisfied;
            this.agentTypes = agentTypes;
        }
        @Override public final boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || other.getClass() != getClass()) return false;
            Desire value = (Desire) other;
            return java.util.Objects.equals(name, value.name) && java.util.Objects.equals(priority, value.priority) && java.util.Objects.equals(achievable, value.achievable) && java.util.Objects.equals(satisfied, value.satisfied) && java.util.Objects.equals(agentTypes, value.agentTypes);
        }
        @Override public final int hashCode() {
            int result = 0;
            result = 31 * result + java.util.Objects.hashCode(name);
            result = 31 * result + java.util.Objects.hashCode(priority);
            result = 31 * result + java.util.Objects.hashCode(achievable);
            result = 31 * result + java.util.Objects.hashCode(satisfied);
            result = 31 * result + java.util.Objects.hashCode(agentTypes);
            return result;
        }
        @Override public final String toString() { return "Desire[" + "name=" + name + ", " + "priority=" + priority + ", " + "achievable=" + achievable + ", " + "satisfied=" + satisfied + ", " + "agentTypes=" + agentTypes + "]"; }

    public static Desire of(String name, int priority,
                            Predicate<AgenticScope> achievable,
                            Predicate<AgenticScope> satisfied,
                            Class<?>... agentTypes) {
        return new Desire(name, priority, achievable, satisfied, dev.langchain4j.internal.Jdk8.list(agentTypes));
    }

    public static Desire of(String name, int priority,
                            String achievableStateKey,
                            String satisfiedStateKey,
                            Class<?>... agentTypes) {
        return new Desire(name, priority,
                scope -> scope.hasState(achievableStateKey),
                scope -> scope.hasState(satisfiedStateKey),
                dev.langchain4j.internal.Jdk8.list(agentTypes));
    }
}
