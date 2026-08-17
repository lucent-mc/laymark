package cx.mia.lucent.laymark.core.result;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Measurement;

/**
 * One measured phase within a repetition.
 *
 * <p>A scenario measures exactly the phases it named, in the order it named them, and each gets
 * its own capture window and its own channel set. Nothing is measured implicitly — a scenario that
 * did not ask for spawn generation does not get a spawn-generation number, even though the world
 * still had to be created.
 *
 * @param flags anything about <em>this</em> phase a reader has to weigh, kept per phase because a
 *     throttle during traversal says nothing about the render segment that followed it
 */
public record PhaseResult(
        Phase phase, Measurement measurement, java.util.List<String> flags, long durationMillis) {

    public PhaseResult {
        if (phase == null) {
            throw new HarnessException("a phase result has no phase");
        }
        measurement = measurement == null ? Measurement.empty() : measurement;
        flags = flags == null ? java.util.List.of() : java.util.List.copyOf(flags);
    }

    public boolean measured() {
        return measurement.measured();
    }

    /** @throws HarnessException if this phase captured nothing */
    public FrameStatistics statistics() {
        return measurement.frameStatistics();
    }
}
