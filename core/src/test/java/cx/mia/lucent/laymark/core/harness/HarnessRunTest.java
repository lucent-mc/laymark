package cx.mia.lucent.laymark.core.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tier 1: the whole run sequence, no game. That is the point of {@link HarnessPort}. */
class HarnessRunTest {

    private final RecordingPort port = new RecordingPort();
    private final List<Frame> frames = new ArrayList<>();

    private static ScenarioSpec scenario(String id, int repetitions) {
        return new ScenarioSpec(
                id,
                List.of(),
                new StopCondition.FixedDuration(5_000),
                repetitions,
                Preset.defaults(),
                Pose.lookingDown(100.5, 120, -64.5),
                42L);
    }

    private RunResult run(ScenarioSpec... scenarios) {
        RunPlan plan = RunPlan.of("run-1", "/out", List.of(scenarios));
        return new HarnessRun(plan, port, frames::add).execute();
    }

    @Test
    void appliesThePresetBeforeTheWorldLoads() {
        run(scenario("resident", 1));
        assertTrue(
                port.calls.indexOf("applyPreset") < port.calls.indexOf("createWorld"),
                "render distance decides how much work world load itself does, so a preset applied"
                        + " afterwards governs everything except the load: "
                        + port.calls);
    }

    @Test
    void measuresOnlyAfterTheReadinessBarrierAndPositioning() {
        run(scenario("resident", 1));
        int capture = port.calls.indexOf("capture");
        assertTrue(port.calls.indexOf("awaitReady") < capture, port.calls.toString());
        assertTrue(port.calls.indexOf("position") < capture, port.calls.toString());
    }

    /**
     * A mod that reverts a setting does it while the world loads, not during the setter call, so a
     * readback taken straight after applying would agree with itself and prove nothing.
     */
    @Test
    void readsBackAfterTheWorldHasLoaded() {
        run(scenario("resident", 1));
        assertTrue(
                port.calls.indexOf("readPreset") > port.calls.indexOf("awaitReady"),
                port.calls.toString());
    }

    @Test
    void everyRepetitionGetsItsOwnDisposableSave() {
        run(scenario("resident", 3));
        assertEquals(3, port.created.size());
        assertEquals(
                3,
                port.created.stream().map(WorldSpec::levelId).distinct().count(),
                "two phases may not run on a region that was already generated");
        assertTrue(port.created.stream().allMatch(w -> WorldSpec.isDisposable(w.levelId())));
    }

    @Test
    void deletesEverySaveItCreated() {
        run(scenario("resident", 2));
        assertEquals(
                port.created.stream().map(WorldSpec::levelId).toList(),
                port.deleted,
                "a save left behind holds a lock the next repetition needs");
    }

    @Test
    void emitsTheScenarioLifecycleInOrder() {
        run(scenario("resident", 1));
        assertEquals(
                List.of("ScenarioStarted", "PhaseEntered", "PhaseEntered", "ScenarioFinished"),
                frames.stream().map(f -> f.getClass().getSimpleName()).toList());
        List<Phase> phases =
                frames.stream()
                        .filter(Frame.PhaseEntered.class::isInstance)
                        .map(f -> ((Frame.PhaseEntered) f).phase())
                        .toList();
        assertEquals(List.of(Phase.SPAWN_GENERATION, Phase.RESIDENT_RENDER), phases);
    }

    @Test
    void recordsFramesAndSummarisesThem() {
        RunResult result = run(scenario("resident", 1));
        ScenarioResult only = result.scenarios().get(0);
        assertEquals(ScenarioResult.Outcome.COMPLETED, only.outcome());
        assertEquals(120, only.statistics().count());
        assertTrue(result.complete());
    }

    /** A setting the game did not honour is recorded against the result, not thrown away. */
    @Test
    void flagsADeviationInsteadOfSilentlyAcceptingIt() {
        port.effective = new Preset(
                8, // the game gave back something other than what was asked for
                12,
                Preset.UNLIMITED_FRAMERATE,
                false,
                Preset.ParticleDetail.ALL,
                Preset.CloudDetail.FANCY,
                true,
                2,
                70);

        ScenarioResult only = run(scenario("resident", 1)).scenarios().get(0);
        assertEquals(ScenarioResult.Outcome.COMPLETED_WITH_FLAGS, only.outcome());
        assertTrue(only.flags().get(0).contains("renderDistance"), only.flags().toString());
        assertTrue(only.measured(), "a flagged run is still a measurement, just a qualified one");
    }

    /**
     * A throttle that engages mid-capture leaves real samples with an artificial ceiling in the
     * middle of them. The port refuses a window that starts throttled; this is the subtler case,
     * where only a reader can judge what the distribution is worth.
     */
    @Test
    void flagsAThrottleThatEngagedDuringTheCapture() {
        port.throttle = Throttle.SHORT_AFK;

        ScenarioResult only = run(scenario("resident", 1)).scenarios().get(0);

        assertEquals(ScenarioResult.Outcome.COMPLETED_WITH_FLAGS, only.outcome());
        assertTrue(only.flags().toString().contains("SHORT_AFK"), only.flags().toString());
        assertTrue(only.measured(), "throttled samples are still samples, just qualified ones");
    }

    /** Every channel reaches the result, whatever the scenario asked to be scored on. */
    @Test
    void recordsEveryChannelNotJustTheScoredOne() {
        Measurement measurement = run(scenario("resident", 1)).scenarios().get(0).measurement();

        assertFalse(measurement.frames().isEmpty());
        assertFalse(measurement.gpu().isEmpty(), "gpu timings are recorded, not requested");
        assertEquals(20.0, measurement.spark().ticksPerSecond());
        assertEquals(new WorkCounters(40, 60, 80), measurement.work());
        assertTrue(measurement.millisPerChunkReceived() > 0, "the duration-independent quantity");
    }

    @Test
    void aFailedRepetitionIsRecordedRatherThanAbandoningTheRun() {
        port.failOnCall = "awaitReady";
        port.failOn = new HarnessException("world never became ready");

        RunResult result = run(scenario("a", 1), scenario("b", 1));

        assertEquals(2, result.scenarios().size(), "the run continues past a failure");
        assertEquals(2, result.failures().size());
        assertFalse(result.complete());
        assertTrue(result.failures().get(0).failureReason().contains("never became ready"));
    }

    /** Cleanup must survive the failure that made it necessary. */
    @Test
    void discardsTheSaveEvenWhenTheRepetitionFailed() {
        port.failOnCall = "capture";
        port.failOn = new HarnessException("renderer died");

        run(scenario("resident", 1));

        assertEquals(1, port.deleted.size(), port.calls.toString());
    }

    @Test
    void aCleanupFailureIsFlaggedWithoutLosingTheResult() {
        port.failOnDelete = new IllegalStateException("save is locked");

        RunResult result = run(scenario("resident", 1));

        assertEquals(ScenarioResult.Outcome.COMPLETED, result.scenarios().get(0).outcome());
        assertTrue(result.flags().get(0).contains("save is locked"), result.flags().toString());
    }

    /** Better a legible refusal than a number answering a question nobody asked. */
    @Test
    void refusesAStopConditionItCannotYetMeasure() {
        ScenarioSpec chunks =
                new ScenarioSpec(
                        "load-chunks",
                        List.of(),
                        new StopCondition.UntilComplete("chunks", 60_000),
                        1,
                        Preset.defaults(),
                        Pose.lookingDown(0.5, 100, 0.5),
                        1L);

        ScenarioResult only = run(chunks).scenarios().get(0);

        assertEquals(ScenarioResult.Outcome.FAILED, only.outcome());
        assertTrue(only.failureReason().contains("completion target"), only.failureReason());
        assertFalse(port.calls.contains("capture"), "it must not measure something else instead");
    }

    @Test
    void runsScenariosInDependencyOrder() {
        ScenarioSpec first = scenario("first", 1);
        ScenarioSpec second =
                new ScenarioSpec(
                        "second",
                        List.of("first"),
                        new StopCondition.FixedDuration(1_000),
                        1,
                        Preset.defaults(),
                        Pose.lookingDown(0.5, 100, 0.5),
                        1L);

        RunResult result =
                new HarnessRun(RunPlan.of("run-1", "/out", List.of(second, first)), port, frames::add)
                        .execute();

        assertEquals(
                List.of("first", "second"),
                result.scenarios().stream().map(ScenarioResult::scenarioId).toList());
    }
}
