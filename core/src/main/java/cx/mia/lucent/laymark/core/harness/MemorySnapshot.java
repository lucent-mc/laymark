package cx.mia.lucent.laymark.core.harness;

/**
 * Heap and garbage-collection state, read at each end of a capture.
 *
 * <p>Read straight from the JVM rather than from a profiling mod. These numbers are a property of
 * the process, not of Minecraft, so nothing needs to be installed to obtain them and nothing extra
 * runs inside the process being measured — which is the whole reason to prefer this source.
 *
 * <p>GC counters matter more than the heap number here. A collection pause lands in exactly one
 * frame and shows up as a tail sample, so a stack that allocates harder produces worse p999 while
 * looking identical at the median. Pairing the two channels is what turns "stutters sometimes"
 * into "stutters because it allocates".
 *
 * @param gcCount collections since JVM start, across all collectors; a delta is the useful form
 * @param gcTimeMillis approximate accumulated pause time, on the same cumulative basis
 */
public record MemorySnapshot(
        long heapUsedBytes, long heapCommittedBytes, long gcCount, long gcTimeMillis) {

    public MemorySnapshot minus(MemorySnapshot earlier) {
        return new MemorySnapshot(
                heapUsedBytes - earlier.heapUsedBytes,
                heapCommittedBytes - earlier.heapCommittedBytes,
                gcCount - earlier.gcCount,
                gcTimeMillis - earlier.gcTimeMillis);
    }

    public double heapUsedMegabytes() {
        return heapUsedBytes / (1024d * 1024d);
    }
}
