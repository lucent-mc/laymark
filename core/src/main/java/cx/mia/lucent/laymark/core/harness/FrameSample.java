package cx.mia.lucent.laymark.core.harness;

/**
 * One frame, timed on every channel the client can report about itself.
 *
 * <p>Frame <em>time</em>, not frame rate. Averaging rates hides stutter, because the frames that
 * ruin a session are individually slow and individually rare — they move a mean by almost nothing
 * and a high percentile by a lot. Samples are kept individually and summarised later so the tail
 * survives to the report.
 *
 * <p>Several channels rather than one because "it got slower" is not a finding until you know
 * where. The interval is what a player feels; the other two say whether the cost moved into
 * rendering, and the throttle says whether the number means anything at all.
 *
 * @param offsetNanos since capture began; keeps samples orderable without a wall clock
 * @param intervalNanos flip to flip — the whole frame, and the only channel scored
 * @param renderCallNanos the render section alone. Excludes the client ticks executed this frame,
 *     sound, toasts, input, the buffer swap and the limiter wait, so it is systematically smaller
 *     than the interval and must never be compared against one.
 * @param submitNanos the render-submission bracket, narrower again than the render call
 */
public record FrameSample(
        long offsetNanos,
        long intervalNanos,
        long renderCallNanos,
        long submitNanos,
        Throttle throttle) {

    public FrameSample {
        if (offsetNanos < 0) {
            throw new HarnessException("frame offset must not be negative, got " + offsetNanos);
        }
        if (intervalNanos <= 0) {
            // A zero-length frame means the timer did not advance between flips, which is a
            // measurement failure rather than an extraordinarily fast frame.
            throw new HarnessException("frame interval must be positive, got " + intervalNanos);
        }
        if (renderCallNanos < 0 || submitNanos < 0) {
            throw new HarnessException("frame sub-channels must not be negative");
        }
        if (throttle == null) {
            throw new HarnessException("frame sample has no throttle state");
        }
    }

    /** A frame with only the headline channel, for tests and for hosts that report nothing else. */
    public static FrameSample interval(long offsetNanos, long intervalNanos) {
        return new FrameSample(offsetNanos, intervalNanos, 0, 0, Throttle.NONE);
    }

    public long nanos(TimingChannel channel) {
        return switch (channel) {
            case INTERVAL -> intervalNanos;
            case RENDER_CALL -> renderCallNanos;
            case SUBMIT -> submitNanos;
        };
    }

    public double millis() {
        return intervalNanos / 1_000_000d;
    }
}
