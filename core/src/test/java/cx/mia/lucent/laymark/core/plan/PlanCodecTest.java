package cx.mia.lucent.laymark.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PlanCodecTest {

    /**
     * A resolved scenario spells out its preset and pose in full. Hand-written fixtures below
     * carry them so they exercise the property under test rather than tripping over an incomplete
     * scenario first.
     */
    private static final String PRESET_AND_POSE =
            """
            "preset":{"renderDistance":12,"simulationDistance":12,"framerateLimit":260,
             "vsync":false,"particles":"ALL","clouds":"FANCY",
             "entityShadows":true,"biomeBlendRadius":2,"fieldOfView":70},
            "pose":{"x":0.5,"y":200.0,"z":0.5,"yaw":0.0,"pitch":90.0},"seed":0
            """;

    static Stream<StopCondition> everyStopCondition() {
        return Stream.of(
                new StopCondition.FixedDuration(45_000),
                new StopCondition.UntilComplete("all-chunks-loaded", 600_000));
    }

    private static RunPlan planWith(StopCondition stopCondition) {
        return RunPlan.of(
                "run-abc", "out/experiments", List.of(ScenarioSpec.of("s1", stopCondition)));
    }

    @ParameterizedTest
    @MethodSource("everyStopCondition")
    void roundTripsEveryStopCondition(StopCondition stopCondition) {
        RunPlan original = planWith(stopCondition);
        assertEquals(original, PlanCodec.read(PlanCodec.write(original)));
    }

    /**
     * The registry is hand-maintained. A variant added without registering it would write fine
     * and fail on read -- and the read happens in the game, after a launch has been paid for.
     */
    @Test
    void registryCoversEverySealedVariant() {
        var unregistered =
                Stream.of(StopCondition.class.getPermittedSubclasses())
                        .filter(
                                type ->
                                        everyStopCondition()
                                                .noneMatch(s -> s.getClass().equals(type)))
                        .map(Class::getSimpleName)
                        .toList();
        assertTrue(
                unregistered.isEmpty(),
                "stop conditions with no round-trip coverage: " + unregistered);
    }

    @Test
    void writesADiscriminator() {
        String json = PlanCodec.write(planWith(new StopCondition.FixedDuration(1000)));
        assertTrue(json.contains("\"kind\""), json);
        assertTrue(json.contains("fixed-duration"), json);
    }

    @Test
    void rejectsUnknownStopConditionKind() {
        String json =
                PlanCodec.write(planWith(new StopCondition.FixedDuration(1000)))
                        .replace("fixed-duration", "until-the-heat-death");
        PlanException e = assertThrows(PlanException.class, () -> PlanCodec.read(json));
        assertTrue(e.getMessage().contains("until-the-heat-death"), e.getMessage());
        assertTrue(e.getMessage().contains("known kinds"), "the error should say what is valid");
    }

    @Test
    void rejectsMissingDiscriminator() {
        String json =
                """
                {"runId":"r","protocolVersion":1,"outputDirectory":"o",
                 "scenarios":[{"id":"s","dependsOn":[],"repetitions":1,
                 "stopCondition":{"millis":1000}}]}
                """;
        assertThrows(PlanException.class, () -> PlanCodec.read(json));
    }

    /**
     * A plan loaded from disk must fail the same way as the identical plan built in code.
     *
     * <p>Gson invokes the canonical constructor but rewraps whatever it throws, so without
     * unwrapping, a cycle read from a file would escape as a bare runtime exception and slip
     * past every {@code catch (PlanException)} the callers are written around.
     */
    @Test
    void validationFailuresSurviveDeserializationAsPlanException() {
        String cyclic =
                """
                {"runId":"r","protocolVersion":1,"outputDirectory":"o","scenarios":[
                  {"id":"a","dependsOn":["b"],"repetitions":1,
                   "stopCondition":{"kind":"fixed-duration","millis":1000},PRESET},
                  {"id":"b","dependsOn":["a"],"repetitions":1,
                   "stopCondition":{"kind":"fixed-duration","millis":1000},PRESET}]}
                """
                        .replace("PRESET", PRESET_AND_POSE);
        PlanException e = assertThrows(PlanException.class, () -> PlanCodec.read(cyclic));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void rejectsAScenarioWithNoPreset() {
        String json =
                """
                {"runId":"r","protocolVersion":1,"outputDirectory":"o","scenarios":[
                  {"id":"s","dependsOn":[],"repetitions":1,
                   "stopCondition":{"kind":"fixed-duration","millis":1000}}]}
                """;
        PlanException e = assertThrows(PlanException.class, () -> PlanCodec.read(json));
        assertTrue(e.getMessage().contains("preset"), e.getMessage());
    }

    /**
     * The preset validates itself and reports a harness failure. At read time that is a malformed
     * plan, and it has to reach the caller as one -- every caller here catches {@link
     * PlanException} and nothing else.
     */
    @Test
    void anInvalidPresetFromDiskFailsAsAPlanProblem() {
        String json =
                """
                {"runId":"r","protocolVersion":1,"outputDirectory":"o","scenarios":[
                  {"id":"s","dependsOn":[],"repetitions":1,
                   "stopCondition":{"kind":"fixed-duration","millis":1000},PRESET}]}
                """
                        .replace("PRESET", PRESET_AND_POSE)
                        .replace("\"renderDistance\":12", "\"renderDistance\":900");
        PlanException e = assertThrows(PlanException.class, () -> PlanCodec.read(json));
        assertTrue(e.getMessage().contains("renderDistance"), e.getMessage());
    }

    @Test
    void rejectsInvalidRepetitionsFromDisk() {
        String json =
                """
                {"runId":"r","protocolVersion":1,"outputDirectory":"o","scenarios":[
                  {"id":"s","dependsOn":[],"repetitions":0,
                   "stopCondition":{"kind":"fixed-duration","millis":1000}}]}
                """;
        PlanException e = assertThrows(PlanException.class, () -> PlanCodec.read(json));
        assertTrue(e.getMessage().contains("repetition"), e.getMessage());
    }

    @Test
    void rejectsCompletionTargetWithoutTimeoutFromDisk() {
        String json =
                """
                {"runId":"r","protocolVersion":1,"outputDirectory":"o","scenarios":[
                  {"id":"s","dependsOn":[],"repetitions":1,
                   "stopCondition":{"kind":"until-complete","target":"all-chunks-loaded",
                    "timeoutMillis":0}}]}
                """;
        PlanException e = assertThrows(PlanException.class, () -> PlanCodec.read(json));
        assertTrue(e.getMessage().contains("timeout"), e.getMessage());
    }

    @Test
    void rejectsEmptyAndMalformedDocuments() {
        assertThrows(PlanException.class, () -> PlanCodec.read(null));
        assertThrows(PlanException.class, () -> PlanCodec.read(""));
        assertThrows(PlanException.class, () -> PlanCodec.read("   "));
        assertThrows(PlanException.class, () -> PlanCodec.read("not json"));
        assertThrows(PlanException.class, () -> PlanCodec.read("[1,2,3]"));
    }
}
