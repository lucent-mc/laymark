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
 * @param clientChunksReceived pose-local chunks present when a CHUNKS target completed. Unlike
 *     global client-cache occupancy, this does not lose arrivals when old chunks unload.
 */
public record Measurement(
        List<FrameSample> frames,
        List<GpuSample> gpu,
        SparkStatistics spark,
        WorkCounters workBefore,
        WorkCounters workAfter,
        MemorySnapshot memoryBefore,
        MemorySnapshot memoryAfter,
        Long clientChunksReceived) {

    public Measurement {
        frames = frames == null ? List.of() : List.copyOf(frames);
        gpu = gpu == null ? List.of() : List.copyOf(gpu);
    }

    public static Measurement empty() {
        return new Measurement(List.of(), List.of(), null, null, null, null, null, null);
    }

    /**
     * This measurement without its raw series, for the on-disk copy once the samples have been
     * written beside it. The scalar channels — Spark, work counters, memory — are already
     * summaries and stay.
     */
    public Measurement withoutSamples() {
        return new Measurement(
                List.of(), List.of(), spark, workBefore, workAfter, memoryBefore, memoryAfter,
                clientChunksReceived);
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
        if (frames.isEmpty()) {
            return null;
        }
        Long received = clientChunksReceived;
        if (received == null) {
            // Compatibility for measurements written before the pose-local completion counter
            // was retained. New captures never use global occupancy as an arrival count.
            WorkCounters delta = work();
            received = delta == null ? null : (long) delta.clientChunks();
        }
        if (received == null || received <= 0) {
            return null;
        }
        long total = frames.stream().mapToLong(FrameSample::intervalNanos).sum();
        return total / 1_000_000d / received;
    }

    /** Source-compatible shape from before pose-local chunk completion was retained. */
    public Measurement(
            List<FrameSample> frames,
            List<GpuSample> gpu,
            SparkStatistics spark,
            WorkCounters workBefore,
            WorkCounters workAfter,
            MemorySnapshot memoryBefore,
            MemorySnapshot memoryAfter) {
        this(frames, gpu, spark, workBefore, workAfter, memoryBefore, memoryAfter, null);
    }

    /**
     * How long the capture window actually was, derived from the frames it caught.
     *
     * <p>Derived rather than recorded because it has to be the span the samples came from, not the
     * span someone intended. A frame- or chunk-targeted capture ends when its target is met, so
     * its length is only known afterwards.
     */
    public long captureMillis() {
        if (frames.isEmpty()) {
            return 0;
        }
        FrameSample last = frames.get(frames.size() - 1);
        return (last.offsetNanos() + last.intervalNanos()) / 1_000_000L;
    }

    /**
     * Whether Spark's rolling window is wider than the capture it is reported against.
     *
     * <p>When it is, the server statistics include time from before the capture — world creation
     * and the previous scenario's teardown — and describe something other than the scenario they
     * are printed beneath. Observed on a real run: a 4.7s capture reported a 10s window, and its
     * server mean read twice that of an otherwise identical 10s capture.
     */
    public boolean serverWindowOverrunsCapture() {
        return spark != null && captureMillis() > 0 && spark.windowMillis() > captureMillis();
    }

    /** Every distinct reason the framerate was held back, in the order first seen. */
    public List<Throttle> throttlesObserved() {
        return frames.stream().map(FrameSample::throttle).distinct().toList();
    }

    public boolean measured() {
        return !frames.isEmpty();
    }
}
