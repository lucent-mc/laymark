package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.experiment.Arm;
import cx.mia.lucent.laymark.core.report.SelectionReport;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.util.List;

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

    default void runStarted(int sequence, Arm arm) {}

    /** The arm currently in flight has begun this scenario. */
    default void scenarioStarted(String scenarioId, int repetition) {}

    /** @param scoredMillis the run's scored metric, averaged across scenarios; 0 when it failed */
    default void runFinished(int sequence, Arm arm, double scoredMillis, boolean failed) {}

    /**
     * One selection round has concluded.
     *
     * @param baselineLabel what this round's candidates were measured against
     * @param promoted the winner, joining the baseline for the next round; null when none
     */
    default void roundCompleted(
            int round, String baselineLabel, List<Comparison> comparisons, String promoted) {}

    /** "running", "paused", "stopping" — for a status line, not for logic. */
    default void stateChanged(String state) {}

    /** @param report null when the run ended before a report could be built */
    default void finished(SelectionReport report) {}

    static ExperimentListener none() {
        return new ExperimentListener() {};
    }
}
