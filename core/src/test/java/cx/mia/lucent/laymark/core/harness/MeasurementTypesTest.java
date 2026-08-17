package cx.mia.lucent.laymark.core.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The value types a measurement is made of, and the invariants they refuse to bend. */
class MeasurementTypesTest {

    private static List<FrameSample> samples(long... millis) {
        List<FrameSample> samples = new ArrayList<>();
        for (int i = 0; i < millis.length; i++) {
            samples.add(new FrameSample(i * 1_000_000L, millis[i] * 1_000_000L));
        }
        return samples;
    }

    @Test
    void percentilesAreNearestRankSoNoInventedFrameTimeReachesAReport() {
        double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5, FrameStatistics.percentile(sorted, 50));
        assertEquals(10, FrameStatistics.percentile(sorted, 100));
        assertEquals(1, FrameStatistics.percentile(sorted, 0), "rank 0 clamps to the fastest frame");
    }

    /**
     * The whole reason samples are kept individually: one 200ms frame in a hundred moves the mean
     * by 2ms and the maximum by 190ms. A benchmark that reported only the mean would call this
     * distribution smooth.
     */
    @Test
    void aRareSlowFrameBarelyMovesTheMeanAndDominatesTheTail() {
        long[] frames = new long[100];
        java.util.Arrays.fill(frames, 10L);
        frames[42] = 200L;

        FrameStatistics stats = FrameStatistics.of(samples(frames));

        assertEquals(11.9, stats.meanMillis(), 0.001);
        assertEquals(10, stats.p50Millis());
        assertEquals(200, stats.maxMillis());
    }

    @Test
    void refusesToSummariseNothing() {
        assertThrows(HarnessException.class, () -> FrameStatistics.of(List.of()));
        assertThrows(HarnessException.class, () -> FrameStatistics.of(null));
    }

    /** A frame that took no time is a broken timer, not a fast frame. */
    @Test
    void rejectsImpossibleFrames() {
        assertThrows(HarnessException.class, () -> new FrameSample(0, 0));
        assertThrows(HarnessException.class, () -> new FrameSample(-1, 1000));
    }

    /** Vanilla substitutes its default for an unacceptable value; clamping here would hide that. */
    @Test
    void presetRefusesOutOfRangeValuesRatherThanClampingThem() {
        Preset base = Preset.defaults();
        assertThrows(HarnessException.class, () -> withRenderDistance(base, 1));
        assertThrows(HarnessException.class, () -> withRenderDistance(base, 64));
        assertEquals(32, withRenderDistance(base, 32).renderDistance());
    }

    private static Preset withRenderDistance(Preset base, int renderDistance) {
        return new Preset(
                renderDistance,
                base.simulationDistance(),
                base.framerateLimit(),
                base.vsync(),
                base.particles(),
                base.clouds(),
                base.entityShadows(),
                base.biomeBlendRadius(),
                base.fieldOfView());
    }

    @Test
    void readbackNamesEverySettingTheGameDidNotHonour() {
        Preset requested = Preset.defaults();
        PresetReadback honoured = new PresetReadback(requested, 1920, 1080, false);
        assertTrue(honoured.honoured(requested));

        PresetReadback drifted =
                new PresetReadback(withRenderDistance(requested, 8), 1920, 1080, false);
        assertFalse(drifted.honoured(requested));
        assertEquals(1, drifted.deviationsFrom(requested).size());
        assertTrue(drifted.deviationsFrom(requested).get(0).contains("requested 12, got 8"));
    }

    @Test
    void levelIdsAreUniquePerRepetitionAndSafeOnDisk() {
        WorldSpec first = WorldSpec.forRepetition("run-1", "resident", 1, 7);
        WorldSpec second = WorldSpec.forRepetition("run-1", "resident", 2, 7);

        assertFalse(first.levelId().equals(second.levelId()));
        assertTrue(WorldSpec.isDisposable(first.levelId()));
        assertTrue(first.levelId().matches("[A-Za-z0-9._-]+"), first.levelId());
    }

    /** The level id becomes a directory, so a scenario id with a slash must not escape it. */
    @Test
    void sanitisesScenarioIdsThatWouldReachOutsideTheSavesDirectory() {
        WorldSpec spec = WorldSpec.forRepetition("run-1", "../../etc", 1, 0);
        assertFalse(spec.levelId().contains("/"), spec.levelId());
        assertFalse(spec.levelId().contains(".."), spec.levelId());
        assertThrows(HarnessException.class, () -> new WorldSpec("../escape", 0, false));
    }

    @Test
    void poseRejectsRotationsThatCannotExist() {
        assertThrows(HarnessException.class, () -> new Pose(0, 0, 0, 0f, 120f));
        assertThrows(HarnessException.class, () -> new Pose(Double.NaN, 0, 0, 0f, 0f));
        assertEquals(Pose.LOOKING_DOWN, Pose.lookingDown(0, 0, 0).pitch());
    }

    @Test
    void poseReportsTheChunkItStandsIn() {
        assertEquals(-1, Pose.lookingDown(-0.5, 100, -0.5).chunkX());
        assertEquals(6, Pose.lookingDown(100.5, 100, 100.5).chunkZ());
    }
}
