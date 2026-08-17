package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.stats.Band;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.util.List;
import java.util.Map;

/**
 * Decides promotion from a candidate's comparisons across every scenario.
 *
 * <p>This is the gate slice 6 left as a seam. It is deliberately not a threshold on a number: a
 * candidate is promoted on the strength of its {@link Band}s, which already carry the interval and
 * the practical floor, so the rule here is about combining scenarios rather than about statistics.
 *
 * <p><strong>Margin-of-error candidates are promoted.</strong> That looks wrong and is required: a
 * mod whose benefit only appears in combination with another presents as no-measurable-difference
 * on its own, and a gate that demanded a clear win would drop it before its partner ever arrived.
 * The stopping rule is a regression, not an absence of gain.
 */
public final class BandGate implements Selection.Gate {

    private final Map<String, List<Comparison>> byCandidate;

    /** @param comparisons every scenario's result for every candidate this round */
    public BandGate(Map<String, List<Comparison>> comparisons) {
        this.byCandidate = Map.copyOf(comparisons);
    }

    @Override
    public Selection.Verdict judge(Bundle bundle) {
        List<Comparison> comparisons = byCandidate.get(bundle.candidate());
        if (comparisons == null || comparisons.isEmpty()) {
            return Selection.Verdict.BLOCKED;
        }

        // A regression anywhere blocks promotion, even alongside gains elsewhere. Scenarios are
        // not interchangeable -- a mod that helps chunk loading and hurts steady render is a
        // trade, and averaging the two would hide the trade rather than present it.
        if (comparisons.stream().anyMatch(c -> c.band() == Band.REGRESSED)) {
            return Selection.Verdict.REGRESSED;
        }
        if (comparisons.stream().anyMatch(c -> c.band() == Band.IMPROVED)) {
            return Selection.Verdict.PROMOTED;
        }
        // Everything is negligible or unmeasurable. Promoted anyway, because a combination-only
        // benefit is indistinguishable from this until its partner is present.
        return Selection.Verdict.PROMOTED;
    }

    /**
     * The score a stack is ordered by.
     *
     * <p>Weighted by scenario so a scenario nobody cares much about cannot outvote one they do. It
     * is deliberately separate from promotion order: promoting the biggest improver first makes
     * completion-target scenarios finish faster and the whole run cheaper, but
     * <strong>runtime must never influence which stack is recommended</strong>, so the two orders
     * are computed from different things.
     */
    public static double weightedScore(List<Comparison> comparisons, Map<String, Double> weights) {
        if (comparisons.isEmpty()) {
            throw new HarnessException("cannot score a candidate with no comparisons");
        }
        double total = 0;
        double weight = 0;
        for (Comparison comparison : comparisons) {
            double scenarioWeight = weights.getOrDefault(comparison.scenarioId(), 1.0);
            total += comparison.improvementPercent() * scenarioWeight;
            weight += scenarioWeight;
        }
        return weight == 0 ? 0 : total / weight;
    }
}
