package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Greedy forward selection: the baseline grows, and nothing is ever eliminated.
 *
 * <p>Forward rather than backward because the question is "what should be added", and a candidate
 * that loses a round may win a later one once the baseline it is measured against has changed.
 * Nothing is eliminated for the same reason — a mod that does nothing on top of an empty baseline
 * may do a great deal on top of a heavier one, and a benchmark that dropped it would never find
 * out.
 *
 * <p><strong>Laymark does not accept or reject mods.</strong> It promotes what its gates say
 * improved things and reports everything, regressions included. A regression is a result, not a
 * verdict: a mod may be wanted for reasons no benchmark can see.
 *
 * <p>Bundles are recomputed every round against the grown baseline, so a candidate's cost falls as
 * its dependencies are promoted out from under it.
 */
public final class Selection {

    /** What one round decided about one candidate. */
    public record Outcome(Bundle bundle, Verdict verdict, String detail) {

        public Outcome {
            if (bundle == null || verdict == null) {
                throw new HarnessException("an outcome needs a bundle and a verdict");
            }
        }
    }

    public enum Verdict {
        /** Passed the gates; its members join the baseline for later rounds. */
        PROMOTED,
        /** Measurably worse. Reported, never rejected — the decision is the operator's. */
        REGRESSED,
        /** No difference the gates would call real. Stays in the pool for a later round. */
        INCONCLUSIVE,
        /** Could not be measured against this baseline; see {@link Outcome#detail}. */
        BLOCKED
    }

    /**
     * Decides whether a bundle earned promotion.
     *
     * <p>An interface rather than a threshold, because "is this difference real" is a statistical
     * question and the answer belongs with the confidence machinery, not here. This class owns the
     * <em>order</em> candidates are considered in and what promotion does to the baseline; it does
     * not own what counts as better.
     */
    @FunctionalInterface
    public interface Gate {
        Verdict judge(Bundle bundle);
    }

    private final DependencyGraph graph;
    private final Set<String> baseline;
    private final Set<String> promoted = new LinkedHashSet<>();

    /**
     * @param baseline the floor every arm loads, before any promotion
     */
    public Selection(DependencyGraph graph, Set<String> baseline) {
        this.graph = graph == null ? DependencyGraph.empty() : graph;
        this.baseline = new TreeSet<>(baseline == null ? Set.of() : baseline);
    }

    /** Everything guaranteed present right now: the floor plus whatever has been promoted. */
    public Set<String> guaranteed() {
        Set<String> all = new TreeSet<>(baseline);
        all.addAll(promoted);
        return all;
    }

    public Set<String> promoted() {
        return new TreeSet<>(promoted);
    }

    /**
     * Builds this round's bundles for the candidates still in the pool.
     *
     * <p>Recomputed rather than cached: the point is that they shrink.
     */
    public List<Bundle> bundlesFor(List<String> pool) {
        Set<String> guaranteed = guaranteed();
        List<Bundle> bundles = new ArrayList<>();
        for (String candidate : pool) {
            if (guaranteed.contains(candidate)) {
                continue; // already in, directly or as somebody's dependency
            }
            bundles.add(Bundle.of(candidate, graph, guaranteed));
        }
        return bundles;
    }

    /**
     * Runs one round and returns what it decided, in the order candidates were considered.
     *
     * <p>Promotions take effect <strong>after</strong> the whole round, not during it. Applying
     * them as they happen would mean a candidate judged later in the round was measured against a
     * different baseline than one judged earlier, and the two results would not be comparable
     * despite appearing side by side in the same round.
     */
    public List<Outcome> round(List<String> pool, Gate gate, Ranking ranking) {
        List<Outcome> judged = new ArrayList<>();
        for (Bundle bundle : bundlesFor(pool)) {
            Verdict verdict = gate.judge(bundle);
            judged.add(new Outcome(bundle, verdict, detailFor(bundle, verdict)));
        }

        // Exactly one promotion per round, the best of those that qualified. Promoting everything
        // that merely failed to regress would collapse greedy forward selection into a single
        // round -- and the whole point is that each promotion changes the baseline the next
        // candidate is measured against.
        Outcome best = null;
        for (Outcome outcome : judged) {
            if (outcome.verdict() != Verdict.PROMOTED) {
                continue;
            }
            if (best == null
                    || ranking.score(outcome.bundle()) > ranking.score(best.bundle())) {
                best = outcome;
            }
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (Outcome outcome : judged) {
            boolean winner = outcome == best;
            outcomes.add(
                    winner
                            ? outcome
                            : new Outcome(
                                    outcome.bundle(),
                                    outcome.verdict() == Verdict.PROMOTED
                                            ? Verdict.INCONCLUSIVE
                                            : outcome.verdict(),
                                    outcome.verdict() == Verdict.PROMOTED
                                            ? "qualified but was not the best this round"
                                            : outcome.detail()));
        }
        if (best != null) {
            promoted.addAll(best.bundle().members());
        }
        return outcomes;
    }

    /**
     * Orders the candidates that qualified, so a round can promote the single best.
     *
     * <p>Separate from the {@link Gate} because qualifying and winning are different questions,
     * and separate from runtime ordering because <strong>runtime must never influence which stack
     * is recommended</strong>.
     */
    @FunctionalInterface
    public interface Ranking {
        double score(Bundle bundle);
    }

    private static String detailFor(Bundle bundle, Verdict verdict) {
        if (verdict != Verdict.PROMOTED || bundle.isAtomic()) {
            return "";
        }
        // Said explicitly because the alternative is a reader assuming a bundle's gain belongs to
        // its candidate, when part of it may belong to what the candidate dragged in.
        return "promoted with " + bundle.exclusiveDependencies();
    }

    /**
     * Candidates that cannot be measured because something already present requires them.
     *
     * <p>An inherited mod requiring a named candidate means the off-arm cannot exist: disabling
     * the candidate would break the thing that needs it, so there is nothing to compare against.
     * That fails the run with the explanation rather than silently testing a different question.
     */
    public Set<String> unmeasurable(List<String> pool) {
        Set<String> blocked = new TreeSet<>();
        for (String candidate : pool) {
            for (String present : baseline) {
                if (graph.closureOf(present).contains(candidate)) {
                    blocked.add(candidate);
                }
            }
        }
        return blocked;
    }
}
