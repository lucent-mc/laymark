package cx.mia.lucent.laymark.core.harness;

import java.util.List;

/**
 * A frame-time distribution reduced to the numbers a report quotes.
 *
 * <p>Percentiles are of frame <em>duration</em>, so higher is worse throughout — including p999,
 * which is the stutter measure and the reason raw samples are retained rather than accumulated
 * into a running mean.
 *
 * @param count how many frames the window contained; a percentile from few samples is noisy, so
 *     the count travels with the summary rather than being discarded
 */
public record FrameStatistics(
        int count,
        double meanMillis,
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double p999Millis,
        double minMillis,
        double maxMillis) {

    public static FrameStatistics of(List<FrameSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new HarnessException("cannot summarise an empty capture");
        }
        double[] millis = samples.stream().mapToDouble(FrameSample::millis).sorted().toArray();
        double sum = 0;
        for (double value : millis) {
            sum += value;
        }
        return new FrameStatistics(
                millis.length,
                sum / millis.length,
                percentile(millis, 50),
                percentile(millis, 95),
                percentile(millis, 99),
                percentile(millis, 99.9),
                millis[0],
                millis[millis.length - 1]);
    }

    /**
     * Nearest-rank on the sorted samples.
     *
     * <p>Deliberately not interpolated. An interpolated p999 invents a frame time that never
     * occurred, and at the tail — where there may be a handful of samples — that invented value is
     * doing most of the work.
     */
    static double percentile(double[] sorted, double percentile) {
        int rank = (int) Math.ceil(percentile / 100d * sorted.length);
        return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)];
    }

    /** Frames per second implied by the mean, for readers who think in rates. */
    public double meanFramesPerSecond() {
        return 1_000d / meanMillis;
    }
}
