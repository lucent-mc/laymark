package cx.mia.lucent.laymark.core.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public final class ScenarioOrder {

    private ScenarioOrder() {}

    /**
     * @throws PlanException if a dependency is missing or the graph contains a cycle
     */
    public static List<ScenarioSpec> resolve(List<ScenarioSpec> scenarios) {
        Map<String, ScenarioSpec> byId = new LinkedHashMap<>();
        for (ScenarioSpec s : scenarios) {
            if (byId.putIfAbsent(s.id(), s) != null) {
                throw new PlanException("duplicate scenario id: " + s.id());
            }
        }

        List<String> missing = new ArrayList<>();
        for (ScenarioSpec s : scenarios) {
            for (String dep : s.dependsOn()) {
                if (!byId.containsKey(dep)) {
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

        List<ScenarioSpec> ordered = new ArrayList<>(scenarios.size());
        Set<String> done = new LinkedHashSet<>();
        Set<String> inProgress = new LinkedHashSet<>();

        for (ScenarioSpec s : scenarios) {
            visit(s, byId, done, inProgress, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visit(
            ScenarioSpec spec,
            Map<String, ScenarioSpec> byId,
            Set<String> done,
            Set<String> inProgress,
            List<ScenarioSpec> ordered) {

        if (done.contains(spec.id())) {
            return;
        }
        if (!inProgress.add(spec.id())) {
            Deque<String> cycle = new ArrayDeque<>(inProgress);
            throw new PlanException(
                    "dependency cycle: " + String.join(" -> ", cycle) + " -> " + spec.id());
        }
        for (String dep : spec.dependsOn()) {
            visit(byId.get(dep), byId, done, inProgress, ordered);
        }
        inProgress.remove(spec.id());
        done.add(spec.id());
        ordered.add(spec);
    }
}
