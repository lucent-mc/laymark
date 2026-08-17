package cx.mia.lucent.laymark.core.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.GpuSample;
import cx.mia.lucent.laymark.core.harness.Measurement;
import cx.mia.lucent.laymark.core.harness.MemorySnapshot;
import cx.mia.lucent.laymark.core.harness.SparkStatistics;
import cx.mia.lucent.laymark.core.harness.Throttle;

import cx.mia.lucent.laymark.core.harness.TimingChannel;
import cx.mia.lucent.laymark.core.harness.WorkCounters;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The harness writes this file and the runner reads it. They are different processes, so the
 * document is the only thing they agree on -- which makes round-tripping the property under test.
 */
class ResultCodecTest {

    private static RunResult sample() {
        return new RunResult(
                "run-abc",
                Laymark.PROTOCOL_VERSION,
                List.of(
                        ScenarioResult.completed(
                                "resident",
                                1,
                                new PresetReadback(Preset.defaults(), 2560, 1440, false),
                                List.of(),
                                new Measurement(
                                        List.of(
                                                new FrameSample(
                                                        0, 8_100_000, 4_000_000, 2_000_000, Throttle.NONE),
                                                new FrameSample(
                                                        8_100_000,
                                                        9_400_000,
                                                        4_100_000,
                                                        2_100_000,
                                                        Throttle.SHORT_AFK)),
                                        List.of(new GpuSample(0, 3_300_000)),
                                        new SparkStatistics(
                                                20.0, 2.4, 0.9, 8.1, 2.2, 5.6, 10_000,
                                                List.of(new SparkStatistics.GcActivity("G1 Young", 4, 21))),
                                        new WorkCounters(10, 20, 30),
                                        new WorkCounters(18, 41, 55),
                                        new MemorySnapshot(1_024, 4_096),
                                        new MemorySnapshot(2_048, 4_096)),
                                5_000),
                        ScenarioResult.failed("traversal", 1, "world never became ready")),
                List.of("machine was not quiet"));
    }

    @Test
    void roundTripsEverythingIncludingFailuresAndFlags() {
        RunResult original = sample();
        assertEquals(original, ResultCodec.read(ResultCodec.write(original)));
    }

    @Test
    void keepsRawSamplesRatherThanOnlyASummary() {
        Measurement read = ResultCodec.read(ResultCodec.write(sample())).scenarios().get(0).measurement();
        assertEquals(2, read.frames().size());
        assertEquals(8_100_000, read.frames().get(0).intervalNanos());
    }

    /** Every channel round-trips, or a result written today is unreadable by a reader tomorrow. */
    @Test
    void carriesEveryChannelThroughTheDocument() {
        Measurement read = ResultCodec.read(ResultCodec.write(sample())).scenarios().get(0).measurement();

        assertEquals(4_000_000, read.frames().get(0).renderCallNanos());
        assertEquals(2_000_000, read.frames().get(0).submitNanos());
        assertEquals(Throttle.SHORT_AFK, read.frames().get(1).throttle());
        assertEquals(1, read.gpu().size());
        assertEquals(new WorkCounters(8, 21, 25), read.work());
        assertEquals(1024, read.memoryAfter().minus(read.memoryBefore()).heapUsedBytes());

        // Spark's figures describe a rolling window, so the window length has to survive with
        // them or a reader cannot tell what period the numbers cover.
        assertEquals(20.0, read.spark().ticksPerSecond());
        assertEquals(2.4, read.spark().millisPerTickMean());
        assertEquals(10_000, read.spark().windowMillis());
        assertEquals(4, read.spark().totalCollections());
        assertEquals("G1 Young", read.spark().gc().get(0).collector());
    }

    /** A machine without Spark installed produces a result, not a failure. */
    @Test
    void survivesAnAbsentSparkChannel() {
        Measurement withoutSpark =
                new Measurement(
                        List.of(FrameSample.interval(0, 8_000_000)),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null);
        RunResult result =
                new RunResult(
                        "r",
                        1,
                        List.of(
                                ScenarioResult.completed(
                                        "s",
                                        1,
                                        new PresetReadback(Preset.defaults(), 800, 600, false),
                                        List.of(),
                                        withoutSpark,
                                        1)),
                        List.of("server statistics unavailable: spark is not installed"));

        RunResult read = ResultCodec.read(ResultCodec.write(result));
        assertEquals(result, read);
        assertTrue(read.complete(), "a missing diagnostic channel does not invalidate a run");
    }

    /** Channels nest, so a report can only be read if the reader knows which is which. */
    @Test
    void summarisesEachTimingChannelSeparately() {
        Measurement measurement = sample().scenarios().get(0).measurement();

        double interval = measurement.frameStatistics(TimingChannel.INTERVAL).meanMillis();
        double renderCall = measurement.frameStatistics(TimingChannel.RENDER_CALL).meanMillis();
        double submit = measurement.frameStatistics(TimingChannel.SUBMIT).meanMillis();

        assertTrue(interval > renderCall, "the render call is a strict subset of the frame");
        assertTrue(renderCall > submit, "submission is a strict subset of the render call");
    }

    /**
     * A reader can only tell "no failure" from "field not written by that version" if the field is
     * always present.
     */
    @Test
    void writesAbsentFieldsExplicitly() {
        assertTrue(ResultCodec.write(sample()).contains("\"failureReason\": null"));
    }

    @Test
    void rejectsDocumentsThatAreNotResults() {
        assertThrows(HarnessException.class, () -> ResultCodec.read(null));
        assertThrows(HarnessException.class, () -> ResultCodec.read("  "));
        assertThrows(HarnessException.class, () -> ResultCodec.read("not json"));
        assertThrows(HarnessException.class, () -> ResultCodec.read("[1,2,3]"));
    }

    /** Gson rewraps what a compact constructor throws; a malformed result must still read as one. */
    @Test
    void validationFailuresSurviveDeserialization() {
        String noRunId =
                """
                {"protocolVersion":1,"scenarios":[],"flags":[]}
                """;
        HarnessException e = assertThrows(HarnessException.class, () -> ResultCodec.read(noRunId));
        assertTrue(e.getMessage().contains("run id"), e.getMessage());
    }

    @Test
    void completenessDistinguishesAFullRunFromAPartialOne() {
        assertTrue(
                new RunResult(
                                "r",
                                1,
                                List.of(
                                        ScenarioResult.completed(
                                                "s",
                                                1,
                                                new PresetReadback(Preset.defaults(), 800, 600, false),
                                                List.of(),
                                                new Measurement(
                                                        List.of(FrameSample.interval(0, 1)),
                                                        List.of(),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null),
                                                1))
                                ,
                                List.of())
                        .complete());
        assertEquals(1, sample().failures().size());
    }
}
