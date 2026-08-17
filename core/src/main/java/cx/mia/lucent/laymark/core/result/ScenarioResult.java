package cx.mia.lucent.laymark.core.result;

import cx.mia.lucent.laymark.core.harness.BarrierReport;
import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Measurement;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import java.util.List;

// Pass and arrayPosition are identity (§8.2): scenario 1 runs against a colder JVM than scenario
// 5, and the warm pass against a warmer one than the cold, so a result that did not say which it
// was could be pooled with one it must never be pooled with.

/**
 * What one repetition of one scenario produced.
 *
 * <p>A failed repetition is still a result. Dropping it would silently bias the run: the arms most
 * likely to fail are the ones under the most stress, so surviving repetitions would be
 * disproportionately the easy ones.
 *
 * @param flags contamination the run detected but chose not to fail on — a setting a mod reverted,
 *     an environmental deviation. Present so a reader can discount a number rather than trust it
 *     blindly.
 * @param segments one entry per phase the scenario asked to measure, in the order it named
 *     them, each with its own channel set retained in full. The summaries are derived, and a
 *     derived number cannot be re-derived differently later if only the summary was kept -- which
 *     matters because the right way to summarise a distribution is a question this project expects
 *     to keep revisiting.
 */
public record ScenarioResult(
        String scenarioId,
        int repetition,
        Pass pass,
        int arrayPosition,
        Outcome outcome,
        String failureReason,
        PresetReadback readback,
        List<String> flags,
        List<PhaseResult> segments,
        BarrierReport barrier,
        long durationMillis) {

    public enum Outcome {
        /** Ran to its stop condition with every precondition met. */
        COMPLETED,
        /** Ran, but something happened that a reader must weigh. See {@link #flags}. */
        COMPLETED_WITH_FLAGS,
        /** Did not produce a usable measurement. */
        FAILED
    }

    public ScenarioResult {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new HarnessException("result has no scenario id");
        }
        // Cold rather than null on old documents: absent means "from before passes existed",
        // and those results were all first traversals.
        pass = pass == null ? Pass.COLD : pass;
        if (outcome == null) {
            throw new HarnessException("result for " + scenarioId + " has no outcome");
        }
        if (outcome == Outcome.FAILED && (failureReason == null || failureReason.isBlank())) {
            throw new HarnessException("failed result for " + scenarioId + " has no reason");
        }
        flags = flags == null ? List.of() : List.copyOf(flags);
        segments = segments == null ? List.of() : List.copyOf(segments);

        barrier = barrier == null ? BarrierReport.none() : barrier;
        if (outcome == Outcome.COMPLETED_WITH_FLAGS
                && flags.isEmpty()
                && segments.stream().allMatch(segment -> segment.flags().isEmpty())) {
            throw new HarnessException("flagged result for " + scenarioId + " carries no flags");
        }
    }

    public static ScenarioResult completed(
            String scenarioId,
            int repetition,
            Pass pass,
            int arrayPosition,
            PresetReadback readback,
            List<String> flags,
            List<PhaseResult> segments,
            BarrierReport barrier,
            long durationMillis) {
        // Segment flags count towards the outcome. A throttle during one phase is recorded against
        // that phase, because it says nothing about the others -- but a reader scanning outcomes
        // still has to see that something about this repetition needs weighing.
        boolean flagged =
                (flags != null && !flags.isEmpty())
                        || (segments != null
                                && segments.stream().anyMatch(s -> !s.flags().isEmpty()));
        Outcome outcome = flagged ? Outcome.COMPLETED_WITH_FLAGS : Outcome.COMPLETED;
        return new ScenarioResult(
                scenarioId, repetition, pass, arrayPosition, outcome, null, readback, flags,
                segments, barrier, durationMillis);
    }

    public static ScenarioResult failed(
            String scenarioId, int repetition, Pass pass, int arrayPosition, String reason) {
        return new ScenarioResult(
                scenarioId,
                repetition,
                pass,
                arrayPosition,
                Outcome.FAILED,
                reason,
                null,
                List.of(),
                List.of(),
                BarrierReport.none(),
                0);
    }

    public boolean measured() {
        return outcome != Outcome.FAILED
                && !segments.isEmpty()
                && segments.stream().allMatch(PhaseResult::measured);
    }

    /** @throws HarnessException if the scenario did not measure that phase */
    public PhaseResult segment(cx.mia.lucent.laymark.core.Phase phase) {
        return segments.stream()
                .filter(segment -> segment.phase() == phase)
                .findFirst()
                .orElseThrow(
                        () ->
                                new HarnessException(
                                        scenarioId + " did not measure " + phase));
    }

    /**
     * The scored distribution of the last phase measured.
     *
     * @throws HarnessException if nothing was measured
     */
    public FrameStatistics statistics() {
        if (segments.isEmpty()) {
            throw new HarnessException(scenarioId + " measured nothing");
        }
        return segments.get(segments.size() - 1).statistics();
    }
}
