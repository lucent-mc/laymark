package cx.mia.lucent.laymark.core.experiment;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayList;
import java.util.List;

/**
 * The order arms run in, written as slot symbols and re-expanded each round.
 *
 * <pre>
 *   A     acclimation, once
 *   B     one baseline run
 *   C     one round-robin pass over the remaining candidates
 *   Cn    blocked repetition: each candidate n times consecutively
 * </pre>
 *
 * <p>Re-expanded rather than expanded once, because the candidate list shrinks as candidates are
 * promoted or dropped. {@code B,C,C,B,C2} against five candidates is 22 runs in the first round
 * and fewer in the next, and a template that had been flattened up front would keep scheduling
 * runs for candidates that are no longer under test.
 *
 * <p><strong>Baselines are the drift checks.</strong> That is why a template alone is not enough:
 * a fixed one does not scale, and at twenty candidates a single {@code B} would sit hours away
 * from the candidate runs it is supposed to bound. {@link Schedule} enforces a maximum gap on top
 * of whatever the template says.
 */
public record RoundTemplate(List<Slot> slots) {

    /** One symbol from the template. */
    public sealed interface Slot {

        /** {@code A} — the discarded warm-up run. */
        record Acclimation() implements Slot {}

        /** {@code B} — one baseline run. */
        record Baseline() implements Slot {}

        /**
         * {@code C} or {@code Cn} — a pass over every remaining candidate.
         *
         * @param repeats how many times each candidate runs consecutively before moving to the
         *     next. Blocked rather than interleaved, so a candidate's repetitions share a machine
         *     state as close to identical as the schedule can make it.
         */
        record Candidates(int repeats) implements Slot {}
    }

    public RoundTemplate {
        if (slots == null || slots.isEmpty()) {
            throw new HarnessException("a round template has no slots");
        }
        slots = List.copyOf(slots);
    }

    /**
     * Parses the comma-separated form an operator writes, such as {@code B,C,C,B,C2}.
     *
     * @throws HarnessException naming the symbol it could not read
     */
    public static RoundTemplate parse(String template) {
        if (template == null || template.isBlank()) {
            throw new HarnessException("a round template must not be empty");
        }
        List<Slot> slots = new ArrayList<>();
        for (String raw : template.split(",")) {
            String symbol = raw.trim();
            if (symbol.isEmpty()) {
                continue;
            }
            slots.add(slot(symbol, template));
        }
        if (slots.isEmpty()) {
            throw new HarnessException("round template '" + template + "' names no slots");
        }
        return new RoundTemplate(slots);
    }

    private static Slot slot(String symbol, String template) {
        return switch (symbol.charAt(0)) {
            case 'A' -> requireBare(symbol, template, new Slot.Acclimation());
            case 'B' -> requireBare(symbol, template, new Slot.Baseline());
            case 'C' -> new Slot.Candidates(repeats(symbol, template));
            default ->
                    throw new HarnessException(
                            "round template '" + template + "' has an unknown slot '" + symbol
                                    + "'; known slots are A, B, C and Cn");
        };
    }

    private static Slot requireBare(String symbol, String template, Slot slot) {
        if (symbol.length() > 1) {
            // Only candidate passes take a count. "B2" would most likely mean "two baselines",
            // which is what "B,B" says unambiguously.
            throw new HarnessException(
                    "round template '" + template + "' gives a count to '" + symbol.charAt(0)
                            + "', which does not take one; write it twice instead");
        }
        return slot;
    }

    private static int repeats(String symbol, String template) {
        if (symbol.length() == 1) {
            return 1;
        }
        try {
            int repeats = Integer.parseInt(symbol.substring(1));
            if (repeats < 1) {
                throw new HarnessException(
                        "round template '" + template + "' repeats a candidate " + repeats
                                + " times");
            }
            return repeats;
        } catch (NumberFormatException e) {
            throw new HarnessException(
                    "round template '" + template + "' has an unreadable count in '" + symbol + "'");
        }
    }

    /** How many runs one round costs for a given number of candidates. */
    public int runsPerRound(int candidates) {
        int runs = 0;
        for (Slot slot : slots) {
            runs +=
                    switch (slot) {
                        case Slot.Acclimation unused -> 1;
                        case Slot.Baseline unused -> 1;
                        case Slot.Candidates pass -> candidates * pass.repeats();
                    };
        }
        return runs;
    }

    @Override
    public String toString() {
        return slots.stream()
                .map(
                        slot ->
                                switch (slot) {
                                    case Slot.Acclimation unused -> "A";
                                    case Slot.Baseline unused -> "B";
                                    case Slot.Candidates pass ->
                                            pass.repeats() == 1 ? "C" : "C" + pass.repeats();
                                })
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();
    }
}
