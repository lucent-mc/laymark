package cx.mia.lucent.laymark.core.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.experiment.Parity;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec §9 evidence 2: every fail-closed path, exercised by injecting its failure.
 *
 * <p>These paths share the most dangerous property in the harness: each one, left unchecked,
 * produces a run that <strong>completes normally and reports plausible numbers</strong>. A test
 * that only covers the happy path would pass forever while every one of these quietly lied.
 */
class FailureInjectionTest {

    /** A port where every operation succeeds until a test injects one specific failure. */
    private static class FakePort implements HarnessPort {

        boolean targetGenerated = false;
        boolean sectionsBuilt = false;
        boolean driftAfterCapture = false;
        boolean throttleDuringCapture = false;
        boolean failCapture = false;
        int readbacks = 0;

        private Preset applied;

        private Measurement measurement() {
            List<FrameSample> frames =
                    List.of(
                            new FrameSample(0, 1_000_000, 0, 0, Throttle.NONE),
                            new FrameSample(
                                    1_000_000,
                                    1_000_000,
                                    0,
                                    0,
                                    throttleDuringCapture ? Throttle.SHORT_AFK : Throttle.NONE));
            return new Measurement(frames, List.of(), null, null, null, null, null);
        }

        @Override
        public void applyPreset(Preset preset) {
            applied = preset;
        }

        @Override
        public PresetReadback readPreset(Preset requested) {
            readbacks++;
            // Drift is injected on the re-read AFTER a capture, which is exactly the read the
            // sequence added for this hazard.
            Preset effective =
                    driftAfterCapture && readbacks > 1
                            ? new Preset(
                                    8,
                                    requested.simulationDistance(),
                                    requested.particles(),
                                    requested.clouds(),
                                    requested.entityShadows(),
                                    requested.biomeBlendRadius(),
                                    requested.fieldOfView())
                            : applied;
            return new PresetReadback(effective, 1600, 900, false);
        }

        @Override
        public void createWorld(WorldSpec spec) {}

        @Override
        public void openWorld(WorldSpec spec) {}

        @Override
        public BarrierReport awaitReady(Duration timeout) {
            return BarrierReport.none();
        }

        @Override
        public void position(Pose pose) {}

        @Override
        public void teleport(Pose pose) {}

        @Override
        public void awaitStop(StopCondition stop, Pose around, int viewDistance) {
            if (failCapture) {
                throw new HarnessException("capture reached only 12 of 3725 CHUNKS within 1s");
            }
        }

        @Override
        public boolean targetIsUngenerated(Pose pose) {
            return !targetGenerated;
        }

        @Override
        public boolean targetHasNoBuiltSections(Pose pose) {
            return !sectionsBuilt;
        }

        @Override
        public void pinGameRules() {}

        @Override
        public void placeContent(List<ScenePlacement> content) {}

        @Override
        public Measurement capture(StopCondition stop, Pose around, int viewDistance) {
            if (failCapture) {
                throw new HarnessException("capture reached only 12 of 3725 CHUNKS within 1s");
            }
            return measurement();
        }

        @Override
        public void beginCapture() {}

        @Override
        public Measurement endCapture() {
            return measurement();
        }

        @Override
        public void closeWorld() {}

        @Override
        public void deleteWorld(String levelId) {}
    }

    private static ScenarioSpec scenario(String id, List<String> dependsOn, Phase phase) {
        return new ScenarioSpec(
                id,
                dependsOn,
                phase == Phase.RESIDENT_RENDER
                        ? StopCondition.time(Duration.ofSeconds(1))
                        : StopCondition.chunks(100, Duration.ofSeconds(1)),
                1,
                Preset.defaults(),
                Pose.lookingDown(0.5, 200, 0.5),
                1L,
                List.of(phase),
                false,
                List.of());
    }

    private static RunResult run(FakePort port, ScenarioSpec... scenarios) {
        RunPlan plan = RunPlan.of("test", "out", List.of(scenarios));
        return new HarnessRun(plan, port, frame -> {}).execute();
    }

    private static ScenarioResult only(RunResult result, String id) {
        return result.scenarios().stream()
                .filter(s -> s.scenarioId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void traversalFailsWhenTargetAlreadyGenerated() {
        FakePort port = new FakePort();
        port.targetGenerated = true;
        var result = run(port, scenario("gen", List.of(), Phase.UNGENERATED_TRAVERSAL));
        var scenario = only(result, "gen");
        assertEquals(ScenarioResult.Outcome.FAILED, scenario.outcome());
        assertTrue(scenario.failureReason().contains("already generated"));
    }

    @Test
    void streamingFailsWhenTargetNeverGenerated() {
        FakePort port = new FakePort();
        port.targetGenerated = false;
        var result = run(port, scenario("load", List.of(), Phase.GENERATED_STREAMING));
        var scenario = only(result, "load");
        assertEquals(ScenarioResult.Outcome.FAILED, scenario.outcome());
        assertTrue(scenario.failureReason().contains("never been generated"));
    }

    @Test
    void streamingFailsWhenClientAlreadyHoldsTheMeshes() {
        FakePort port = new FakePort();
        port.targetGenerated = true;
        port.sectionsBuilt = true;
        var result = run(port, scenario("load", List.of(), Phase.GENERATED_STREAMING));
        var scenario = only(result, "load");
        assertEquals(ScenarioResult.Outcome.FAILED, scenario.outcome());
        assertTrue(scenario.failureReason().contains("already meshed"));
    }

    @Test
    void settingsDriftDuringACaptureFailsTheRepetition() {
        FakePort port = new FakePort();
        port.driftAfterCapture = true;
        var result = run(port, scenario("render", List.of(), Phase.RESIDENT_RENDER));
        var scenario = only(result, "render");
        assertEquals(ScenarioResult.Outcome.FAILED, scenario.outcome());
        assertTrue(scenario.failureReason().contains("drifted"));
    }

    @Test
    void throttleEngagingMidCaptureFailsTheRepetition() {
        FakePort port = new FakePort();
        port.throttleDuringCapture = true;
        var result = run(port, scenario("render", List.of(), Phase.RESIDENT_RENDER));
        var scenario = only(result, "render");
        assertEquals(ScenarioResult.Outcome.FAILED, scenario.outcome());
        assertTrue(scenario.failureReason().contains("throttled"));
    }

    @Test
    void dependentOfAFailedDependencyFailsFastInsteadOfMeasuringTheWreckage() {
        FakePort port = new FakePort();
        port.failCapture = true;
        var result =
                run(
                        port,
                        scenario("gen", List.of(), Phase.UNGENERATED_TRAVERSAL),
                        scenario("load", List.of("gen"), Phase.GENERATED_STREAMING));
        assertEquals(ScenarioResult.Outcome.FAILED, only(result, "gen").outcome());
        var dependent = only(result, "load");
        assertEquals(ScenarioResult.Outcome.FAILED, dependent.outcome());
        assertTrue(dependent.failureReason().contains("did not complete"));
    }

    @Test
    void aFailedScenarioDoesNotAbandonTheRestOfTheRun() {
        FakePort port = new FakePort();
        port.targetGenerated = true; // fails the traversal, not the render
        var result =
                run(
                        port,
                        scenario("gen", List.of(), Phase.UNGENERATED_TRAVERSAL),
                        scenario("render", List.of(), Phase.RESIDENT_RENDER));
        assertEquals(ScenarioResult.Outcome.FAILED, only(result, "gen").outcome());
        assertEquals(ScenarioResult.Outcome.COMPLETED, only(result, "render").outcome());
    }

    @Test
    void parityNamesTheArmThatDiffered() {
        Preset defaults = Preset.defaults();
        Preset drifted =
                new Preset(
                        8,
                        defaults.simulationDistance(),
                        defaults.particles(),
                        defaults.clouds(),
                        defaults.entityShadows(),
                        defaults.biomeBlendRadius(),
                        defaults.fieldOfView());
        var mismatches =
                Parity.compare(
                        "render",
                        "baseline",
                        new PresetReadback(defaults, 1600, 900, false),
                        "sodium",
                        new PresetReadback(drifted, 1600, 900, false));
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("sodium differs from baseline"));
        assertTrue(mismatches.get(0).contains("renderDistance"));
    }

    @Test
    void parityCatchesAFramebufferOnlyDifference() {
        Preset defaults = Preset.defaults();
        var mismatches =
                Parity.compare(
                        "render",
                        "baseline",
                        new PresetReadback(defaults, 1600, 900, false),
                        "lithium",
                        new PresetReadback(defaults, 1920, 1080, false));
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("framebuffer"));
    }
}
