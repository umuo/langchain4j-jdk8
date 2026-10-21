package dev.langchain4j.agentic.internal;

import dev.langchain4j.agentic.scope.AgenticScope;
import java.lang.reflect.Method;
import java.util.Map;

final class SpecAgentInvoker extends AbstractAgentInvoker {

    SpecAgentInvoker(Method method, InternalAgent agent) {
        super(method, agent);
    }

    @Override
    public AgentInvocationArguments toInvocationArguments(AgenticScope agenticScope) {
        return new AgentInvocationArguments(dev.langchain4j.internal.Jdk8.map(), new Object[] { agenticScope });
    }
}
