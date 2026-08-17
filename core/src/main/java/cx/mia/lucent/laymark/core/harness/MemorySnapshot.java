package cx.mia.lucent.laymark.core.harness;

/**
 * Heap occupancy, read at each end of a capture.
 *
 * <p>Heap only. Garbage collection is Spark's to report and arrives in {@link SparkStatistics};
 * this exists alongside it because {@code spark-api} exposes no heap figure at all, and reading
 * one from {@code Runtime} is a property of the JVM rather than a reimplementation of anything
 * Spark does.
 *
 * <p>The pairing is what makes either useful. A collection pause lands in exactly one frame and
 * surfaces as a tail sample, so a stack that allocates harder produces a worse p999 while looking
 * identical at the median — "stutters sometimes" only becomes "stutters because it allocates" when
 * the heap and GC channels are read next to the frame distribution.
 */
public record MemorySnapshot(long heapUsedBytes, long heapCommittedBytes) {

    public MemorySnapshot minus(MemorySnapshot earlier) {
        return new MemorySnapshot(
                heapUsedBytes - earlier.heapUsedBytes,
                heapCommittedBytes - earlier.heapCommittedBytes);
    }

    public double heapUsedMegabytes() {
        return heapUsedBytes / (1024d * 1024d);
    }
}
