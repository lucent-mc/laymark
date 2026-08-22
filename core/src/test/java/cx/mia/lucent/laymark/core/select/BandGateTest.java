package cx.mia.lucent.laymark.core.select;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cx.mia.lucent.laymark.core.stats.Comparison;
import cx.mia.lucent.laymark.core.plan.ScoreWeights;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BandGateTest {

    @Test
    void scoreWeightsPercentChangesByTheBaselineCostTheyActOn() {
        Comparison expensive = comparison("expensive", 10.0, 9.0);
        Comparison nearlyFree = comparison("nearly-free", 0.001, 0.003);

        // Saving 1 ms on the expensive scenario matters far more than adding 0.002 ms to the
        // cheap one. Equal-weighting their +10% and -200% changes would report -95%.
        assertEquals(
                9.979,
                BandGate.weightedScore(List.of(expensive, nearlyFree), Map.of()),
                0.001);
    }

    @Test
    void compositeKeepsSpeedAndMemoryAsSeparatelyNormalizedObjectives() {
        Comparison speed = comparison("world", 10.0, 9.0);
        Comparison memory =
                Comparison.of(
                        "candidate",
                        "world",
                        Comparison.Metric.MEMORY,
                        List.of(
                                new Comparison.Run("baseline", 0, 2000, true),
                                new Comparison.Run("baseline", 3, 2000, true)),
                        List.of(
                                new Comparison.Run("candidate", 1, 1600, true),
                                new Comparison.Run("candidate", 2, 1600, true)),
                        Comparison.DEFAULT_FLOOR_PERCENT);

        assertEquals(
                15.0,
                CompositeScore.of(
                        List.of(speed),
                        List.of(memory),
                        Map.of("world", 1.0),
                        new ScoreWeights(1, 1)),
                0.001);
    }

    @Test
    void topScoreWinsEvenOverARegressedBand() {
        // A big win on one scenario, a real regression on another: the composite score prices
        // the trade, so a net gain is promotable -- the gate must not veto what the score says.
        Comparison bigWin = comparison("chunk-generation", 13.0, 4.4);
        Comparison smallLoss = comparison("chunk-loading", 1.5, 1.65);
        assertEquals(
                cx.mia.lucent.laymark.core.stats.Band.REGRESSED,
                smallLoss.band(),
                "the loss must be a real regression for this test to mean anything");

        BandGate gate =
                new BandGate(
                        Map.of("traded.jar", List.of(bigWin, smallLoss)),
                        Map.of("traded.jar", 55.0));
        assertEquals(
                Selection.Verdict.PROMOTED,
                gate.judge(new Bundle("traded.jar", java.util.Set.of("traded.jar"))));
    }

    @Test
    void aNetLossIsNotPromotedAndTheVerdictSaysWhy() {
        Comparison loss = comparison("chunk-loading", 1.5, 1.8);
        BandGate regressed =
                new BandGate(Map.of("worse.jar", List.of(loss)), Map.of("worse.jar", -20.0));
        assertEquals(
                Selection.Verdict.REGRESSED,
                regressed.judge(new Bundle("worse.jar", java.util.Set.of("worse.jar"))));

        // A net non-gain with nothing measurably worse is inconclusive, not "regressed" --
        // that word claims evidence. One run above the baseline and one below spans zero.
        Comparison noise =
                Comparison.of(
                        "candidate",
                        "chunk-loading",
                        List.of(
                                new Comparison.Run("baseline", 0, 1.5, true),
                                new Comparison.Run("baseline", 3, 1.5, true)),
                        List.of(
                                new Comparison.Run("candidate", 1, 1.52, true),
                                new Comparison.Run("candidate", 2, 1.48, true)),
                        Comparison.DEFAULT_FLOOR_PERCENT);
        assertEquals(
                cx.mia.lucent.laymark.core.stats.Band.NO_MEASURABLE_DIFFERENCE, noise.band());
        BandGate inconclusive =
                new BandGate(Map.of("meh.jar", List.of(noise)), Map.of("meh.jar", 0.0));
        assertEquals(
                Selection.Verdict.INCONCLUSIVE,
                inconclusive.judge(new Bundle("meh.jar", java.util.Set.of("meh.jar"))));
    }

    private static Comparison comparison(String scenario, double baseline, double candidate) {
        return Comparison.of(
                "candidate",
                scenario,
                List.of(
                        new Comparison.Run("baseline", 0, baseline, true),
                        new Comparison.Run("baseline", 3, baseline, true)),
                List.of(
                        new Comparison.Run("candidate", 1, candidate, true),
                        new Comparison.Run("candidate", 2, candidate, true)),
                Comparison.DEFAULT_FLOOR_PERCENT);
    }
}
