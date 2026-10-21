package dev.langchain4j.agentic.patterns.goap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Modified A* Search for Dependency Graphs
 * <p>
 * Handles nodes with multiple input dependencies where ALL inputs must be
 * satisfied before a node can be activated/traversed.
 */
public class DependencyGraphSearch {

    /**
     * Represents a node with multiple inputs and a single output
     */
    public static class Node {
        private final String id;
        private final Set<Node> inputNodes = new HashSet<>();      // Required input nodes
        private final List<Node> outputNodes = new ArrayList<>();  // Nodes this feeds into

        public Node(String id) {
            this.id = id;
        }

        private void addInput(Node input) {
            inputNodes.add(input);
        }

        public void addOutput(Node output) {
            outputNodes.add(output);
            output.addInput(this);  // Automatically set up bidirectional relationship
        }

        public Set<Node> getInputNodes() {
            return inputNodes;
        }

        public List<Node> getOutputNodes() {
            return outputNodes;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node)) return false;
            final Node node = (Node) o;
            return id.equals(node.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * Represents the state of the search: which nodes have been activated
     */
    static final class SearchState {
        private final Set<Node> activatedNodes;
        public Set<Node> activatedNodes() { return activatedNodes; }
        private final Node currentNode;
        public Node currentNode() { return currentNode; }
        private final int depth;
        public int depth() { return depth; }
        SearchState(Set<Node> activatedNodes, Node currentNode, int depth) {
            this.activatedNodes = activatedNodes;
            this.currentNode = currentNode;
            this.depth = depth;
        }
        @Override public final boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || other.getClass() != getClass()) return false;
            SearchState value = (SearchState) other;
            return java.util.Objects.equals(activatedNodes, value.activatedNodes) && java.util.Objects.equals(currentNode, value.currentNode) && java.util.Objects.equals(depth, value.depth);
        }
        @Override public final int hashCode() {
            int result = 0;
            result = 31 * result + java.util.Objects.hashCode(activatedNodes);
            result = 31 * result + java.util.Objects.hashCode(currentNode);
            result = 31 * result + java.util.Objects.hashCode(depth);
            return result;
        }
        @Override public final String toString() { return "SearchState[" + "activatedNodes=" + activatedNodes + ", " + "currentNode=" + currentNode + ", " + "depth=" + depth + "]"; }

        SearchState activateNode(Node node) {
            Set<Node> newActivated = new HashSet<>(activatedNodes);
            newActivated.add(node);
            return new SearchState(newActivated, node, depth + 1);
        }

        boolean canActivate(Node node) {
            // A node can be activated if all its input dependencies are satisfied
            return activatedNodes.containsAll(node.getInputNodes());
        }
    }

    /**
     * Wrapper for priority queue with f-score
     */
    static final class StateScore implements Comparable<StateScore> {
        private final SearchState state;
        public SearchState state() { return state; }
        private final double fScore;
        public double fScore() { return fScore; }
        StateScore(SearchState state, double fScore) {
            this.state = state;
            this.fScore = fScore;
        }
        @Override public final boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || other.getClass() != getClass()) return false;
            StateScore value = (StateScore) other;
            return java.util.Objects.equals(state, value.state) && java.util.Objects.equals(fScore, value.fScore);
        }
        @Override public final int hashCode() {
            int result = 0;
            result = 31 * result + java.util.Objects.hashCode(state);
            result = 31 * result + java.util.Objects.hashCode(fScore);
            return result;
        }
        @Override public final String toString() { return "StateScore[" + "state=" + state + ", " + "fScore=" + fScore + "]"; }


        @Override
        public int compareTo(StateScore other) {
            return Double.compare(this.fScore, other.fScore);
        }
    }

    /**
     * Heuristic function for estimating remaining cost
     */
    interface Heuristic {
        double estimate(SearchState state, Node goal);
    }

    public static List<Node> search(Node goal, Node... preconditions) {
        return search(goal, Stream.of(preconditions).collect(Collectors.toSet()));
    }

    public static List<Node> search(Node goal, Collection<Node> preconditions) {
        // Simple heuristic: number of unsatisfied dependencies
        Heuristic heuristic = (state, goalNode) -> {
            if (state.activatedNodes.contains(goalNode)) {
                return 0.0;
            }
            // Estimate remaining nodes to activate
            Set<Node> remaining = new HashSet<>();
            Queue<Node> toCheck = new LinkedList<>();
            toCheck.add(goalNode);

            while (!toCheck.isEmpty()) {
                Node node = toCheck.poll();
                if (!state.activatedNodes.contains(node)) {
                    remaining.add(node);
                    toCheck.addAll(node.getInputNodes());
                }
            }

            return remaining.size();
        };
        return search(preconditions, goal, heuristic);
    }

    /**
     * Finds shortest path considering dependency constraints
     *
     * @param startNodes Set of nodes that are already active (preconditions)
     * @param goal The goal node
     * @param heuristic Heuristic function for A*
     * @return List of nodes in activation order, or null if no path exists
     */
    private static List<Node> search(Collection<Node> startNodes, Node goal, Heuristic heuristic) {
        if (startNodes == null || startNodes.isEmpty()) {
            throw new IllegalArgumentException("Must provide at least one start node");
        }

        // Initial state: all start nodes are already activated
        Set<Node> initialActivated = new HashSet<>(startNodes);
        // Use first start node as current (arbitrary choice since all are active)
        SearchState initialState = new SearchState(initialActivated, startNodes.iterator().next(), 0);

        PriorityQueue<StateScore> openSet = new PriorityQueue<>();
        Set<SearchState> visited = new HashSet<>();
        Map<SearchState, SearchState> cameFrom = new HashMap<>();
        Map<SearchState, Double> gScore = new HashMap<>();

        gScore.put(initialState, 0.0);
        openSet.add(new StateScore(initialState, heuristic.estimate(initialState, goal)));

        while (!openSet.isEmpty()) {
            SearchState current = openSet.poll().state;

            if (visited.contains(current)) {
                continue;
            }
            visited.add(current);

            // Check if goal is reached
            if (current.currentNode.equals(goal)) {
                return reconstructPath(cameFrom, current);
            }

            double currentGScore = gScore.get(current);

            // Explore all nodes that can now be activated
            for (Node nextNode : findActivatableNodes(current)) {
                SearchState nextState = current.activateNode(nextNode);

                if (visited.contains(nextState)) {
                    continue;
                }

                double tentativeGScore = currentGScore + 1.0; // Cost of activating one node

                if (tentativeGScore < gScore.getOrDefault(nextState, Double.POSITIVE_INFINITY)) {
                    cameFrom.put(nextState, current);
                    gScore.put(nextState, tentativeGScore);
                    double fScore = tentativeGScore + heuristic.estimate(nextState, goal);
                    openSet.add(new StateScore(nextState, fScore));
                }
            }
        }

        return null; // No path found
    }

    /**
     * Finds all nodes that can be activated given the current state
     */
    private static Set<Node> findActivatableNodes(SearchState state) {
        Set<Node> activatable = new HashSet<>();

        // Check all output nodes of already activated nodes
        for (Node activatedNode : state.activatedNodes) {
            for (Node outputNode : activatedNode.getOutputNodes()) {
                if (!state.activatedNodes.contains(outputNode) && state.canActivate(outputNode)) {
                    activatable.add(outputNode);
                }
            }
        }

        return activatable;
    }

    /**
     * Reconstructs the path by following cameFrom references
     */
    private static List<Node> reconstructPath(Map<SearchState, SearchState> cameFrom, SearchState current) {
        List<Node> path = new ArrayList<>();

        // Collect all states in reverse order
        List<SearchState> states = new ArrayList<>();
        states.add(current);

        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            states.add(0, current);
        }

        // Extract the sequence of activated nodes
        Set<Node> previouslyActivated = new HashSet<>();
        for (SearchState state : states) {
            Set<Node> newNodes = new HashSet<>(state.activatedNodes);
            newNodes.removeAll(previouslyActivated);
            path.addAll(newNodes);
            previouslyActivated = state.activatedNodes;
        }

        return path;
    }
}
