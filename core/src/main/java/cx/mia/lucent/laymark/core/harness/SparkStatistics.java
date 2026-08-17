package cx.mia.lucent.laymark.core.harness;

import java.util.List;

/**
 * Server health for a capture, as Spark measures it.
 *
 * <p>Laymark does not time server ticks itself. Spark already does it well, across every platform
 * it supports, with the rolling windows and the percentile handling worked out — a second
 * implementation living beside it would be a second thing to be wrong, and it would disagree with
 * the numbers users already read out of {@code /spark health}.
 *
 * <p>Spark's statistics are <strong>rolling windows</strong>, not per-capture aggregates, which is
 * why {@link #windowMillis} travels with them. A ten-second window read at the end of a
 * thirty-second capture describes its final third; the reader has to be told that rather than
 * left to assume the numbers span what they are printed beneath.
 *
 * @param ticksPerSecond over {@link #windowMillis}; 20 is the ceiling
 * @param gc per-collector activity <em>during</em> the capture, differenced from cumulative
 *     totals — the one part of this that really is capture-scoped
 */
public record SparkStatistics(
        double ticksPerSecond,
        double millisPerTickMean,
        double millisPerTickMin,
        double millisPerTickMax,
        double millisPerTickMedian,
        double millisPerTickPercentile95,
        long windowMillis,
        List<GcActivity> gc) {

    /** One collector's work during a capture. */
    public record GcActivity(String collector, long collections, long totalTimeMillis) {

        public GcActivity {
            if (collector == null || collector.isBlank()) {
                throw new HarnessException("gc activity has no collector name");
            }
        }
    }

    public SparkStatistics {
        gc = gc == null ? List.of() : List.copyOf(gc);
    }

    /**
     * Milliseconds a tick had to spare.
     *
     * <p>The server falls behind real time once a tick exceeds its 50 ms budget, so headroom is
     * the readable form — a mean of 2 ms and a mean of 45 ms are both "20 TPS" and are not remotely
     * the same situation.
     */
    public double headroomMillis() {
        return TICK_BUDGET_MILLIS - millisPerTickMean;
    }

    /** A tick longer than this makes the server fall behind. */
    public static final double TICK_BUDGET_MILLIS = 50;

    public long totalCollections() {
        return gc.stream().mapToLong(GcActivity::collections).sum();
    }

    public long totalGcMillis() {
        return gc.stream().mapToLong(GcActivity::totalTimeMillis).sum();
    }
}
