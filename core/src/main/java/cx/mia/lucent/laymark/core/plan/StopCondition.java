package cx.mia.lucent.laymark.core.plan;

import java.time.Duration;

/**
 * How a scenario's capture ends — and therefore which channel is its <em>scored</em> metric.
 *
 * <p>One shape for every kind: count something until it reaches {@code target}, giving up at
 * {@code timeoutMillis}. What is being counted is the only thing that varies, which is why this is
 * a flat record rather than a hierarchy — a sealed type here would need a discriminator, an
 * adapter and a registry to express a difference that is really just a unit.
 *
 * <p>Every scenario records the same channel set regardless. The stop condition selects only the
 * primary metric; everything else is still captured, because a three-day run must never end with
 * the realisation that the interesting channel was not the scored one.
 *
 * <p><strong>A target defines how much work an arm does, so it must define the same work for
 * both.</strong> That is why there is no "until it stops making progress" kind: a struggling stack
 * would plateau early, complete having done less, and post a flattering number — bias that
 * correlates with the thing under test and that repetition cannot remove.
 *
 * @param target what to count up to; the unit is decided by {@link #kind}
 * @param timeoutMillis wall-clock ceiling. A completion target needs one, or a mod that breaks
 *     chunk loading hangs the run forever.
 */
public record StopCondition(Kind kind, long target, long timeoutMillis) {

    /** How much longer than its target a duration capture is allowed to take before it is wrong. */
    private static final long TIME_TIMEOUT_FACTOR = 2;

    public enum Kind {
        /** {@code target} is milliseconds of capture. Both arms observe identical windows. */
        TIME,

        /**
         * {@code target} is chunks received by the client during the capture.
         *
         * <p>Produces <strong>variable-length captures</strong> — 120s on a baseline, perhaps 40s
         * on a good stack — so the arms yield different frame-sample counts. A percentile from
         * fewer samples is noisier rather than biased, so frame distributions are still recorded
         * but are diagnostic here. The comparable quantity is time per chunk.
         */
        CHUNKS,

        /**
         * {@code target} is frames presented during the capture.
         *
         * <p>Equalises sample count across arms, which makes tail percentiles directly comparable
         * — p999 from 14,000 samples and from 20,000 are differently noisy. The cost is unequal
         * wall-clock: the faster arm finishes sooner and is exposed to less thermal drift.
         */
        FRAMES
    }

    public StopCondition {
        if (kind == null) {
            throw new PlanException("stop condition has no kind");
        }
        if (target <= 0) {
            throw new PlanException("stop condition target must be positive, got " + target);
        }
        if (timeoutMillis <= 0) {
            if (kind != Kind.TIME) {
                // Only a duration capture can be trusted to finish on its own; everything else
                // depends on the game making progress, which is exactly what may have broken.
                throw new PlanException(
                        "a " + kind + " target needs a timeout, or a broken stack hangs the run");
            }
            timeoutMillis = target * TIME_TIMEOUT_FACTOR;
        }
        if (timeoutMillis < target && kind == Kind.TIME) {
            throw new PlanException(
                    "a time target of " + target + "ms cannot complete within a " + timeoutMillis
                            + "ms timeout");
        }
    }

    public static StopCondition time(Duration duration) {
        return new StopCondition(Kind.TIME, duration.toMillis(), 0);
    }

    public static StopCondition chunks(long count, Duration timeout) {
        return new StopCondition(Kind.CHUNKS, count, timeout.toMillis());
    }

    public static StopCondition frames(long count, Duration timeout) {
        return new StopCondition(Kind.FRAMES, count, timeout.toMillis());
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMillis);
    }

    /** @throws PlanException if this is not a duration capture */
    public Duration duration() {
        if (kind != Kind.TIME) {
            throw new PlanException(kind + " is not a duration");
        }
        return Duration.ofMillis(target);
    }
}
