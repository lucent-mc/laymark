package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.util.List;
import java.util.Map;

/**
 * Decides eligibility; the composite score decides everything else.
 *
 * <p><strong>One number, one rule: top score wins — and Laymark never decides for the
 * operator.</strong> The score prices every trade the run measured, ranks the lap, and its winner
 * is promoted whatever the sign, because a lap whose best candidate scores −0.1 is still a
 * measurement worth having: the run walks the whole pool, the report's cumulative column shows
 * exactly where returns turned negative, and where to stop the stack is the operator's call. A
 * benchmark that halted itself on a low number would be making that call for them.
 *
 * <p>All the gate refuses is the unmeasurable: a candidate with no comparisons cannot be ranked,
 * and pretending otherwise would put an unmeasured mod into a measured ordering.
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
        return comparisons == null || comparisons.isEmpty()
                ? Selection.Verdict.BLOCKED
                : Selection.Verdict.PROMOTED;
    }

    /**
     * The score a stack is ordered by.
     *
     * <p>Each percentage is weighted by the paired baseline cost it acts on. A 200% change to
     * 0.001 ms of work is a 0.002 ms change, and must not outvote a 10% change to 10 ms of work.
     * Operator scenario weights remain multipliers on that cost rather than replacing it.
     */
    public static double weightedScore(List<Comparison> comparisons, Map<String, Double> weights) {
        if (comparisons.isEmpty()) {
            throw new HarnessException("cannot score a candidate with no comparisons");
        }
        double total = 0;
        double weight = 0;
        for (Comparison comparison : comparisons) {
            double scenarioWeight = weights.getOrDefault(comparison.scenarioId(), 1.0);
            double costWeight = scenarioWeight * comparison.baselineValue();
            total += comparison.improvementPercent() * costWeight;
            weight += costWeight;
        }
        return weight == 0 ? 0 : total / weight;
    }
}
