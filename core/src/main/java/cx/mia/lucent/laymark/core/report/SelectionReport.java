package cx.mia.lucent.laymark.core.report;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.stats.Comparison;
import cx.mia.lucent.laymark.core.stats.Drift;
import java.util.List;
import java.util.Map;

/**
 * Everything one selection run concluded, in the shape a report renders from.
 *
 * <p>Derived from raw samples, never from a summary. The raw samples are authoritative and both
 * the JSON and the Markdown come off them independently, so a bug in one cannot silently propagate
 * into the other and agreement between them means something.
 *
 * <p>Per-round history is <strong>output rather than scratch</strong>. A candidate is re-measured
 * against every new baseline, so its score moves between rounds, and the reason it moved — a
 * genuine interaction, or its bundle shrinking as a dependency was promoted — is the most useful
 * thing the run learned. Discarding the history would leave only the final ordering, which is the
 * part a reader can already guess.
 *
 * @param stack the recommended order, best first
 * @param rounds each round's ranking, in order, so movement between them is visible
 * @param voids windows the run declared incomparable; a void comparison is reported as void rather
 *     than silently producing a number
 * @param branches conflict branches explored, each with its own stack
 */
public record SelectionReport(
        String runId,
        String hardware,
        String baselineDescription,
        long elapsedMillis,
        List<String> stack,
        List<Round> rounds,
        List<Comparison> comparisons,
        List<Drift.VoidWindow> voids,
        List<Branch> branches,
        Map<String, String> provenance) {

    /** One round's ranking, and why each entry moved. */
    public record Round(int number, List<Entry> entries) {

        /**
         * @param movedBecause empty when the score did not move, otherwise the reason — which is
         *     the distinction between a real interaction and a bundle that shrank
         */
        public record Entry(
                String candidate,
                double scorePercent,
                String verdict,
                List<String> bundleMembers,
                String movedBecause) {}
    }

    /** One diverging stack from a conflict cluster. */
    public record Branch(String describedBy, List<String> stack) {}

    public SelectionReport {
        if (runId == null || runId.isBlank()) {
            throw new HarnessException("a report has no run id");
        }
        stack = List.copyOf(stack);
        rounds = List.copyOf(rounds);
        comparisons = List.copyOf(comparisons);
        voids = List.copyOf(voids);
        branches = List.copyOf(branches);
        provenance = Map.copyOf(provenance);
    }

    /**
     * The cumulative effect of taking the first N of the stack.
     *
     * <p>Cumulative leads and marginal sits beneath it, because marginal effects do not sum
     * cleanly across a stack and presenting only marginals invites a reader to add them up. What
     * this shows is the shape of diminishing returns; it asserts no threshold, because where to
     * stop is the operator's call.
     */
    public List<Double> cumulativeByPosition() {
        List<Double> cumulative = new java.util.ArrayList<>();
        double running = 1.0;
        for (String candidate : stack) {
            double marginal = marginalFor(candidate);
            running *= 1 - marginal / 100.0;
            cumulative.add((1 - running) * 100.0);
        }
        return cumulative;
    }

    /** The improvement attributed to one candidate at the point it was promoted. */
    public double marginalFor(String candidate) {
        return comparisons.stream()
                .filter(comparison -> comparison.candidateId().equals(candidate))
                .mapToDouble(Comparison::improvementPercent)
                .average()
                .orElse(0);
    }
}
