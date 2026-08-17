package cx.mia.lucent.laymark.core.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects a baseline that has moved further than its own history says it should.
 *
 * <p>Derived, not invented. There is no threshold for "the machine got slower" — there is only the
 * baseline's own control history, and a run that falls outside what that history predicts. A
 * machine that has always been noisy is allowed to be noisy; one that has been steady and then
 * jumps is what this catches.
 *
 * <p>Drift is <strong>localised to the window between two baseline runs</strong>, which is what
 * makes the baseline interval worth tightening: only the candidate runs inside the affected window
 * are voided, not the whole schedule.
 */
public final class Drift {

    private Drift() {}

    /**
     * How many standard deviations from the running mean counts as drift.
     *
     * <p>Three, the conventional control-chart limit. Under stable conditions roughly one reading
     * in 370 falls outside it by chance, so a false drift flag costs one voided window rarely
     * enough to be worth the protection.
     */
    private static final double SIGMA = 3.0;

    /** A stretch of the schedule whose results cannot be trusted. */
    public record VoidWindow(int fromSequence, int toSequence, String reason) {}

    /**
     * Finds the windows bounded by a baseline that moved.
     *
     * <p>Needs at least three baselines before it can say anything: two define no spread, and one
     * defines nothing at all. Below that it reports no drift rather than guessing, which is the
     * honest answer and not the safe-looking one.
     *
     * @param baselines in schedule order
     */
    public static List<VoidWindow> detect(List<Comparison.Run> baselines) {
        List<VoidWindow> windows = new ArrayList<>();
        if (baselines.size() < 3) {
            return windows;
        }

        for (int i = 2; i < baselines.size(); i++) {
            List<Comparison.Run> history = baselines.subList(0, i);
            double mean =
                    history.stream().mapToDouble(Comparison.Run::scoredMillis).average().orElseThrow();
            double variance =
                    history.stream()
                                    .mapToDouble(run -> Math.pow(run.scoredMillis() - mean, 2))
                                    .sum()
                            / (history.size() - 1);
            double deviation = Math.sqrt(variance);
            if (deviation == 0) {
                continue;
            }

            Comparison.Run current = baselines.get(i);
            double distance = Math.abs(current.scoredMillis() - mean) / deviation;
            if (distance > SIGMA) {
                // The window is from the previous baseline to this one: everything measured in
                // between was measured on a machine that had already moved.
                windows.add(
                        new VoidWindow(
                                baselines.get(i - 1).sequence(),
                                current.sequence(),
                                String.format(java.util.Locale.ROOT, 
                                        "baseline moved %.1f standard deviations from its own"
                                                + " history (%.2fms against a mean of %.2fms)",
                                        distance, current.scoredMillis(), mean)));
            }
        }
        return windows;
    }

    /** Whether a run sits inside any voided window, and is therefore not comparable. */
    public static boolean voided(int sequence, List<VoidWindow> windows) {
        return windows.stream()
                .anyMatch(window -> sequence > window.fromSequence() && sequence <= window.toSequence());
    }
}
