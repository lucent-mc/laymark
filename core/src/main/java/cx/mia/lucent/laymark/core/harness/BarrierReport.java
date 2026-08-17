package cx.mia.lucent.laymark.core.harness;

import java.util.List;

/**
 * Evidence that a scenario started from the state it claimed to.
 *
 * <p>The barrier is part of the apparatus, not scaffolding around it. {@code hasRenderedAllSections()}
 * is a question about {@code LevelRenderer}, and renderer-replacing mods reimplement that
 * subsystem — so the barrier itself can behave differently between the two arms being compared.
 * Recording what was checked, how long each condition took and how many polls it took to settle
 * makes "did both arms start from comparable states" a question answerable after the fact instead
 * of an assumption nobody wrote down.
 *
 * @param conditions in the order they were satisfied
 * @param stablePolls consecutive successful polls required before the barrier was believed
 * @param totalMillis wall-clock from the first poll to the barrier passing
 */
public record BarrierReport(List<Condition> conditions, int stablePolls, long totalMillis) {

    /**
     * One thing the barrier waited on.
     *
     * @param satisfiedAfterMillis from the start of the barrier, not of the condition — a
     *     condition that was already true reads as 0 and one that gated the barrier reads as
     *     nearly the total, which is the comparison worth being able to make
     */
    public record Condition(String name, long satisfiedAfterMillis, boolean everBlocked) {}

    public BarrierReport {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public static BarrierReport none() {
        return new BarrierReport(List.of(), 0, 0);
    }

    /** The condition that took longest to satisfy, which is the one worth comparing across arms. */
    public Condition slowest() {
        return conditions.stream()
                .max((a, b) -> Long.compare(a.satisfiedAfterMillis(), b.satisfiedAfterMillis()))
                .orElse(null);
    }
}
