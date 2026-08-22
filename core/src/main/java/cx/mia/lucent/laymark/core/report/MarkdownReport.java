package cx.mia.lucent.laymark.core.report;

import cx.mia.lucent.laymark.core.stats.Comparison;
import cx.mia.lucent.laymark.core.stats.Drift;
import java.time.Duration;
import java.util.List;

/**
 * Renders a selection run for a human.
 *
 * <p>Built from the same {@link SelectionReport} the JSON is, which is itself built from raw
 * samples — never from the JSON summary. Two independent renderings of one source can be compared;
 * a rendering of a rendering cannot.
 *
 * <p>Structured so the things that would make a number untrustworthy are impossible to skip past:
 * validity annotations and voided windows sit in the body, not an appendix. <strong>Because a
 * human decides, every reason a number might be wrong has to reach them.</strong>
 */
public final class MarkdownReport {

    private MarkdownReport() {}

    public static String render(SelectionReport report) {
        StringBuilder out = new StringBuilder();

        out.append("# Laymark run ").append(report.runId()).append("\n\n");
        out.append("- Baseline: ").append(report.baselineDescription()).append('\n');
        out.append("- Hardware: ").append(report.hardware()).append('\n');
        out.append("- Elapsed: ")
                .append(Duration.ofMillis(report.elapsedMillis()).toMinutes())
                .append(" minutes\n\n");

        // Validity first. A reader who stops after the stack must already have seen that part of
        // the run was thrown away or needs weighing.
        if (!report.voids().isEmpty() || !report.trustFlags().isEmpty()) {
            out.append("## Validity\n\n");
            if (!report.voids().isEmpty()) {
                out.append(report.voids().size())
                        .append(" window(s) were voided and their results excluded.\n\n");
                for (Drift.VoidWindow window : report.voids()) {
                    out.append("- runs ")
                            .append(window.fromSequence())
                            .append("–")
                            .append(window.toSequence())
                            .append(": ")
                            .append(window.reason())
                            .append('\n');
                }
                out.append('\n');
            }
            for (String flag : report.trustFlags()) {
                out.append("- ").append(flag).append('\n');
            }
            if (!report.trustFlags().isEmpty()) {
                out.append('\n');
            }
        }

        out.append("## Recommended stack\n\n");
        if (report.stack().isEmpty()) {
            out.append("Nothing was promoted.\n\n");
        } else {
            out.append("| # | Mod | Cumulative | Marginal |\n|---|---|---|---|\n");
            List<Double> cumulative = report.cumulativeByPosition();
            for (int i = 0; i < report.stack().size(); i++) {
                String candidate = report.stack().get(i);
                out.append("| ")
                        .append(i + 1)
                        .append(" | ")
                        .append(candidate)
                        .append(" | ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", cumulative.get(i)))
                        .append(" | ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", report.marginalFor(candidate)))
                        .append(" |\n");
            }
            out.append(
                    "\nCumulative leads because marginal effects do not sum across a stack. This is"
                            + " the shape of diminishing returns; where to stop is your call.\n\n");
        }

        if (!report.branches().isEmpty()) {
            out.append("## Branches\n\n");
            out.append("Conflicting mods produced diverging stacks; both were measured.\n\n");
            for (SelectionReport.Branch branch : report.branches()) {
                out.append("- **").append(branch.describedBy()).append("**: ")
                        .append(String.join(" → ", branch.stack()))
                        .append('\n');
            }
            out.append('\n');
        }

        out.append("## Per-round history\n\n");
        for (SelectionReport.Round round : report.rounds()) {
            out.append("### Round ").append(round.number()).append("\n\n");
            for (SelectionReport.Round.Entry entry : round.entries()) {
                out.append("- ")
                        .append(entry.candidate())
                        .append(" — ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", entry.scorePercent()))
                        .append(", ")
                        .append(entry.verdict());
                if (entry.bundleMembers().size() > 1) {
                    out.append(" (bundled with ")
                            .append(String.join(", ", entry.bundleMembers().stream()
                                    .filter(member -> !member.equals(entry.candidate()))
                                    .toList()))
                            .append(')');
                }
                if (!entry.movedBecause().isBlank()) {
                    out.append(" — ").append(entry.movedBecause());
                }
                out.append('\n');
            }
            out.append('\n');
        }

        out.append("## Per-scenario results\n\n");
        out.append(
                "| Mod | Scenario | Metric | Band | Change | Interval | n |\n"
                        + "|---|---|---|---|---|---|---|\n");
        for (Comparison comparison : report.comparisons()) {
            // Regressions are named, not averaged away: a mod that helps one scenario and hurts
            // another is presenting a trade, and the trade is the finding.
            out.append("| ")
                    .append(comparison.candidateId())
                    .append(" | ")
                    .append(comparison.scenarioId())
                    .append(" | ")
                    .append(comparison.metric().toString().toLowerCase(java.util.Locale.ROOT))
                    .append(" | **")
                    .append(comparison.band())
                    .append("** | ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%%", comparison.improvementPercent()))
                    .append(" | ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%% to %.1f%%", -comparison.highPercent(),
                            -comparison.lowPercent()))
                    .append(" | ")
                    .append(comparison.pairs())
                    .append(" |\n");
        }

        long voided = report.comparisons().stream().mapToInt(Comparison::voided).sum();
        if (voided > 0) {
            out.append("\n")
                    .append(voided)
                    .append(" individual run(s) were excluded as not comparable.\n");
        }

        out.append("\n## Environment\n\n");
        report.provenance().forEach((key, value) ->
                out.append("- ").append(key).append(": ").append(value).append('\n'));

        return out.toString();
    }
}
