package cx.mia.lucent.laymark.core.harness;

/**
 * One integrated-server tick, timed.
 *
 * <p>The channel that catches a mod costing nothing to draw and a great deal to simulate. Server
 * ticks are also what a frame waits on when the two share a machine, so a regression visible only
 * here still reaches the player — it just does not look like a rendering problem.
 *
 * <p>Ticks are nominally 50 ms apart and the server is healthy while each finishes inside that.
 * The duration is therefore read as headroom rather than as a rate.
 */
public record TickSample(long offsetNanos, long durationNanos) {

    /** A tick that takes longer than this makes the server fall behind real time. */
    public static final long BUDGET_NANOS = 50_000_000L;

    public TickSample {
        if (offsetNanos < 0) {
            throw new HarnessException("tick offset must not be negative");
        }
        if (durationNanos <= 0) {
            throw new HarnessException("tick duration must be positive, got " + durationNanos);
        }
    }

    public boolean overBudget() {
        return durationNanos > BUDGET_NANOS;
    }

    public double millis() {
        return durationNanos / 1_000_000d;
    }
}
