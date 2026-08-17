package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.SparkStatistics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;

/**
 * Server health, read from Spark rather than measured again.
 *
 * <p>Spark already does this across every platform it supports, with the rolling windows and
 * percentile handling worked out, and its numbers are the ones users already read out of
 * {@code /spark health}. A second implementation beside it would be a second thing to be wrong and
 * a second set of numbers to reconcile.
 *
 * <p>{@code spark-api} is <strong>compileOnly</strong>. Spark's own mod supplies the
 * implementation at runtime, so every entry point here is guarded twice over: the classes may be
 * absent entirely, and {@link SparkProvider#get()} throws while Spark is still starting. Neither
 * is a run-ending problem — the statistics channel goes empty and the run says so.
 *
 * <p>Nothing from {@code spark-common} is touched. The sampler, the platform and the container are
 * shaded into the mod jar, unpublished, and explicitly not a supported surface.
 */
final class SparkChannel {

    /**
     * Shortest windows Spark offers, which is what a capture wants.
     *
     * <p>Even so these are rolling windows rather than capture-scoped aggregates: read at the end
     * of a longer capture they describe only its tail. The length is recorded alongside the
     * figures so the reader is told that rather than left to assume otherwise.
     */
    private static final StatisticWindow.MillisPerTick MSPT_WINDOW =
            StatisticWindow.MillisPerTick.SECONDS_10;

    private static final StatisticWindow.TicksPerSecond TPS_WINDOW =
            StatisticWindow.TicksPerSecond.SECONDS_10;

    private Map<String, GcActivityTotals> gcAtStart = Map.of();
    private String unavailable;

    private record GcActivityTotals(long collections, long totalTimeMillis) {}

    /** Why the channel is empty, or null while it is working. */
    String unavailableReason() {
        return unavailable;
    }

    /** Snapshots the cumulative GC counters so the capture can be differenced against them. */
    void start() {
        unavailable = null;
        gcAtStart = Map.of();
        try {
            gcAtStart = collectors(spark());
        } catch (RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    /** @return the capture's statistics, or null when Spark could not supply them */
    SparkStatistics stop() {
        if (unavailable != null) {
            return null;
        }
        try {
            Spark spark = spark();

            Double tps = spark.tps() == null ? null : spark.tps().poll(TPS_WINDOW);
            DoubleAverageInfo mspt = spark.mspt() == null ? null : spark.mspt().poll(MSPT_WINDOW);
            if (mspt == null || tps == null) {
                // Both are null on a platform with no tick loop to measure. Nothing is wrong; there
                // is simply no server here, and saying so beats reporting zeros.
                unavailable = "spark reports no tick statistics for this platform";
                return null;
            }

            return new SparkStatistics(
                    tps,
                    mspt.mean(),
                    mspt.min(),
                    mspt.max(),
                    mspt.median(),
                    mspt.percentile95th(),
                    MSPT_WINDOW.length().toMillis(),
                    gcDelta(spark));
        } catch (RuntimeException | LinkageError e) {
            disable(e);
            return null;
        }
    }

    /**
     * GC activity during the capture.
     *
     * <p>Spark reports cumulative totals, so the capture-scoped figure is a difference. A
     * collector that first appears mid-capture counts from zero, which is correct — everything it
     * has ever done happened inside the window.
     */
    private List<SparkStatistics.GcActivity> gcDelta(Spark spark) {
        List<SparkStatistics.GcActivity> activity = new ArrayList<>();
        collectors(spark)
                .forEach(
                        (name, now) -> {
                            GcActivityTotals before =
                                    gcAtStart.getOrDefault(name, new GcActivityTotals(0, 0));
                            long collections = now.collections() - before.collections();
                            long millis = now.totalTimeMillis() - before.totalTimeMillis();
                            if (collections > 0 || millis > 0) {
                                activity.add(
                                        new SparkStatistics.GcActivity(name, collections, millis));
                            }
                        });
        return activity;
    }

    private static Map<String, GcActivityTotals> collectors(Spark spark) {
        Map<String, GcActivityTotals> totals = new HashMap<>();
        for (Map.Entry<String, GarbageCollector> entry : spark.gc().entrySet()) {
            GarbageCollector collector = entry.getValue();
            totals.put(
                    entry.getKey(),
                    new GcActivityTotals(collector.totalCollections(), collector.totalTime()));
        }
        return totals;
    }

    private static Spark spark() {
        return SparkProvider.get();
    }

    private void disable(Throwable cause) {
        // NoClassDefFoundError means Spark is not installed; IllegalStateException means it has
        // not enabled yet. Both are ordinary states of a machine, not defects in a run.
        unavailable =
                cause instanceof LinkageError
                        ? "spark is not installed"
                        : "spark is not available: " + cause;
    }

    /** Exposed for the spec's benefit: the window these figures actually describe. */
    static Duration window() {
        return MSPT_WINDOW.length();
    }
}
