package cx.mia.lucent.laymark.core.harness;

/**
 * How long the GPU spent on one frame, as the GPU itself reports it.
 *
 * <p>Distinct from the frame interval because the two answer different questions. A GPU-bound
 * stack and a CPU-bound one can post identical intervals; only this channel says which lever to
 * pull, and it is the one number a shader or culling mod is really competing on.
 *
 * <p>A separate series rather than a field on {@link FrameSample} because the value is not
 * available when the frame ends. A timer query resolves some frames later, and waiting for it
 * would stall the pipeline — measuring the measurement instead of the game.
 *
 * @param offsetNanos of the frame this describes, on the same clock as {@link FrameSample}, so the
 *     two series can be aligned despite being recorded at different times
 */
public record GpuSample(long offsetNanos, long durationNanos) {

    public GpuSample {
        if (offsetNanos < 0) {
            throw new HarnessException("gpu sample offset must not be negative");
        }
        if (durationNanos <= 0) {
            throw new HarnessException("gpu duration must be positive, got " + durationNanos);
        }
    }

    public double millis() {
        return durationNanos / 1_000_000d;
    }
}
