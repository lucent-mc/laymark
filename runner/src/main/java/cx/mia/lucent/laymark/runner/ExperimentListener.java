package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.experiment.Arm;
import cx.mia.lucent.laymark.core.report.SelectionReport;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.util.List;
import java.util.Map;

/**
 * What an experiment tells whoever is watching it.
 *
 * <p>A seam rather than a GUI dependency: the experiment emits facts, and headless runs attach
 * nobody. Every method defaults to nothing so a listener implements only what it shows.
 *
 * <p>Called from the experiment thread. A GUI implementation marshals to its own thread; the
 * experiment never waits on a listener.
 */
public interface ExperimentListener {

    /**
     * One selection round's arms, plus where that round sits in the whole experiment.
     *
     * @param round 1-based
     * @param rounds how many rounds are planned
     * @param armsTotal every arm the experiment expects to run, across all rounds. An estimate
     *     after round 1, because greedy selection decides later rounds from earlier results — it
     *     drives the progress readout and the time estimate, never a decision.
     */
    record Slate(List<Arm> arms, int round, int rounds, int armsTotal) {}

    /** The round's run order, before anything launches. */
    default void scheduleBuilt(Slate slate) {}

    /**
     * What each mod file is called, for anything showing arms to a human.
     *
     * <p>Arm ids stay file names — they are what moves on disk — and a display surface substitutes
     * these on the way out. Optional and best-effort: a file with no manifest name simply is not
     * here.
     */
    default void named(java.util.Map<String, String> displayNames) {}

    default void runStarted(int sequence, Arm arm) {}

    /** The arm currently in flight has begun this scenario. */
    default void scenarioStarted(String scenarioId, int repetition) {}

    /** @param scoredMillis the run's scored metric, averaged across scenarios; 0 when it failed */
    default void runFinished(int sequence, Arm arm, double scoredMillis, boolean failed) {}

    /**
     * A candidate's standing while the round is still running, every channel included: the
     * aggregate of all this candidate's measured arms so far against the baseline arms measured so
     * far. Positive percent is faster.
     *
     * <p>Point estimates with no interval and no band — preliminary by name and by nature. They
     * exist because the alternative shown mid-round was one opaque percentage, which reads as a
     * result while saying nothing about where it came from. The round's close replaces this with
     * the real comparison.
     *
     * @param msptDelta candidate-mean minus baseline-mean, null where a channel had no data yet
     * @param scenarioPercents per-scenario improvement so far, by scenario id
     */
    record Preliminary(
            String id,
            double improvementPercent,
            Double msptDelta,
            Double fpsDelta,
            Double msPerChunkDelta,
            java.util.Map<String, Double> scenarioPercents) {}

    default void preliminaryScore(Preliminary preliminary) {}

    /**
     * One candidate's round in summary: the score a card leads with, the metric deltas beneath it,
     * and the verdict that decided its fate.
     *
     * <p>Deltas are candidate-mean minus baseline-mean for this round, null where a channel could
     * not be measured. {@code vsOriginalPercent} is null in round 1, where the current baseline
     * <em>is</em> the original.
     */
    /** One scenario's metric channels for one candidate, as candidate-mean minus baseline-mean. */
    record ScenarioChannels(Double msptDelta, Double fpsDelta, Double msPerChunkDelta) {}

    record CandidateScore(
            String id,
            double score,
            Double msptDelta,
            Double fpsDelta,
            Double msPerChunkDelta,
            Double vsOriginalPercent,
            String verdict,
            String detail,
            java.util.Map<String, ScenarioChannels> scenarioChannels) {

        public CandidateScore {
            scenarioChannels =
                    scenarioChannels == null ? java.util.Map.of() : Map.copyOf(scenarioChannels);
        }

        public String describe() {
            StringBuilder text = new StringBuilder(id);
            text.append(String.format(java.util.Locale.ROOT, "  score %+.1f", score));
            if (msptDelta != null) {
                text.append(String.format(java.util.Locale.ROOT, "  mspt %+.1f", msptDelta));
            }
            if (fpsDelta != null) {
                text.append(String.format(java.util.Locale.ROOT, "  fps %+.0f", fpsDelta));
            }
            if (msPerChunkDelta != null) {
                text.append(
                        String.format(java.util.Locale.ROOT, "  ms/chunk %+.2f", msPerChunkDelta));
            }
            if (vsOriginalPercent != null) {
                text.append(
                        String.format(
                                java.util.Locale.ROOT, "  vs original %+.1f%%", vsOriginalPercent));
            }
            text.append("  ").append(verdict);
            return text.toString();
        }
    }

    /**
     * One selection round has concluded.
     *
     * @param baselineLabel what this round's candidates were measured against
     * @param scores one per candidate still in the pool, the card's content
     * @param promoted the winner, joining the baseline for the next round; null when none
     */
    default void roundCompleted(
            int round,
            String baselineLabel,
            List<Comparison> comparisons,
            List<CandidateScore> scores,
            String promoted) {}

    /** "running", "paused", "stopping" — for a status line, not for logic. */
    default void stateChanged(String state) {}

    /** @param report null when the run ended before a report could be built */
    default void finished(SelectionReport report) {}

    static ExperimentListener none() {
        return new ExperimentListener() {};
    }
}
