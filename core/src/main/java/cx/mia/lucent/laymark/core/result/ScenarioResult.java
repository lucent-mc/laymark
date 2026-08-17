package cx.mia.lucent.laymark.core.result;

import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import java.util.List;

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
 * @param samples retained in full; the summary is derived, and derived numbers cannot be
 *     re-derived differently later if only the summary was kept
 */
public record ScenarioResult(
        String scenarioId,
        int repetition,
        Outcome outcome,
        String failureReason,
        PresetReadback readback,
        List<String> flags,
        List<FrameSample> samples,
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
        if (outcome == null) {
            throw new HarnessException("result for " + scenarioId + " has no outcome");
        }
        if (outcome == Outcome.FAILED && (failureReason == null || failureReason.isBlank())) {
            throw new HarnessException("failed result for " + scenarioId + " has no reason");
        }
        flags = flags == null ? List.of() : List.copyOf(flags);
        samples = samples == null ? List.of() : List.copyOf(samples);
        if (outcome == Outcome.COMPLETED_WITH_FLAGS && flags.isEmpty()) {
            throw new HarnessException("flagged result for " + scenarioId + " carries no flags");
        }
    }

    public static ScenarioResult completed(
            String scenarioId,
            int repetition,
            PresetReadback readback,
            List<String> flags,
            List<FrameSample> samples,
            long durationMillis) {
        Outcome outcome =
                flags == null || flags.isEmpty() ? Outcome.COMPLETED : Outcome.COMPLETED_WITH_FLAGS;
        return new ScenarioResult(
                scenarioId, repetition, outcome, null, readback, flags, samples, durationMillis);
    }

    public static ScenarioResult failed(String scenarioId, int repetition, String reason) {
        return new ScenarioResult(
                scenarioId, repetition, Outcome.FAILED, reason, null, List.of(), List.of(), 0);
    }

    public boolean measured() {
        return outcome != Outcome.FAILED && !samples.isEmpty();
    }

    /** @throws HarnessException if nothing was measured */
    public FrameStatistics statistics() {
        return FrameStatistics.of(samples);
    }
}
