package cx.mia.lucent.laymark.core.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.FrameSample;
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
                                List.of(new FrameSample(0, 8_100_000), new FrameSample(8_100_000, 9_400_000)),
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
        RunResult read = ResultCodec.read(ResultCodec.write(sample()));
        assertEquals(2, read.scenarios().get(0).samples().size());
        assertEquals(8_100_000, read.scenarios().get(0).samples().get(0).durationNanos());
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
                                                List.of(new FrameSample(0, 1)),
                                                1))
                                ,
                                List.of())
                        .complete());
        assertEquals(1, sample().failures().size());
    }
}
