package cx.mia.lucent.laymark.core.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves {@code dependsOn} into a deterministic execution order.
 *
 * <p>Pure policy: no Minecraft, no loader, no I/O. This is the shape most of {@code core} should
 * take, because it means the fast test tier can cover it in milliseconds.
 *
 * <p>Order is <strong>stable</strong>: among scenarios whose dependencies are equally satisfied,
 * declaration order wins. That matters more than it looks. Array position is part of a
 * scenario's identity — scenario 1 runs against a colder JVM than scenario 5 — so an ordering
 * that varied between runs would silently make two arms incomparable.
 *
 * <p>Stability is why this is a breadth-first Kahn traversal keyed on declaration index rather
 * than the shorter depth-first post-order. Depth-first is equally deterministic but not stable:
 * it finishes the first declaration's whole subtree before looking at the second, so a scenario
 * that was runnable from the start can land behind one declared after it.
 */
public final class ScenarioOrder {

    private ScenarioOrder() {}

    /**
     * @throws PlanException if an id is duplicated, a dependency is missing, or the graph
     *     contains a cycle
     */
    public static List<ScenarioSpec> resolve(List<ScenarioSpec> scenarios) {
        Map<String, Integer> declarationIndex = new LinkedHashMap<>();
        for (int i = 0; i < scenarios.size(); i++) {
            String id = scenarios.get(i).id();
            if (declarationIndex.putIfAbsent(id, i) != null) {
                throw new PlanException("duplicate scenario id: " + id);
            }
        }
        requireDependenciesPresent(scenarios, declarationIndex.keySet());

        // Unmet dependency count per scenario, and the reverse edges that decrement it.
        Map<String, Integer> unmet = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (ScenarioSpec s : scenarios) {
            Set<String> deps = new LinkedHashSet<>(s.dependsOn());
            unmet.put(s.id(), deps.size());
            for (String dep : deps) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.id());
            }
        }

        // The ready set is keyed on declaration index, so the earliest-declared runnable
        // scenario is always the one emitted next.
        NavigableSet<Integer> ready = new TreeSet<>();
        unmet.forEach(
                (id, count) -> {
                    if (count == 0) {
                        ready.add(declarationIndex.get(id));
                    }
                });

        List<ScenarioSpec> ordered = new ArrayList<>(scenarios.size());
        while (!ready.isEmpty()) {
            ScenarioSpec next = scenarios.get(ready.pollFirst());
            ordered.add(next);
            for (String dependent : dependents.getOrDefault(next.id(), List.of())) {
                if (unmet.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(declarationIndex.get(dependent));
                }
            }
        }

        if (ordered.size() != scenarios.size()) {
            Set<String> unresolved = new LinkedHashSet<>();
            unmet.forEach(
                    (id, count) -> {
                        if (count > 0) {
                            unresolved.add(id);
                        }
                    });
            throw new PlanException("dependency cycle: " + describeCycle(unresolved, scenarios));
        }
        return List.copyOf(ordered);
    }

    private static void requireDependenciesPresent(List<ScenarioSpec> scenarios, Set<String> ids) {
        List<String> missing = new ArrayList<>();
        for (ScenarioSpec s : scenarios) {
            for (String dep : s.dependsOn()) {
                if (!ids.contains(dep)) {
                    missing.add(s.id() + " depends on unknown scenario " + dep);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new PlanException(
                    "unresolved dependencies: " + String.join("; ", missing)
                            + ". A scenario that reuses another's world cannot run standalone,"
                            + " so a subset must be dependency-closed.");
        }
    }

    /**
     * Names the cycle itself, not the acyclic run-up that reached it — an edge outside the cycle
     * is not the edge to break, so reporting it sends plan repair at the wrong scenario.
     *
     * <p>Every unresolved scenario has at least one unresolved dependency, which is what left it
     * unresolved, so following that edge from any of them revisits a scenario within a finite
     * number of steps. The path from that revisited id onwards is the cycle.
     */
    private static String describeCycle(Set<String> unresolved, List<ScenarioSpec> scenarios) {
        Map<String, ScenarioSpec> byId = new LinkedHashMap<>();
        scenarios.forEach(s -> byId.put(s.id(), s));

        List<String> path = new ArrayList<>();
        Set<String> onPath = new LinkedHashSet<>();
        String at = unresolved.iterator().next();
        while (onPath.add(at)) {
            path.add(at);
            at =
                    byId.get(at).dependsOn().stream()
                            .filter(unresolved::contains)
                            .findFirst()
                            .orElseThrow();
        }
        List<String> cycle = new ArrayList<>(path.subList(path.indexOf(at), path.size()));
        cycle.add(at);
        return String.join(" -> ", cycle);
    }
}
