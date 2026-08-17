package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Splits a selection when two candidates cannot both be promoted.
 *
 * <p>Two mods that conflict have no single answer — there are two stacks, and which is better is a
 * measurement, not a preference. Branching carries both and reports both rather than picking one,
 * because picking would be Laymark making the decision the operator is supposed to make.
 *
 * <p>This does not explode. Branches share their prefix, and the bound is the number of conflict
 * <em>clusters</em> rather than the number of candidates: five mods that all conflict with each
 * other are one cluster and five branches, not thirty-two.
 *
 * <p>The projected run count is reported before a run starts, because a selection that branches
 * into a schedule three times longer than expected is something an operator has to agree to
 * beforehand rather than discover at hour six.
 */
public final class Branching {

    private Branching() {}

    /**
     * A pair that cannot be loaded together.
     *
     * <p>Symmetric: a conflict is a property of the pair, not a direction.
     */
    public record Conflict(String a, String b) {

        public Conflict {
            if (a == null || b == null || a.equals(b)) {
                throw new HarnessException("a conflict needs two distinct mods");
            }
        }

        public boolean involves(String mod) {
            return a.equals(mod) || b.equals(mod);
        }
    }

    /**
     * Groups conflicts into clusters of mutually incompatible mods.
     *
     * <p>Transitively: if A conflicts with B and B with C, all three are one cluster even where A
     * and C would load together. That is deliberately conservative — the alternative is enumerating
     * every independent set, which is where the combinatorics actually live.
     */
    public static List<Set<String>> clusters(List<Conflict> conflicts) {
        List<Set<String>> clusters = new ArrayList<>();
        for (Conflict conflict : conflicts) {
            Set<String> merged = new TreeSet<>(List.of(conflict.a(), conflict.b()));
            clusters.removeIf(
                    existing -> {
                        if (existing.contains(conflict.a()) || existing.contains(conflict.b())) {
                            merged.addAll(existing);
                            return true;
                        }
                        return false;
                    });
            clusters.add(merged);
        }
        return clusters;
    }

    /**
     * The candidate pools each branch explores.
     *
     * <p>One branch per member of each cluster, carrying that member and dropping its rivals.
     * Everything outside any cluster is in every branch, which is what keeps branches sharing a
     * prefix instead of duplicating the whole selection.
     */
    public static List<List<String>> branch(List<String> pool, List<Conflict> conflicts) {
        List<Set<String>> clusters = clusters(conflicts);
        List<List<String>> branches = new ArrayList<>();
        branches.add(new ArrayList<>(pool));

        for (Set<String> cluster : clusters) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> branch : branches) {
                for (String keep : cluster) {
                    if (!branch.contains(keep)) {
                        continue;
                    }
                    List<String> forked = new ArrayList<>(branch);
                    forked.removeIf(mod -> cluster.contains(mod) && !mod.equals(keep));
                    next.add(forked);
                }
            }
            branches = next.isEmpty() ? branches : next;
        }
        return dedupe(branches);
    }

    private static List<List<String>> dedupe(List<List<String>> branches) {
        Set<List<String>> seen = new LinkedHashSet<>(branches);
        return new ArrayList<>(seen);
    }

    /**
     * Runs a whole selection would cost, to show an operator before it starts.
     *
     * @param runsPerRound what the schedule costs for one branch's pool
     */
    public static int projectedRuns(List<List<String>> branches, int runsPerRound) {
        return branches.size() * runsPerRound;
    }
}
