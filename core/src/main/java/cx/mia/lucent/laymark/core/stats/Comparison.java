package cx.mia.lucent.laymark.core.stats;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayList;
import java.util.List;

/**
 * One candidate measured against one baseline, on one scenario.
 *
 * <p>Paired, because back-to-back arms share thermal state, driver state and page cache. The
 * difference between a candidate run and the baseline runs bracketing it is far better behaved
 * than either measurement on its own, and pairing does most of the work that would otherwise need
 * many more repetitions to buy.
 *
 * <p>Lower is better throughout: the scored metric is a duration.
 *
 * @param changePercent negative means faster. The point estimate.
 * @param lowPercent lower end of the 95% interval; also negative-is-faster
 * @param pairs how many candidate runs contributed, which is what the interval width rests on
 * @param voided comparisons excluded because their runs were not comparable, kept as a count so a
 *     reader can see the experiment was smaller than it looks
 */
public record Comparison(
        String candidateId,
        String scenarioId,
        Band band,
        double changePercent,
        double lowPercent,
        double highPercent,
        int pairs,
        int voided,
        double floorPercent) {

    /**
     * Practical floor below which a real difference is still not worth acting on.
     *
     * <p><strong>A placeholder, explicitly.</strong> It stands in until measured spread from the
     * machine profile replaces it, and it is stated as a guess rather than presented as a finding.
     */
    public static final double DEFAULT_FLOOR_PERCENT = 3.0;

    /** One run's scored number, with enough context to pair it. */
    public record Run(String armId, int sequence, double scoredMillis, boolean comparable) {

        public Run {
            if (scoredMillis <= 0) {
                throw new HarnessException("a run's scored metric must be positive");
            }
        }
    }

    /**
     * Pairs each candidate run with the baseline runs bracketing it, then tests the differences.
     *
     * <p>Bracketing rather than a global baseline mean: the baseline drifts over hours, and a
     * candidate run late in a schedule should be judged against the machine as it was then. Using
     * the whole baseline would let slow drift read as a candidate effect.
     *
     * @throws HarnessException if fewer than two usable pairs remain, since an interval cannot be
     *     formed from one observation and reporting a bare point estimate would imply certainty
     *     nobody has
     */
    public static Comparison of(
            String candidateId,
            String scenarioId,
            List<Run> baseline,
            List<Run> candidate,
            double floorPercent) {
        return of(candidateId, scenarioId, baseline, candidate, floorPercent, null);
    }

    /**
     * @param profile widens the interval to at least this machine's remembered wobble for the
     *     scenario; null skips it. Widen-only, so a stale profile can only make the verdict more
     *     cautious — the failure a benchmark should prefer.
     */
    public static Comparison of(
            String candidateId,
            String scenarioId,
            List<Run> baseline,
            List<Run> candidate,
            double floorPercent,
            MachineProfile profile) {

        List<Double> differences = new ArrayList<>();
        int voided = 0;

        for (Run run : candidate) {
            if (!run.comparable()) {
                voided++;
                continue;
            }
            Double reference = bracketingBaseline(baseline, run.sequence());
            if (reference == null) {
                voided++;
                continue;
            }
            // Percent change against the local baseline, so every pair is on one scale and the
            // mean of them is meaningful across scenarios with different absolute frame times.
            differences.add((run.scoredMillis() - reference) / reference * 100.0);
        }

        if (differences.size() < 2) {
            throw new HarnessException(
                    "comparison of " + candidateId + " on " + scenarioId + " has only "
                            + differences.size()
                            + " usable pair(s); an interval needs at least two");
        }

        double mean = differences.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double variance =
                differences.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum()
                        / (differences.size() - 1);
        double standardError = Math.sqrt(variance / differences.size());
        double margin = StudentT.criticalValue(differences.size() - 1) * standardError;
        if (profile != null) {
            margin = profile.widen(scenarioId, margin);
        }

        double low = mean - margin;
        double high = mean + margin;
        return new Comparison(
                candidateId,
                scenarioId,
                band(low, high, floorPercent),
                mean,
                low,
                high,
                differences.size(),
                voided,
                floorPercent);
    }

    /**
     * The baseline level around a point in the schedule.
     *
     * <p>Averages the nearest baseline before and after where both exist, so a candidate sitting
     * between two drift checks is judged against the interval it actually ran in.
     */
    private static Double bracketingBaseline(List<Run> baseline, int sequence) {
        Double before = null;
        Double after = null;
        int bestBefore = Integer.MIN_VALUE;
        int bestAfter = Integer.MAX_VALUE;

        for (Run run : baseline) {
            if (!run.comparable()) {
                continue;
            }
            if (run.sequence() <= sequence && run.sequence() > bestBefore) {
                bestBefore = run.sequence();
                before = run.scoredMillis();
            }
            if (run.sequence() > sequence && run.sequence() < bestAfter) {
                bestAfter = run.sequence();
                after = run.scoredMillis();
            }
        }
        if (before != null && after != null) {
            return (before + after) / 2;
        }
        return before != null ? before : after;
    }

    /**
     * An interval spanning zero is "cannot tell", not "the same".
     *
     * <p>The floor is applied only once the difference is real. A large but uncertain difference is
     * not negligible — it is unmeasured, and calling it negligible would report a stronger
     * conclusion than the data supports.
     */
    private static Band band(double low, double high, double floorPercent) {
        if (low <= 0 && high >= 0) {
            return Band.NO_MEASURABLE_DIFFERENCE;
        }
        if (high < 0) {
            return Math.abs(high) >= floorPercent ? Band.IMPROVED : Band.NEGLIGIBLE;
        }
        return Band.REGRESSED;
    }

    /** Improvement as a positive number, for prose that says "3% faster". */
    public double improvementPercent() {
        return -changePercent;
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT, 
                "%s: %.1f%% (interval %.1f%% to %.1f%%, n=%d)",
                band, improvementPercent(), -highPercent, -lowPercent, pairs);
    }
}
