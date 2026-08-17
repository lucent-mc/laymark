package cx.mia.lucent.laymark.core.harness;

import java.util.List;

/**
 * Everything one capture window recorded.
 *
 * <p><strong>Every scenario records every channel</strong>, whatever its stop condition. The stop
 * condition selects only which channel is <em>scored</em>; the rest are diagnostic and reported
 * alongside. A three-day run must never end with the realisation that the interesting channel was
 * not captured, and re-running to collect one is not a neutral act — it happens on a different
 * machine state than the run it would be compared against.
 *
 * <p>Channels that a host cannot supply are empty rather than absent or zeroed. GPU timer queries
 * are the realistic case: a backend or driver may not support them, and an empty series says
 * "not measured" where a series of zeros would say "instant".
 *
 * @param frames per-frame CPU timings; the headline distribution lives here
 * @param gpu GPU execution time per frame, resolved several frames after the fact because reading
 *     a timer query eagerly means waiting for the GPU, which would change what is being measured
 * @param spark server health as Spark measures it — the channel that catches a mod costing
 *     nothing to draw and a great deal to simulate. Null when Spark is not installed, which is
 *     recorded as a run-level flag rather than treated as a failure.
 */
public record Measurement(
        List<FrameSample> frames,
        List<GpuSample> gpu,
        SparkStatistics spark,
        WorkCounters workBefore,
        WorkCounters workAfter,
        MemorySnapshot memoryBefore,
        MemorySnapshot memoryAfter) {

    public Measurement {
        frames = frames == null ? List.of() : List.copyOf(frames);
        gpu = gpu == null ? List.of() : List.copyOf(gpu);
    }

    public static Measurement empty() {
        return new Measurement(List.of(), List.of(), null, null, null, null, null);
    }

    /** The scored distribution. */
    public FrameStatistics frameStatistics() {
        return FrameStatistics.of(frames, TimingChannel.INTERVAL);
    }

    public FrameStatistics frameStatistics(TimingChannel channel) {
        return FrameStatistics.of(frames, channel);
    }

    /** @throws HarnessException if the GPU channel was unavailable */
    public FrameStatistics gpuStatistics() {
        return FrameStatistics.from(gpu, GpuSample::durationNanos);
    }

    /** Work completed during the window, or null when the counters were not both read. */
    public WorkCounters work() {
        return workBefore == null || workAfter == null ? null : workAfter.minus(workBefore);
    }

    /**
     * Milliseconds of client work per chunk the client received.
     *
     * <p>The duration-independent quantity. Two arms that load chunks at different speeds observe
     * windows of different length, so their frame counts are not comparable — but the cost of a
     * unit of work is, which is what makes a completion-targeted scenario scoreable at all.
     *
     * @return null when no chunks arrived, which is the normal case for a resident-render scenario
     */
    public Double millisPerChunkReceived() {
        WorkCounters delta = work();
        if (delta == null || delta.clientChunks() <= 0 || frames.isEmpty()) {
            return null;
        }
        long total = frames.stream().mapToLong(FrameSample::intervalNanos).sum();
        return total / 1_000_000d / delta.clientChunks();
    }

    /** Every distinct reason the framerate was held back, in the order first seen. */
    public List<Throttle> throttlesObserved() {
        return frames.stream().map(FrameSample::throttle).distinct().toList();
    }

    public boolean measured() {
        return !frames.isEmpty();
    }
}
