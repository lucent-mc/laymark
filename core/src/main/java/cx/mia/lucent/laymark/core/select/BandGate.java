package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.stats.Band;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.util.List;
import java.util.Map;

/**
 * Decides promotion from the same composite score the round is ranked by.
 *
 * <p><strong>One number, one rule: top score wins.</strong> The score already prices every trade
 * the run measured — scenario costs weight the percentages, {@code scoreWeights} balances speed
 * against memory — so a second veto on top of it would double-count the same evidence and let a
 * side-channel overrule the number the operator tuned. A regression is <em>in</em> the score, at
 * the price the config gave it, and a candidate whose gains outweigh a regression wins with it.
 *
 * <p>What the gate still owes: a candidate with no comparisons cannot be ranked (BLOCKED), and a
 * candidate whose net score is not a gain must not be promoted — that is the stopping rule, since
 * a lap where nothing scores positive has nothing left worth adding. The verdict then says why:
 * REGRESSED where something was measurably worse, INCONCLUSIVE where nothing was.
 */
public final class BandGate implements Selection.Gate {

    private final Map<String, List<Comparison>> byCandidate;
    private final Map<String, Double> scoreByCandidate;

    /**
     * @param comparisons every scenario's result for every candidate this round
     * @param scores each candidate's composite score — the same number the ranking uses, because
     *     a gate judging by a different number than the ranking is how a +11 beats a +34
     */
    public BandGate(Map<String, List<Comparison>> comparisons, Map<String, Double> scores) {
        this.byCandidate = Map.copyOf(comparisons);
        this.scoreByCandidate = Map.copyOf(scores);
    }

    @Override
    public Selection.Verdict judge(Bundle bundle) {
        List<Comparison> comparisons = byCandidate.get(bundle.candidate());
        if (comparisons == null || comparisons.isEmpty()) {
            return Selection.Verdict.BLOCKED;
        }
        if (scoreByCandidate.getOrDefault(bundle.candidate(), 0.0) > 0) {
            return Selection.Verdict.PROMOTED;
        }
        // No net gain. The distinction between the two losing verdicts is honesty about why:
        // "regressed" claims something was measurably worse, and may only be said of a candidate
        // with a regressed band in evidence.
        if (comparisons.stream().anyMatch(c -> c.band() == Band.REGRESSED)) {
            return Selection.Verdict.REGRESSED;
        }
        return Selection.Verdict.INCONCLUSIVE;
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
