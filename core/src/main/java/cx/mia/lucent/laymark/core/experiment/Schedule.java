package cx.mia.lucent.laymark.core.experiment;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayList;
import java.util.List;

/**
 * Expands a round template into the actual order of arm runs.
 *
 * <p>Two things decide that order, and they are independent on purpose. The template says what a
 * round looks like; {@link #baselineInterval} puts a floor under how often a baseline appears
 * regardless. A template alone does not scale — at twenty candidates a single {@code B} sits hours
 * from the runs it is meant to bound — and <strong>baseline runs are the drift checks</strong>, so
 * a tighter interval localises any drift to a smaller window and voids fewer runs when it happens.
 *
 * <p>Expansion is per round rather than once up front, because the candidate list shrinks as
 * candidates are promoted or dropped, and a flattened schedule would keep scheduling runs for
 * candidates no longer under test.
 */
public record Schedule(RoundTemplate template, int baselineInterval) {

    /**
     * Default ceiling on candidate runs between baselines.
     *
     * <p>Five is a compromise: every drift check costs a full run, and the window it bounds is the
     * set of results that get voided when one fails.
     */
    public static final int DEFAULT_BASELINE_INTERVAL = 5;

    public Schedule {
        if (template == null) {
            throw new HarnessException("a schedule has no round template");
        }
        if (baselineInterval < 1) {
            throw new HarnessException(
                    "baseline interval must be at least 1, got " + baselineInterval);
        }
    }

    public static Schedule of(String template) {
        return new Schedule(RoundTemplate.parse(template), DEFAULT_BASELINE_INTERVAL);
    }

    /**
     * The runs for one round, in order.
     *
     * <p>A baseline is inserted whenever {@link #baselineInterval} candidate runs have passed
     * without one — including part-way through a candidate pass, since a long pass is exactly the
     * case a fixed template handles badly.
     *
     * @param candidates the bundles still under test, in the order they should be visited
     * @param acclimated whether the acclimation run has already happened; it is once per
     *     experiment, not once per round, so later rounds drop the {@code A} slot
     */
    public List<Arm> expand(List<Arm> candidates, Arm baseline, Arm acclimation, boolean acclimated) {
        if (baseline == null) {
            throw new HarnessException("a schedule needs a baseline arm");
        }
        List<Arm> runs = new ArrayList<>();
        int sinceBaseline = 0;

        for (RoundTemplate.Slot slot : template.slots()) {
            switch (slot) {
                case RoundTemplate.Slot.Acclimation unused -> {
                    if (!acclimated) {
                        if (acclimation == null) {
                            throw new HarnessException(
                                    "the template asks for acclimation but none was supplied");
                        }
                        runs.add(acclimation);
                    }
                }
                case RoundTemplate.Slot.Baseline unused -> {
                    runs.add(baseline);
                    sinceBaseline = 0;
                }
                case RoundTemplate.Slot.Candidates pass -> {
                    for (Arm candidate : candidates) {
                        for (int repeat = 0; repeat < pass.repeats(); repeat++) {
                            if (sinceBaseline >= baselineInterval) {
                                runs.add(baseline);
                                sinceBaseline = 0;
                            }
                            runs.add(candidate);
                            sinceBaseline++;
                        }
                    }
                }
            }
        }
        return runs;
    }

    /**
     * The longest run of candidates with no baseline between them.
     *
     * <p>The quantity the interval exists to bound, so it is worth being able to assert on: it is
     * how many results a single failed drift check would void.
     */
    public static int longestUncheckedStretch(List<Arm> runs) {
        int longest = 0;
        int current = 0;
        for (Arm arm : runs) {
            if (arm.kind() == Arm.Kind.BASELINE) {
                current = 0;
            } else if (arm.kind() == Arm.Kind.CANDIDATE) {
                current++;
                longest = Math.max(longest, current);
            }
        }
        return longest;
    }
}
