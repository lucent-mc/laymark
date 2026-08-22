package cx.mia.lucent.laymark.core.result;

import static org.junit.jupiter.api.Assertions.assertThrows;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.BarrierReport;
import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Measurement;
import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.MemorySnapshot;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelsTest {

    @Test
    void chunkTargetWithoutReceivedChunksCannotFallBackToFrameTime() {
        FrameStatistics frames = new FrameStatistics(1, 3, 3, 3, 3, 3, 3, 3, 3);
        PhaseResult segment =
                new PhaseResult(
                        Phase.GENERATED_STREAMING,
                        Measurement.empty(),
                        new PhaseSummaries(frames, frames, frames, null, 3, null),
                        null,
                        List.of(),
                        3);
        ScenarioResult result =
                ScenarioResult.completed(
                        "loading",
                        1,
                        Pass.WARM,
                        0,
                        null,
                        List.of(),
                        List.of(segment),
                        BarrierReport.none(),
                        3);
        ScenarioSpec scenario =
                new ScenarioSpec(
                        "loading",
                        List.of(),
                        StopCondition.chunks(1, Duration.ofSeconds(1)),
                        1,
                        Preset.ofMinecraft(Map.of("renderDistance", "12")),
                        Pose.lookingDown(0.5, 200, 0.5),
                        1,
                        List.of(Phase.GENERATED_STREAMING),
                        false,
                        List.of());
        RunPlan plan = RunPlan.of("test", "out", List.of(scenario));

        assertThrows(HarnessException.class, () -> Channels.of(result, plan));
    }

    @Test
    void exposesPostCaptureRetainedHeapAsAScoreChannel() {
        long mib = 1024L * 1024L;
        Measurement measurement =
                new Measurement(
                        List.of(FrameSample.interval(0, 10_000_000)),
                        List.of(),
                        null,
                        null,
                        null,
                        new MemorySnapshot(500 * mib, 4096 * mib),
                        new MemorySnapshot(400 * mib, 4096 * mib));
        PhaseResult segment =
                new PhaseResult(Phase.RESIDENT_RENDER, measurement, List.of(), 10);
        ScenarioResult result =
                ScenarioResult.completed(
                        "render",
                        1,
                        Pass.WARM,
                        0,
                        null,
                        List.of(),
                        List.of(segment),
                        BarrierReport.none(),
                        10);
        RunPlan plan =
                RunPlan.of(
                        "test",
                        "out",
                        List.of(
                                ScenarioSpec.of(
                                        "render",
                                        StopCondition.time(Duration.ofMillis(10)))));

        org.junit.jupiter.api.Assertions.assertEquals(
                400.0, Channels.of(result, plan).heapUsedMegabytes(), 0.001);
    }
}
