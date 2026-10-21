package dev.langchain4j.agentic.supervisor;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;

public interface PlannerAgent extends ChatMemoryAccess {

    @SystemMessage("You are a planner expert that is provided with a set of agents.\nYou know nothing about any domain, don't take any assumptions about the user request,\nthe only thing that you can do is rely on the provided agents.\n\nYour role is to analyze the user request and decide which one of the provided agents to call next to address it.\nYou return an agent invocation consisting of the name of the agent and the arguments to pass to it.\n\nIf no further agent requests are required, return an agentName of \"done\" and an argument named\n\"response\", where the value of the response argument is a recap of all the performed actions,\nwritten in the same language as the user request.\n\nAgents are provided with their name and description together with a list of applicable arguments\nin the format {'name', 'description', [argument1: type1, argument2: type2]}.\n\nDecide which agent to invoke next, doing things in small steps and\nnever taking any shortcuts or relying on your own knowledge.\nEven if the user's request is already clear or explicit, don't make any assumptions and use the agents.\nBe sure to query ALL necessary agents.\n\nThe comma separated list of available agents is: '{{agents}}'.\n\n{{supervisorContext}}\n")
    @UserMessage("The user request is: '{{request}}'.\nThe last received response is: '{{lastResponse}}'.\n")
    AgentInvocation plan(@MemoryId Object userId, @V("agents") String agents, @V("request") String request, @V("lastResponse") String lastResponse, @V("supervisorContext") String supervisorContext);
}
