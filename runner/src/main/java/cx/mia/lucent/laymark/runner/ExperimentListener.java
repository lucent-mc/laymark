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

    /**
     * One scenario's numbers, streamed the moment its capture closed — mid-arm, hours before the
     * arm's result file. The result file stays authoritative; this is the same extraction
     * ({@code Channels}) arriving early.
     */
    record LiveSample(
            String scenarioId,
            int repetition,
            boolean warm,
            double scoredMillis,
            Double mspt,
            Double fps,
            Double msPerChunk,
            Double heapUsedMegabytes) {
        public LiveSample(
                String scenarioId,
                int repetition,
                boolean warm,
                double scoredMillis,
                Double mspt,
                Double fps,
                Double msPerChunk) {
            this(scenarioId, repetition, warm, scoredMillis, mspt, fps, msPerChunk, null);
        }
    }

    default void scenarioMeasured(LiveSample sample) {}

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
     * @param scenarios per-scenario aggregates so far, by scenario id, in scenario order
     */
    record Preliminary(
            String id,
            double improvementPercent,
            Double msptDelta,
            Double fpsDelta,
            Double heapUsedMegabytesDelta,
            java.util.Map<String, PreliminaryScenario> scenarios) {
        public Preliminary(
                String id,
                double improvementPercent,
                Double msptDelta,
                Double fpsDelta,
                java.util.Map<String, PreliminaryScenario> scenarios) {
            this(id, improvementPercent, msptDelta, fpsDelta, null, scenarios);
        }
    }

    /**
     * One scenario's running aggregate for one candidate: the scored percent and every metric
     * channel, plus how many arms fed each side — the count is what says how preliminary this is.
     */
    record PreliminaryScenario(
            double improvementPercent,
            Double msptDelta,
            Double fpsDelta,
            Double msPerChunkDelta,
            Double heapUsedMegabytesDelta,
            int candidateArms,
            int baselineArms) {
        public PreliminaryScenario(
                double improvementPercent,
                Double msptDelta,
                Double fpsDelta,
                Double msPerChunkDelta,
                int candidateArms,
                int baselineArms) {
            this(
                    improvementPercent,
                    msptDelta,
                    fpsDelta,
                    msPerChunkDelta,
                    null,
                    candidateArms,
                    baselineArms);
        }
    }

    default void preliminaryScore(Preliminary preliminary) {}

    /** One scenario's metric channels for one candidate, as candidate-mean minus baseline-mean. */
    record ScenarioChannels(
            Double msptDelta,
            Double fpsDelta,
            Double msPerChunkDelta,
            Double heapUsedMegabytesDelta) {
        public ScenarioChannels(
                Double msptDelta, Double fpsDelta, Double msPerChunkDelta) {
            this(msptDelta, fpsDelta, msPerChunkDelta, null);
        }
    }

    /**
     * One candidate's round in summary: the score a card leads with, the metric deltas beneath it,
     * and the verdict that decided its fate.
     *
     * <p>Deltas are candidate-mean minus baseline-mean for this round, null where a channel could
     * not be measured. Time per chunk is <strong>not</strong> among them: it is only comparable
     * within a scenario — generating a chunk and loading one differ by an order of magnitude —
     * so it lives on {@link ScenarioChannels} and in the per-scenario columns instead.
     * {@code vsOriginalPercent} is null in round 1, where the current baseline <em>is</em> the
     * original.
     */
    record CandidateScore(
            String id,
            double score,
            Double msptDelta,
            Double fpsDelta,
            Double heapUsedMegabytesDelta,
            Double vsOriginalPercent,
            String verdict,
            String detail,
            java.util.Map<String, ScenarioChannels> scenarioChannels) {

        public CandidateScore {
            scenarioChannels =
                    scenarioChannels == null ? java.util.Map.of() : Map.copyOf(scenarioChannels);
        }

        public CandidateScore(
                String id,
                double score,
                Double msptDelta,
                Double fpsDelta,
                Double vsOriginalPercent,
                String verdict,
                String detail,
                java.util.Map<String, ScenarioChannels> scenarioChannels) {
            this(
                    id,
                    score,
                    msptDelta,
                    fpsDelta,
                    null,
                    vsOriginalPercent,
                    verdict,
                    detail,
                    scenarioChannels);
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
            if (heapUsedMegabytesDelta != null) {
                text.append(
                        String.format(
                                java.util.Locale.ROOT,
                                "  heap %+.0f MiB",
                                heapUsedMegabytesDelta));
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
