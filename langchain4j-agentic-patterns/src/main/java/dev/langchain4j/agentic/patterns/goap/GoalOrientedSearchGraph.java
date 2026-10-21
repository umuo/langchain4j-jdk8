package dev.langchain4j.agentic.patterns.goap;

import dev.langchain4j.agentic.patterns.goap.DependencyGraphSearch.Node;
import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.AgentInstance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoalOrientedSearchGraph {

    private static final Logger LOG = LoggerFactory.getLogger(GoalOrientedSearchGraph.class);

    private static final class NodePair {
        private final Node input;
        public Node input() { return input; }
        private final Node output;
        public Node output() { return output; }
        NodePair(Node input, Node output) {
            this.input = input;
            this.output = output;
        }
        @Override public final boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || other.getClass() != getClass()) return false;
            NodePair value = (NodePair) other;
            return java.util.Objects.equals(input, value.input) && java.util.Objects.equals(output, value.output);
        }
        @Override public final int hashCode() {
            int result = 0;
            result = 31 * result + java.util.Objects.hashCode(input);
            result = 31 * result + java.util.Objects.hashCode(output);
            return result;
        }
        @Override public final String toString() { return "NodePair[" + "input=" + input + ", " + "output=" + output + "]"; }
    }

    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<NodePair, AgentInstance> edges = new HashMap<>();

    public GoalOrientedSearchGraph(List<AgentInstance> agents) {
        init(agents);
    }

    private void init(List<AgentInstance> agents) {
        for (AgentInstance agent : agents) {
            List<Node> inputs = agent.arguments().stream()
                    .map(AgentArgument::name)
                    .map(arg -> nodes.computeIfAbsent(arg, Node::new))
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
            Node output = nodes.computeIfAbsent(agent.outputKey(), Node::new);

            inputs.forEach(input -> {
                input.addOutput(output);
                edges.put(new NodePair(input, output), agent);
            });
        }
    }

    public List<AgentInstance> search(Collection<String> preconditions, String goal) {
        Node goalNode = nodes.get(goal);
        if (goalNode == null) {
            // no agent produces the goal
            return dev.langchain4j.internal.Jdk8.list();
        }
        List<Node> initialNodes = preconditions.stream().map(nodes::get).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
        List<Node> nodesPath = DependencyGraphSearch.search(goalNode, initialNodes);

        if (nodesPath == null) {
            return dev.langchain4j.internal.Jdk8.list();
        }

        int preconditionsCounter = 0;
        List<AgentInstance> agentsPath = new ArrayList<>();
        for (int i = 1; i < nodesPath.size(); i++) {
            Node output = nodesPath.get(i);
            if (preconditions.contains(output.getId())) {
                preconditionsCounter++;
                continue;
            }
            for (int j = i - 1; j >= 0; j--) {
                AgentInstance agent = edges.get(new NodePair(nodesPath.get(j), output));
                if (agent != null) {
                    agentsPath.add(agent);
                    break;
                }
            }
            if (agentsPath.size() != i - preconditionsCounter) {
                throw new IllegalStateException("No path found for node: " + output.getId());
            }
        }

        List<String> pathNames = agentsPath.stream().map(AgentInstance::name)
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
        LOG.info("Agents path sequence: {}", pathNames);

        return agentsPath;
    }
}
