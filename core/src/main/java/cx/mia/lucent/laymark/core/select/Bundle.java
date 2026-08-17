package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * A candidate and the dependencies only it brings.
 *
 * <p>A candidate is tested as an atomic bundle because it cannot be loaded without what it
 * requires. Measuring "mod X" when X drags in a library is really measuring X plus the library,
 * and pretending otherwise attributes the library's cost to X.
 *
 * <p><strong>Exclusive</strong> is the operative word: the bundle is X's transitive requirements
 * minus everything already guaranteed present. That set shrinks as the baseline grows, so bundles
 * are recomputed every round — if X and Y both need L and X is promoted, Y's bundle becomes Y
 * alone. A report has to be able to say that Y's score moved because its bundle shrank rather than
 * because Y interacts with X, which is why {@link #members} is recorded per round rather than
 * derived once.
 *
 * @param candidate the mod actually under test
 * @param members everything that loads for this arm and would not otherwise, candidate included
 */
public record Bundle(String candidate, Set<String> members) {

    public Bundle {
        if (candidate == null || candidate.isBlank()) {
            throw new HarnessException("a bundle has no candidate");
        }
        members = new TreeSet<>(members);
        if (!members.contains(candidate)) {
            throw new HarnessException(
                    "bundle for " + candidate + " does not contain the candidate itself");
        }
    }

    /**
     * Builds the bundle for one candidate against what is already present.
     *
     * @param guaranteed mods that will load regardless of this candidate — the baseline floor plus
     *     anything already promoted
     */
    public static Bundle of(String candidate, DependencyGraph graph, Set<String> guaranteed) {
        Set<String> members = new LinkedHashSet<>();
        members.add(candidate);
        for (String required : graph.closureOf(candidate)) {
            if (!guaranteed.contains(required)) {
                members.add(required);
            }
        }
        return new Bundle(candidate, members);
    }

    /** Everything the candidate drags in with it. */
    public Set<String> exclusiveDependencies() {
        Set<String> dependencies = new TreeSet<>(members);
        dependencies.remove(candidate);
        return dependencies;
    }

    public boolean isAtomic() {
        return members.size() == 1;
    }
}
