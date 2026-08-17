package cx.mia.lucent.laymark.core.harness;

import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link HarnessPort} that records what it was asked to do and answers plausibly.
 *
 * <p>Exists so the run sequence can be tested at all. The alternative is asserting on ordering by
 * launching Minecraft, which takes minutes per attempt and needs a GPU — which in practice means
 * the ordering never gets asserted on.
 */
final class RecordingPort implements HarnessPort {

    /** Method names in call order, the property most of these tests are actually about. */
    final List<String> calls = new ArrayList<>();

    final List<WorldSpec> created = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    Preset lastApplied;
    Pose lastPose;

    /** What {@link #readPreset} claims the game ended up with. Null means "exactly as asked". */
    Preset effective;

    int framesPerCapture = 120;
    RuntimeException failOn;
    String failOnCall;
    RuntimeException failOnDelete;

    @Override
    public void applyPreset(Preset preset) {
        record("applyPreset");
        lastApplied = preset;
    }

    @Override
    public PresetReadback readPreset(Preset requested) {
        record("readPreset");
        return new PresetReadback(effective != null ? effective : requested, 1920, 1080, false);
    }

    @Override
    public void createWorld(WorldSpec spec) {
        record("createWorld");
        created.add(spec);
    }

    /** Negative preconditions default to satisfied; a test flips one to prove it is enforced. */
    boolean targetUngenerated = true;
    boolean targetUnmeshed = true;
    final List<ScenePlacement> placed = new ArrayList<>();

    @Override
    public BarrierReport awaitReady(Duration timeout) {
        record("awaitReady");
        return new BarrierReport(
                List.of(new BarrierReport.Condition("all sections built", 120, true)), 5, 200);
    }

    @Override
    public boolean targetIsUngenerated(Pose pose) {
        record("targetIsUngenerated");
        return targetUngenerated;
    }

    @Override
    public boolean targetHasNoBuiltSections(Pose pose) {
        record("targetHasNoBuiltSections");
        return targetUnmeshed;
    }

    @Override
    public void placeContent(List<ScenePlacement> content) {
        record("placeContent");
        placed.addAll(content);
    }

    @Override
    public void position(Pose pose) {
        record("position");
        lastPose = pose;
    }

    /** Throttle to report on every captured frame, so the sequence's flagging can be exercised. */
    Throttle throttle = Throttle.NONE;

    /** Null models a machine without Spark installed, which must not fail a run. */
    SparkStatistics spark =
            new SparkStatistics(
                    20.0,
                    2.4,
                    0.9,
                    8.1,
                    2.2,
                    5.6,
                    10_000,
                    List.of(new SparkStatistics.GcActivity("G1 Young Generation", 4, 21)));

    @Override
    public Measurement capture(Duration duration) {
        record("capture");
        List<FrameSample> samples = new ArrayList<>();
        List<GpuSample> gpu = new ArrayList<>();
        for (int i = 0; i < framesPerCapture; i++) {
            samples.add(
                    new FrameSample(i * 8_000_000L, 8_000_000L + i, 4_000_000L, 2_000_000L, throttle));
            gpu.add(new GpuSample(i * 8_000_000L, 3_000_000L));
        }
        return new Measurement(
                samples,
                gpu,
                spark,
                new WorkCounters(100, 200, 300),
                new WorkCounters(140, 260, 380),
                new MemorySnapshot(1_000, 4_000),
                new MemorySnapshot(2_000, 4_000));
    }

    @Override
    public void closeWorld() {
        record("closeWorld");
    }

    @Override
    public void deleteWorld(String levelId) {
        record("deleteWorld");
        if (failOnDelete != null) {
            throw failOnDelete;
        }
        deleted.add(levelId);
    }

    private void record(String call) {
        calls.add(call);
        if (call.equals(failOnCall) && failOn != null) {
            throw failOn;
        }
    }
}
