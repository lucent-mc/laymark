package cx.mia.lucent.laymark.core.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ordering is the whole product here, and it is pure, so all of it is tier 1. */
class ScheduleTest {

    private static final Arm BASELINE = new Arm("baseline", Arm.Kind.BASELINE, Set.of());
    private static final Arm ACCLIMATION = new Arm("acclimation", Arm.Kind.ACCLIMATION, Set.of());

    private static List<Arm> candidates(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Arm("c" + i, Arm.Kind.CANDIDATE, Set.of("c" + i + ".jar")))
                .toList();
    }

    private static List<String> ids(List<Arm> runs) {
        return runs.stream().map(Arm::id).toList();
    }

    @Test
    void expandsACandidatePassOverEveryCandidate() {
        List<Arm> runs =
                Schedule.of("B,C").expand(candidates(3), BASELINE, ACCLIMATION, true);

        assertEquals(List.of("baseline", "c0", "c1", "c2"), ids(runs));
    }

    /** Blocked, not interleaved: a candidate's repeats share as close a machine state as possible. */
    @Test
    void blockedRepetitionRunsEachCandidateConsecutively() {
        List<Arm> runs = Schedule.of("C2").expand(candidates(2), BASELINE, ACCLIMATION, true);

        assertEquals(List.of("c0", "c0", "c1", "c1"), ids(runs));
    }

    /** Acclimation is once per experiment, not once per round. */
    @Test
    void dropsAcclimationOnceItHasHappened() {
        assertEquals(
                List.of("acclimation", "baseline"),
                ids(Schedule.of("A,B").expand(List.of(), BASELINE, ACCLIMATION, false)));
        assertEquals(
                List.of("baseline"),
                ids(Schedule.of("A,B").expand(List.of(), BASELINE, ACCLIMATION, true)));
    }

    /**
     * The reason the interval exists. A fixed template does not scale: with twenty candidates a
     * single B would sit hours away from the runs it is supposed to bound.
     */
    @Test
    void insertsBaselinesInsideALongCandidatePass() {
        List<Arm> runs =
                new Schedule(RoundTemplate.parse("B,C"), 5)
                        .expand(candidates(12), BASELINE, ACCLIMATION, true);

        assertTrue(
                Schedule.longestUncheckedStretch(runs) <= 5,
                "no more than five candidate runs may pass unchecked: " + ids(runs));
        assertEquals(12, runs.stream().filter(a -> a.kind() == Arm.Kind.CANDIDATE).count());
    }

    @Test
    void aTighterIntervalVoidsFewerRunsWhenDriftIsFound() {
        List<Arm> loose =
                new Schedule(RoundTemplate.parse("B,C"), 5)
                        .expand(candidates(20), BASELINE, ACCLIMATION, true);
        List<Arm> tight =
                new Schedule(RoundTemplate.parse("B,C"), 2)
                        .expand(candidates(20), BASELINE, ACCLIMATION, true);

        assertTrue(
                Schedule.longestUncheckedStretch(tight)
                        < Schedule.longestUncheckedStretch(loose),
                "the interval is what bounds how much a single failed drift check invalidates");
    }

    /** The spec's worked example, which is also the cheapest check that expansion is right. */
    @Test
    void theSpecExampleExpandsToTwentyTwoRuns() {
        assertEquals(22, RoundTemplate.parse("B,C,C,B,C2").runsPerRound(5));
    }

    /** Re-expanded each round, because the candidate list shrinks as candidates are promoted. */
    @Test
    void expandsAgainstWhicheverCandidatesRemain() {
        Schedule schedule = Schedule.of("B,C");
        assertEquals(4, schedule.expand(candidates(3), BASELINE, ACCLIMATION, true).size());
        assertEquals(2, schedule.expand(candidates(1), BASELINE, ACCLIMATION, true).size());
    }

    @Test
    void parsesEverySlotSymbol() {
        assertEquals("A,B,C,C2", RoundTemplate.parse("A,B,C,C2").toString());
        assertEquals("B,C", RoundTemplate.parse(" B , C ").toString());
    }

    @Test
    void rejectsAnUnknownSlotAndSaysWhatIsValid() {
        HarnessException e =
                assertThrows(HarnessException.class, () -> RoundTemplate.parse("B,X"));
        assertTrue(e.getMessage().contains("'X'"), e.getMessage());
        assertTrue(e.getMessage().contains("A, B, C"), e.getMessage());
    }

    /** "B2" most likely means two baselines, which "B,B" already says without ambiguity. */
    @Test
    void rejectsACountOnASlotThatDoesNotTakeOne() {
        HarnessException e =
                assertThrows(HarnessException.class, () -> RoundTemplate.parse("B2"));
        assertTrue(e.getMessage().contains("write it twice"), e.getMessage());
    }

    @Test
    void rejectsUnreadableAndImpossibleCounts() {
        assertThrows(HarnessException.class, () -> RoundTemplate.parse("Cx"));
        assertThrows(HarnessException.class, () -> RoundTemplate.parse("C0"));
        assertThrows(HarnessException.class, () -> RoundTemplate.parse(""));
        assertThrows(HarnessException.class, () -> RoundTemplate.parse(",,"));
    }

    @Test
    void rejectsAnUnusableBaselineInterval() {
        assertThrows(
                HarnessException.class, () -> new Schedule(RoundTemplate.parse("C"), 0));
    }

    /** Acclimation measurements are discarded, so the arm has to know it is not scored. */
    @Test
    void acclimationIsNotScoredAndTheOthersAre() {
        assertTrue(!ACCLIMATION.scored());
        assertTrue(BASELINE.scored());
        assertTrue(candidates(1).get(0).scored());
    }

    @Test
    void refusesToExpandWithoutABaseline() {
        assertThrows(
                HarnessException.class,
                () -> Schedule.of("B").expand(List.of(), null, ACCLIMATION, true));
    }

    /** A template asking for acclimation that was never supplied is a configuration error. */
    @Test
    void refusesAcclimationItWasNotGiven() {
        assertThrows(
                HarnessException.class,
                () -> Schedule.of("A").expand(List.of(), BASELINE, null, false));
    }
}
