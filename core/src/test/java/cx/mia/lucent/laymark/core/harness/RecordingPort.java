package cx.mia.lucent.laymark.core.harness;

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

    @Override
    public void awaitReady(Duration timeout) {
        record("awaitReady");
    }

    @Override
    public void position(Pose pose) {
        record("position");
        lastPose = pose;
    }

    @Override
    public List<FrameSample> capture(Duration duration) {
        record("capture");
        List<FrameSample> samples = new ArrayList<>();
        for (int i = 0; i < framesPerCapture; i++) {
            samples.add(new FrameSample(i * 8_000_000L, 8_000_000L + i));
        }
        return samples;
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
