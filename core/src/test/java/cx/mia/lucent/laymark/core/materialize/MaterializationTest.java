package cx.mia.lucent.laymark.core.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tier 1: no filesystem. The awkward cases here — a half-materialised instance, a mod that
 * vanished between rounds — would each take a real interrupted run to reproduce on disk, which is
 * exactly why the decision is data rather than an action.
 */
class MaterializationTest {

    private static ModFile mod(String name) {
        return new ModFile(name, "a".repeat(64));
    }

    private static InstanceState state(List<String> enabled, List<String> disabled, List<String> withheld) {
        return new InstanceState(
                enabled.stream().map(MaterializationTest::mod).toList(),
                disabled.stream().map(MaterializationTest::mod).toList(),
                withheld.stream().map(MaterializationTest::mod).toList());
    }

    @Test
    void disablesAParticipantTheArmDoesNotWant() {
        List<FileOperation> operations =
                Materialization.plan(
                        state(List.of("sodium.jar", "candidate.jar"), List.of(), List.of()),
                        Set.of("sodium.jar", "candidate.jar"),
                        Set.of("sodium.jar"));

        assertEquals(List.of(new FileOperation.Disable("candidate.jar")), operations);
    }

    @Test
    void enablesAParticipantTheArmWants() {
        List<FileOperation> operations =
                Materialization.plan(
                        state(List.of("sodium.jar"), List.of("candidate.jar"), List.of()),
                        Set.of("sodium.jar", "candidate.jar"),
                        Set.of("sodium.jar", "candidate.jar"));

        assertEquals(List.of(new FileOperation.Enable("candidate.jar")), operations);
    }

    @Test
    void leavesAlonePartsThatAreAlreadyRight() {
        assertTrue(
                Materialization.plan(
                                state(List.of("sodium.jar"), List.of("candidate.jar"), List.of()),
                                Set.of("sodium.jar", "candidate.jar"),
                                Set.of("sodium.jar"))
                        .isEmpty());
    }

    /** Non-participants leave mods/ entirely, so the directory reads as the stack under test. */
    @Test
    void withholdsEverythingNotParticipating() {
        List<FileOperation> operations =
                Materialization.plan(
                        state(List.of("sodium.jar", "unrelated.jar"), List.of(), List.of()),
                        Set.of("sodium.jar"),
                        Set.of("sodium.jar"));

        assertEquals(List.of(new FileOperation.Withhold("unrelated.jar")), operations);
    }

    /** A withheld non-participant is already out of the way and must not be fetched back. */
    @Test
    void leavesAlreadyWithheldNonParticipantsAlone() {
        assertTrue(
                Materialization.plan(
                                state(List.of("sodium.jar"), List.of(), List.of("unrelated.jar")),
                                Set.of("sodium.jar"),
                                Set.of("sodium.jar"))
                        .isEmpty());
    }

    /** A disabled non-participant is enabled first, so the file it moves has the name expected. */
    @Test
    void normalisesADisabledNonParticipantBeforeWithholdingIt() {
        List<FileOperation> operations =
                Materialization.plan(
                        state(List.of("sodium.jar"), List.of("unrelated.jar"), List.of()),
                        Set.of("sodium.jar"),
                        Set.of("sodium.jar"));

        assertEquals(
                List.of(
                        new FileOperation.Enable("unrelated.jar"),
                        new FileOperation.Withhold("unrelated.jar")),
                operations);
    }

    /** A participant is brought back before its state is set; it cannot be enabled where it is. */
    @Test
    void restoresAWithheldParticipantBeforeEnablingIt() {
        List<FileOperation> operations =
                Materialization.plan(
                        state(List.of(), List.of(), List.of("candidate.jar")),
                        Set.of("candidate.jar"),
                        Set.of("candidate.jar"));

        assertEquals(
                List.of(
                        new FileOperation.Restore("candidate.jar"),
                        new FileOperation.Enable("candidate.jar")),
                operations);
    }

    /** Continuing would run a smaller stack than the invocation described, and look fine doing it. */
    @Test
    void refusesAnArmNamingAModTheInstanceDoesNotHave() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () ->
                                Materialization.plan(
                                        state(List.of("sodium.jar"), List.of(), List.of()),
                                        Set.of("sodium.jar", "ghost.jar"),
                                        Set.of("sodium.jar")));
        assertTrue(e.getMessage().contains("ghost.jar"), e.getMessage());
    }

    @Test
    void refusesEnablingSomethingThatIsNotAParticipant() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () ->
                                Materialization.plan(
                                        state(List.of("a.jar", "b.jar"), List.of(), List.of()),
                                        Set.of("a.jar"),
                                        Set.of("a.jar", "b.jar")));
        assertTrue(e.getMessage().contains("b.jar"), e.getMessage());
    }

    /** The likeliest cause is a previous run interrupted partway through its renames. */
    @Test
    void refusesAnInstanceHoldingTheSameModTwice() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () -> state(List.of("dup.jar"), List.of(), List.of("dup.jar")));
        assertTrue(e.getMessage().contains("partly-materialised"), e.getMessage());
    }

    /**
     * Recovery restores to the recorded start, not by replaying what was done. An interrupted run
     * then recovers exactly the way a clean one finishes.
     */
    @Test
    void restoresToTheRecordedStartingState() {
        InstanceState initial = state(List.of("a.jar", "b.jar"), List.of("c.jar"), List.of());
        InstanceState current = state(List.of("a.jar", "c.jar"), List.of("b.jar"), List.of());

        List<FileOperation> operations = Materialization.restore(current, initial);

        assertTrue(operations.contains(new FileOperation.Enable("b.jar")), operations.toString());
        assertTrue(operations.contains(new FileOperation.Disable("c.jar")), operations.toString());
    }

    @Test
    void restoreBringsWithheldModsBack() {
        InstanceState initial = state(List.of("a.jar", "unrelated.jar"), List.of(), List.of());
        InstanceState current = state(List.of("a.jar"), List.of(), List.of("unrelated.jar"));

        assertEquals(
                List.of(new FileOperation.Restore("unrelated.jar")),
                Materialization.restore(current, initial));
    }

    @Test
    void restoringAnUntouchedInstanceDoesNothing() {
        InstanceState initial = state(List.of("a.jar"), List.of("b.jar"), List.of());
        assertTrue(Materialization.restore(initial, initial).isEmpty());
    }

    /**
     * The real guard. A rename that silently failed leaves a mods folder that boots perfectly and
     * produces a completely plausible run of the wrong stack — nothing downstream can detect it.
     */
    @Test
    void verifyPassesWhenTheFolderIsExactlyTheArm() {
        Materialization.verify(
                state(List.of("a.jar"), List.of("b.jar"), List.of()), Set.of("a.jar"));
    }

    @Test
    void verifyNamesWhatIsUnexpectedlyLoadable() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () ->
                                Materialization.verify(
                                        state(List.of("a.jar", "b.jar"), List.of(), List.of()),
                                        Set.of("a.jar")));
        assertTrue(e.getMessage().contains("unexpectedly loadable [b.jar]"), e.getMessage());
    }

    @Test
    void verifyNamesWhatIsMissing() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () ->
                                Materialization.verify(
                                        state(List.of("a.jar"), List.of("b.jar"), List.of()),
                                        Set.of("a.jar", "b.jar")));
        assertTrue(e.getMessage().contains("missing [b.jar]"), e.getMessage());
    }

    /** The suffix is a state, not part of a mod's identity. */
    @Test
    void modNamesNeverCarryTheDisabledSuffix() {
        assertThrows(HarnessException.class, () -> new ModFile("a.jar.disabled", "a".repeat(64)));
        assertEquals("a.jar", ModFile.stripDisabled("a.jar.disabled"));
        assertEquals("a.jar", ModFile.stripDisabled("a.jar"));
        assertEquals("a.jar.disabled", mod("a.jar").disabledName());
    }

    /** A hash alone cannot tell the loader what to load; a name alone cannot detect a swap. */
    @Test
    void modFilesNeedBothANameAndAHash() {
        assertThrows(HarnessException.class, () -> new ModFile("a.jar", "not-a-hash"));
        assertThrows(HarnessException.class, () -> new ModFile("", "a".repeat(64)));
    }

    /** Same names in the same places, different bytes: a jar swapped underneath a run. */
    @Test
    void matchingComparesContentAsWellAsPlacement() {
        InstanceState before = state(List.of("a.jar"), List.of(), List.of());
        InstanceState after =
                new InstanceState(List.of(new ModFile("a.jar", "b".repeat(64))), List.of(), List.of());

        assertTrue(before.matches(before));
        assertTrue(!before.matches(after), "a replaced jar must not read as the same state");
    }
}
