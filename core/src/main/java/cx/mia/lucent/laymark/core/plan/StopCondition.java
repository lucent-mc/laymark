package cx.mia.lucent.laymark.core.plan;

import java.time.Duration;

/**
 * How a scenario ends — and therefore which of its channels is the <em>scored</em> metric.
 *
 * <p>Every scenario records the same channel set regardless; the stop condition selects only
 * the primary metric. A three-day run must never end with the realisation that the interesting
 * channel was not captured.
 */
public sealed interface StopCondition {

    /**
     * Run for a fixed wall-clock duration. Both arms observe identical windows, so frame
     * distributions are directly comparable and are the scored metric.
     */
    record FixedDuration(long millis) implements StopCondition {
        public FixedDuration {
            if (millis <= 0) {
                throw new IllegalArgumentException("duration must be positive, got " + millis);
            }
        }

        public Duration duration() {
            return Duration.ofMillis(millis);
        }
    }

    /**
     * Run until a target is reached, for example all chunks loaded.
     *
     * <p>Completion time is the scored metric. These produce <strong>variable-length
     * captures</strong> — 120s on a baseline, perhaps 40s on a good stack — so the arms yield
     * different frame-sample counts. A percentile from fewer samples is noisier rather than
     * biased, so frame distributions are still recorded, but they are diagnostic here. The
     * duration-independent comparable quantity is time per unit of work.
     *
     * @param timeoutMillis a variable stop condition needs a ceiling: a mod that breaks chunk
     *     loading must fail the run rather than hang forever.
     */
    record UntilComplete(String target, long timeoutMillis) implements StopCondition {
        public UntilComplete {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("completion target must be named");
            }
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException(
                        "a completion target needs a timeout, or a broken stack hangs the run");
            }
        }
    }
}
