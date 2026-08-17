package cx.mia.lucent.laymark.core.harness;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * A timing distribution reduced to the numbers a report quotes.
 *
 * <p>Percentiles are of frame <em>duration</em>, so higher is worse throughout — including p999,
 * which is the stutter measure and the reason raw samples are retained rather than accumulated
 * into a running mean.
 *
 * @param count how many samples the window contained; a percentile from few samples is noisy, so
 *     the count travels with the summary rather than being discarded
 * @param onePercentLowMillis mean of the worst 1% of samples — the gamer's "1% low", as a
 *     duration. Distinct from p99: p99 is the threshold the worst 1% crosses, this is how bad
 *     that 1% actually was once it crossed.
 */
public record FrameStatistics(
        int count,
        double meanMillis,
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double p999Millis,
        double onePercentLowMillis,
        double minMillis,
        double maxMillis) {

    /** Summarises the headline channel. */
    public static FrameStatistics of(List<FrameSample> samples) {
        return of(samples, TimingChannel.INTERVAL);
    }

    public static FrameStatistics of(List<FrameSample> samples, TimingChannel channel) {
        return from(samples, sample -> sample.nanos(channel));
    }

    /**
     * @param nanos how to read the quantity being summarised, so the same reduction serves frame
     *     channels, GPU timings and server ticks rather than being copied three times
     */
    public static <T> FrameStatistics from(List<T> samples, ToLongFunction<T> nanos) {
        if (samples == null || samples.isEmpty()) {
            throw new HarnessException("cannot summarise an empty capture");
        }
        double[] millis =
                samples.stream().mapToLong(nanos).mapToDouble(n -> n / 1_000_000d).sorted().toArray();
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
                onePercentLow(millis),
                millis[0],
                millis[millis.length - 1]);
    }

    /** Mean of the worst 1% (at least one sample), on the already-sorted array. */
    private static double onePercentLow(double[] sorted) {
        int worst = Math.max(1, sorted.length / 100);
        double sum = 0;
        for (int i = sorted.length - worst; i < sorted.length; i++) {
            sum += sorted[i];
        }
        return sum / worst;
    }

    /** The 1% low as a framerate, which is how the number is usually quoted. */
    public double onePercentLowFramesPerSecond() {
        return 1_000d / onePercentLowMillis;
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
