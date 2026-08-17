package cx.mia.lucent.laymark.core.harness;

/**
 * One frame, timed.
 *
 * <p>Frame <em>time</em>, not frame rate. Averaging rates hides stutter, because the frames that
 * ruin a session are individually slow and individually rare — they move a mean by almost nothing
 * and a high percentile by a lot. Samples are kept individually and summarised later so the tail
 * survives to the report.
 *
 * @param offsetNanos since capture began; keeps samples orderable without a wall clock
 */
public record FrameSample(long offsetNanos, long durationNanos) {

    public FrameSample {
        if (offsetNanos < 0) {
            throw new HarnessException("frame offset must not be negative, got " + offsetNanos);
        }
        if (durationNanos <= 0) {
            // A zero-length frame means the timer did not advance between flips, which is a
            // measurement failure rather than an extraordinarily fast frame.
            throw new HarnessException("frame duration must be positive, got " + durationNanos);
        }
    }

    public double millis() {
        return durationNanos / 1_000_000d;
    }
}
