package cx.mia.lucent.laymark.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The config is a living document and the plan is an archive. These tests are mostly about the
 * one-way door between them.
 */
class ScenarioConfigTest {

    private static RunPlan resolve(ScenarioConfig config) {
        return config.resolve("run-1", "/out");
    }

    /** The shortest valid scenario is an id. Everything else has a defensible default. */
    @Test
    void aScenarioNeedsOnlyAnId() {
        RunPlan plan = resolve(ScenarioConfig.of(ScenarioDefinition.of("resident")));

        ScenarioSpec only = plan.scenarios().get(0);
        assertEquals("resident", only.id());
        assertEquals(1, only.repetitions());
        assertEquals(Phase.RESIDENT_RENDER, only.phase());
        assertEquals(Preset.defaults(), only.preset());
        assertTrue(only.stopCondition().kind() == StopCondition.Kind.TIME);
    }

    /**
     * The whole reason the two types are separate: a plan must not point at a name whose meaning
     * the operator can change after the results were written.
     */
    @Test
    void resolvingExpandsNamedPresetsIntoTheirValues() {
        Preset lowDetail =
                new Preset(
                        4,
                        5,
                        Preset.UNLIMITED_FRAMERATE,
                        false,
                        Preset.ParticleDetail.MINIMAL,
                        Preset.CloudDetail.OFF,
                        false,
                        0,
                        70);
        ScenarioConfig config =
                new ScenarioConfig(
                        ScenarioConfig.SCHEMA_VERSION,
                        Map.of("low", lowDetail),
                        List.of(
                                new ScenarioDefinition(
                                        "s", List.of(), null, null, null, PresetRef.named("low"), null, null,
                                        null, null)));

        ScenarioSpec resolved = resolve(config).scenarios().get(0);

        assertEquals(lowDetail, resolved.preset(), "the values travel, not the name");
        assertEquals(4, resolved.preset().renderDistance());
    }

    @Test
    void namingAPresetThatDoesNotExistSaysWhatDoes() {
        ScenarioConfig config =
                new ScenarioConfig(
                        ScenarioConfig.SCHEMA_VERSION,
                        Map.of("low", Preset.defaults()),
                        List.of(
                                new ScenarioDefinition(
                                        "s", List.of(), null, null, null, PresetRef.named("lwo"), null, null,
                                        null, null)));

        PlanException e = assertThrows(PlanException.class, () -> resolve(config));
        assertTrue(e.getMessage().contains("lwo"), e.getMessage());
        assertTrue(e.getMessage().contains("low"), "the error should name what is available");
    }

    /**
     * The union makes "named and inline at once" unrepresentable, so there is no rule to test --
     * only that each spelling still resolves.
     */
    @Test
    void inlineSettingsResolveWithoutAPresetMap() {
        Preset inline = Preset.defaults();
        ScenarioSpec resolved =
                resolve(
                                ScenarioConfig.of(
                                        new ScenarioDefinition(
                                                "s", List.of(), null, null, null,
                                                PresetRef.inline(inline), null, null, null, null)))
                        .scenarios()
                        .get(0);
        assertEquals(inline, resolved.preset());
    }

    @Test
    void refusesDuplicateScenarioIds() {
        PlanException e =
                assertThrows(
                        PlanException.class,
                        () ->
                                ScenarioConfig.of(
                                        ScenarioDefinition.of("same"), ScenarioDefinition.of("same")));
        assertTrue(e.getMessage().contains("more than once"), e.getMessage());
    }

    @Test
    void refusesAnEmptyConfig() {
        assertThrows(
                PlanException.class,
                () -> new ScenarioConfig(ScenarioConfig.SCHEMA_VERSION, Map.of(), List.of()));
    }

    /** A future schema read by an older build would be read wrongly, not partially. */
    @Test
    void refusesAnUnsupportedSchemaVersion() {
        PlanException e =
                assertThrows(
                        PlanException.class,
                        () ->
                                new ScenarioConfig(
                                        99, Map.of(), List.of(ScenarioDefinition.of("s"))));
        assertTrue(e.getMessage().contains("99"), e.getMessage());
    }

    /** Spawn generation measures world creation, so it cannot start in someone else's world. */
    @Test
    void refusesSpawnGenerationThatReusesAWorld() {
        ScenarioConfig config =
                ScenarioConfig.of(
                        ScenarioDefinition.of("first"),
                        new ScenarioDefinition(
                                "second",
                                List.of("first"),
                                Phase.SPAWN_GENERATION,
                                null, null, null, null, null, null, null));

        PlanException e = assertThrows(PlanException.class, () -> resolve(config));
        assertTrue(e.getMessage().contains("cannot reuse a world"), e.getMessage());
    }

    @Test
    void carriesPhasePoseSeedAndContentThrough() {
        ScenarioDefinition definition =
                new ScenarioDefinition(
                        "traversal",
                        List.of(),
                        Phase.UNGENERATED_TRAVERSAL,
                        StopSpec.of(StopCondition.Kind.CHUNKS, 512, 120_000),
                         3,
                        null,
                        Pose.lookingDown(2048.5, 500, 2048.5),
                        4242L,
                        true,
                        List.of(new ScenePlacement("scenes/pen.schem", 0, 64, 0)));

        ScenarioSpec resolved = resolve(ScenarioConfig.of(definition)).scenarios().get(0);

        assertEquals(Phase.UNGENERATED_TRAVERSAL, resolved.phase());
        assertEquals(4242L, resolved.seed());
        assertEquals(3, resolved.repetitions());
        assertEquals(500, resolved.pose().y());
        assertTrue(resolved.generateStructures());
        assertEquals("scenes/pen.schem", resolved.content().get(0).schematic());
    }

    /** A shared config that only works on the machine that wrote it is not shareable. */
    @Test
    void refusesSceneReferencesThatEscapeOrAreAbsolute() {
        assertThrows(PlanException.class, () -> new ScenePlacement("/etc/passwd", 0, 0, 0));
        assertThrows(PlanException.class, () -> new ScenePlacement("C:/scenes/x.schem", 0, 0, 0));
        assertThrows(PlanException.class, () -> new ScenePlacement("../../x.schem", 0, 0, 0));
        assertThrows(PlanException.class, () -> new ScenePlacement("  ", 0, 0, 0));
    }

    /** Resolution runs the plan's own validation, so a bad graph fails here rather than in-game. */
    @Test
    void resolvingValidatesTheDependencyGraph() {
        ScenarioConfig config =
                ScenarioConfig.of(
                        new ScenarioDefinition(
                                "a", List.of("b"), null, null, null, null, null, null, null, null),
                        new ScenarioDefinition(
                                "b", List.of("a"), null, null, null, null, null, null, null, null));

        PlanException e = assertThrows(PlanException.class, () -> resolve(config));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }
}
