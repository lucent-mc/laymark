package cx.mia.lucent.laymark.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.experiment.Arm;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The unattended policy for a failed arm, which is what a headless run and an unanswered dialog
 * both fall back to.
 *
 * <p>A candidate is skipped: losing one comparison costs one comparison, and "this mod cannot
 * complete a run" is itself a finding. Anything else stops, because every candidate in the lap is
 * measured against the baseline and a lap without one means nothing.
 */
class RecoveryPolicyTest {

    private final ExperimentListener unattended = ExperimentListener.none();

    @Test
    void aFailedCandidateIsSkipped() {
        assertEquals(
                ExperimentListener.Recovery.SKIP,
                unattended.armFailed(
                        7, new Arm("some-mod.jar", Arm.Kind.CANDIDATE, Set.of()), "went silent"));
    }

    @Test
    void aFailedBaselineStopsTheRun() {
        assertEquals(
                ExperimentListener.Recovery.STOP,
                unattended.armFailed(
                        7, new Arm("baseline", Arm.Kind.BASELINE, Set.of()), "went silent"));
        assertEquals(
                ExperimentListener.Recovery.STOP,
                unattended.armFailed(
                        0, new Arm("acclimation", Arm.Kind.ACCLIMATION, Set.of()), "went silent"));
    }

    @Test
    void everyRecoveryIsSomethingTheDriverActsOn() {
        // A fourth constant would be a branch the driver does not have; the three are the whole
        // vocabulary the failure dialog offers.
        assertEquals(3, ExperimentListener.Recovery.values().length);
        assertTrue(
                Set.of(ExperimentListener.Recovery.values())
                        .containsAll(
                                Set.of(
                                        ExperimentListener.Recovery.RETRY,
                                        ExperimentListener.Recovery.SKIP,
                                        ExperimentListener.Recovery.STOP)));
    }
}
