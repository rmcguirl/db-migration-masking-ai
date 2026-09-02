package com.migration.referential;

import com.migration.domain.TableRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adjacency-map-backed {@link DependencyGraph}. Self-loops (a table's FK referencing
 * itself, e.g. {@code employee.manager_id -> employee.id}) are never represented as
 * edges here — a self-reference doesn't constrain ordering between two different tables,
 * so it's handled without special-casing per design doc §5, simply by never appearing in
 * {@code childrenOf}/{@code parentsOf}.
 */
final class DefaultDependencyGraph implements DependencyGraph {

    private static final Comparator<TableRef> BY_QUALIFIED_NAME = Comparator.comparing(TableRef::qualifiedName);

    private final Set<TableRef> nodes;
    private final Map<TableRef, Set<TableRef>> childrenOf;
    private final Map<TableRef, Set<TableRef>> parentsOf;

    DefaultDependencyGraph(Set<TableRef> nodes, Map<TableRef, Set<TableRef>> childrenOf,
                            Map<TableRef, Set<TableRef>> parentsOf) {
        this.nodes = nodes;
        this.childrenOf = childrenOf;
        this.parentsOf = parentsOf;
    }

    @Override
    public List<TableRef> topologicalLoadOrder() {
        List<TableRef> order = new ArrayList<>();
        loadLayers().forEach(order::addAll);
        return order;
    }

    @Override
    public List<List<TableRef>> loadLayers() {
        Map<TableRef, Integer> remainingInDegree = new HashMap<>();
        for (TableRef node : nodes) {
            remainingInDegree.put(node, parentsOf.getOrDefault(node, Set.of()).size());
        }

        List<List<TableRef>> layers = new ArrayList<>();
        Set<TableRef> processed = new HashSet<>();
        while (processed.size() < nodes.size()) {
            List<TableRef> layer = remainingInDegree.entrySet().stream()
                    .filter(e -> !processed.contains(e.getKey()) && e.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .sorted(BY_QUALIFIED_NAME)
                    .toList();
            if (layer.isEmpty()) {
                // Every remaining node is stuck in a cycle (in-degree never reaches 0 among
                // the remaining set) — surface them as one final, deterministically-ordered
                // layer rather than looping forever. Cycle resolution/ordering within this
                // layer is CycleLoadStrategyResolver's job, not the graph's.
                List<TableRef> remaining = nodes.stream()
                        .filter(n -> !processed.contains(n))
                        .sorted(BY_QUALIFIED_NAME)
                        .toList();
                layers.add(remaining);
                break;
            }
            layers.add(layer);
            processed.addAll(layer);
            for (TableRef finished : layer) {
                for (TableRef child : childrenOf.getOrDefault(finished, Set.of())) {
                    remainingInDegree.merge(child, -1, Integer::sum);
                }
            }
        }
        return layers;
    }

    @Override
    public List<List<TableRef>> detectCycles() {
        return tarjanStronglyConnectedComponents().stream()
                .filter(scc -> scc.size() > 1)
                .toList();
    }

    @Override
    public Set<TableRef> transitiveDependents(TableRef table) {
        Set<TableRef> visited = new LinkedHashSet<>();
        Deque<TableRef> queue = new ArrayDeque<>(childrenOf.getOrDefault(table, Set.of()));
        while (!queue.isEmpty()) {
            TableRef next = queue.poll();
            if (visited.add(next)) {
                queue.addAll(childrenOf.getOrDefault(next, Set.of()));
            }
        }
        return visited;
    }

    private List<TableRef> sortedChildren(TableRef node) {
        return childrenOf.getOrDefault(node, Set.of()).stream().sorted(BY_QUALIFIED_NAME).toList();
    }

    /**
     * Tarjan's strongly-connected-components algorithm, iterative (an explicit work-stack
     * simulating the recursive call stack, plus a saved position within each node's
     * successor list) to avoid JVM stack-depth limits on large schemas.
     */
    private List<List<TableRef>> tarjanStronglyConnectedComponents() {
        Map<TableRef, Integer> index = new HashMap<>();
        Map<TableRef, Integer> lowLink = new HashMap<>();
        Set<TableRef> onStack = new HashSet<>();
        Deque<TableRef> sccStack = new ArrayDeque<>();
        List<List<TableRef>> result = new ArrayList<>();
        int[] counter = {0};

        List<TableRef> roots = nodes.stream().sorted(BY_QUALIFIED_NAME).toList();
        for (TableRef root : roots) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<TableRef> callStack = new ArrayDeque<>();
            Deque<Iterator<TableRef>> successorIterators = new ArrayDeque<>();

            visit(root, index, lowLink, onStack, sccStack, counter, callStack, successorIterators);

            while (!callStack.isEmpty()) {
                TableRef node = callStack.peek();
                Iterator<TableRef> successors = successorIterators.peek();
                if (successors.hasNext()) {
                    TableRef successor = successors.next();
                    if (!index.containsKey(successor)) {
                        visit(successor, index, lowLink, onStack, sccStack, counter, callStack, successorIterators);
                    } else if (onStack.contains(successor)) {
                        lowLink.put(node, Math.min(lowLink.get(node), index.get(successor)));
                    }
                } else {
                    callStack.pop();
                    successorIterators.pop();
                    if (!callStack.isEmpty()) {
                        TableRef parent = callStack.peek();
                        lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(node)));
                    }
                    if (lowLink.get(node).equals(index.get(node))) {
                        result.add(popComponent(node, sccStack, onStack));
                    }
                }
            }
        }
        return result;
    }

    private void visit(TableRef node, Map<TableRef, Integer> index, Map<TableRef, Integer> lowLink,
                        Set<TableRef> onStack, Deque<TableRef> sccStack, int[] counter,
                        Deque<TableRef> callStack, Deque<Iterator<TableRef>> successorIterators) {
        index.put(node, counter[0]);
        lowLink.put(node, counter[0]);
        counter[0]++;
        sccStack.push(node);
        onStack.add(node);
        callStack.push(node);
        successorIterators.push(sortedChildren(node).iterator());
    }

    private List<TableRef> popComponent(TableRef root, Deque<TableRef> sccStack, Set<TableRef> onStack) {
        List<TableRef> component = new ArrayList<>();
        TableRef member;
        do {
            member = sccStack.pop();
            onStack.remove(member);
            component.add(member);
        } while (!member.equals(root));
        return component;
    }
}
