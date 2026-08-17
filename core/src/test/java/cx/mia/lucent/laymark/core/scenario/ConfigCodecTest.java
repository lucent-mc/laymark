package cx.mia.lucent.laymark.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** This is the file a human edits, so the tests are largely about what happens when they typo. */
class ConfigCodecTest {

    /** What a minimal hand-written config actually looks like. */
    private static final String MINIMAL =
            """
            {
              "version": 1,
              "scenarios": [ { "id": "resident" } ]
            }
            """;

    @Test
    void readsAConfigThatOmitsEverythingOptional() {
        ScenarioConfig config = ConfigCodec.read(MINIMAL);

        assertEquals(1, config.scenarios().size());
        assertEquals("resident", config.scenarios().get(0).id());
        assertTrue(config.settingsPresets().isEmpty());
    }

    @Test
    void roundTripsAFullyPopulatedConfig() {
        ScenarioConfig original =
                new ScenarioConfig(
                        ScenarioConfig.SCHEMA_VERSION,
                        Map.of("low", Preset.defaults()),
                        List.of(
                                new ScenarioDefinition(
                                        "traversal",
                                        List.of(),
                                        Phase.UNGENERATED_TRAVERSAL,
                                        StopSpec.of(StopCondition.Kind.CHUNKS, 512, 120_000),
                                         3,
                                        PresetRef.named("low"),
                                        null,
                                         4242L,
                                        true,
                                        List.of(new ScenePlacement("scenes/pen.schem", 0, 64, 0)))));

        assertEquals(original, ConfigCodec.read(ConfigCodec.write(original)));
    }

    /** The config and the plan must agree on how a stop condition is spelled. */
    @Test
    void usesTheSameStopConditionSpellingAsThePlan() {
        String json =
                """
                {"version":1,"scenarios":[{"id":"s",
                 "stop":{"kind":"CHUNKS","target":512,"timeout":60000}}]}
                """;
        ScenarioConfig config = ConfigCodec.read(json);
        assertTrue(config.scenarios().get(0).stop().kind() == StopCondition.Kind.CHUNKS);
        assertTrue(ConfigCodec.write(config).contains("CHUNKS"));
    }

    @Test
    void rejectsAnUnknownStopConditionAndSaysWhatIsValid() {
        String json =
                """
                {"version":1,"scenarios":[{"id":"s","stop":{"kind":"when-i-say-so","target":1}}]}
                """;
        PlanException e = assertThrows(PlanException.class, () -> ConfigCodec.read(json));
        assertTrue(e.getMessage().contains("when-i-say-so"), e.getMessage());
        assertTrue(e.getMessage().contains("TIME"), "the error should name the valid kinds");
    }

    /** An unknown phase name must fail loudly, not silently become the default one. */
    @Test
    void rejectsAnUnknownPhase() {
        String json =
                """
                {"version":1,"scenarios":[{"id":"s","phase":"WARMING_UP"}]}
                """;
        assertThrows(PlanException.class, () -> ConfigCodec.read(json));
    }

    /** Preset validation applies to a config too; a typo'd number is caught before any launch. */
    @Test
    void rejectsAnOutOfRangePresetValue() {
        String json =
                """
                {"version":1,"settingsPresets":{"silly":{"renderDistance":900,"simulationDistance":12,
                 "framerateLimit":260,"vsync":false,"particles":"ALL","clouds":"FANCY",
                 "entityShadows":true,"biomeBlendRadius":2,"fieldOfView":70}},
                 "scenarios":[{"id":"s","settings":"silly"}]}
                """;
        PlanException e = assertThrows(PlanException.class, () -> ConfigCodec.read(json));
        assertTrue(e.getMessage().contains("renderDistance"), e.getMessage());
    }

    /**
     * One field, two spellings. The JSON type is the discriminator, so an operator never writes a
     * tag that exists only for the parser's benefit.
     */
    @Test
    void readsSettingsAsEitherANameOrAnObject() {
        ScenarioConfig named =
                ConfigCodec.read(
                        """
                        {"version":1,"settingsPresets":{"near":{"renderDistance":8,"simulationDistance":8,
                         "framerateLimit":260,"vsync":false,"particles":"ALL","clouds":"FANCY",
                         "entityShadows":true,"biomeBlendRadius":2,"fieldOfView":70}},
                         "scenarios":[{"id":"s","settings":"near"}]}
                        """);
        assertEquals(
                PresetRef.named("near"), named.scenarios().get(0).settings());
        assertEquals(8, named.resolve("r", "/out").scenarios().get(0).preset().renderDistance());

        ScenarioConfig inline =
                ConfigCodec.read(
                        """
                        {"version":1,"scenarios":[{"id":"s","settings":{"renderDistance":16,
                         "simulationDistance":12,"framerateLimit":260,"vsync":false,
                         "particles":"ALL","clouds":"FANCY","entityShadows":true,
                         "biomeBlendRadius":2,"fieldOfView":70}}]}
                        """);
        assertTrue(inline.scenarios().get(0).settings() instanceof PresetRef.Inline);
        assertEquals(16, inline.resolve("r", "/out").scenarios().get(0).preset().renderDistance());
    }

    /** Each spelling must come back out the way it went in, or a config rewrite would rewrite it. */
    @Test
    void writesBackTheSpellingItRead() {
        String named = ConfigCodec.write(ConfigCodec.read(
                """
                {"version":1,"settingsPresets":{"near":{"renderDistance":8,"simulationDistance":8,
                 "framerateLimit":260,"vsync":false,"particles":"ALL","clouds":"FANCY",
                 "entityShadows":true,"biomeBlendRadius":2,"fieldOfView":70}},
                 "scenarios":[{"id":"s","settings":"near"}]}
                """));
        assertTrue(named.contains("\"settings\": \"near\""), named);
    }

    @Test
    void rejectsSettingsThatAreNeitherANameNorAnObject() {
        PlanException e =
                assertThrows(
                        PlanException.class,
                        () ->
                                ConfigCodec.read(
                                        "{\"version\":1,\"scenarios\":[{\"id\":\"s\",\"settings\":42}]}"));
        assertTrue(e.getMessage().contains("settings"), e.getMessage());
    }

    @Test
    void rejectsEmptyAndMalformedDocuments() {
        assertThrows(PlanException.class, () -> ConfigCodec.read(null));
        assertThrows(PlanException.class, () -> ConfigCodec.read("   "));
        assertThrows(PlanException.class, () -> ConfigCodec.read("not json"));
        assertThrows(PlanException.class, () -> ConfigCodec.read("[1,2,3]"));
    }

    @Test
    void rejectsAConfigWithNoScenarios() {
        assertThrows(
                PlanException.class,
                () -> ConfigCodec.read("{\"version\":1,\"scenarios\":[]}"));
    }
}
