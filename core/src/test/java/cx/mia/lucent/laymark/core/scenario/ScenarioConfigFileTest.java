package cx.mia.lucent.laymark.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioConfigFileTest {

    @TempDir Path instance;

    @Test
    void writesTheCommentedReferenceWhenConfigIsMissing() throws Exception {
        Path written = ScenarioConfigFile.ensureExists(instance);

        assertEquals(instance.resolve(Laymark.CONFIG_PATH), written);
        String contents = Files.readString(written, StandardCharsets.UTF_8);
        // The intro is the operator's prose and changes at their whim; what the test holds is
        // that the file opens with commentary, is the complete reference rather than a stub, and
        // parses. Pinning intro sentences here made editing the intro break the build.
        assertTrue(contents.startsWith("//"), "the reference opens with its commentary");
        assertTrue(contents.length() > 5_000, "the complete reference, not an empty config stub");
        assertEquals(2, ConfigCodec.read(contents).scenarios().size());
    }

    @Test
    void neverOverwritesAnExistingConfig() throws Exception {
        Path config = instance.resolve(Laymark.CONFIG_PATH);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "// mine\n{}\n", StandardCharsets.UTF_8);

        assertEquals(config, ScenarioConfigFile.ensureExists(instance));
        assertEquals("// mine\n{}\n", Files.readString(config, StandardCharsets.UTF_8));
    }

    @Test
    void referenceListsEverySchemaFieldAndNamedOption() throws Exception {
        String contents =
                Files.readString(
                        ScenarioConfigFile.ensureExists(instance), StandardCharsets.UTF_8);

        for (Class<?> recordType :
                new Class<?>[] {
                    ScenarioConfig.class,
                    ScenarioDefinition.class,
                    StopSpec.class,
                    Preset.class,
                    Pose.class,
                    ScenePlacement.class
                }) {
            Arrays.stream(recordType.getRecordComponents())
                    .forEach(
                            component ->
                                    assertTrue(
                                            contents.contains("\"" + component.getName() + "\""),
                                            () ->
                                                    recordType.getSimpleName()
                                                            + "."
                                                            + component.getName()
                                                            + " is absent from the reference"));
        }

        assertEnumValuesDocumented(contents, Phase.values());
        assertEnumValuesDocumented(contents, StopCondition.Kind.values());
        assertEnumValuesDocumented(contents, Preset.ParticleDetail.values());
        assertEnumValuesDocumented(contents, Preset.CloudDetail.values());
        assertTrue(contents.contains("\"all-in-radius\""));
        assertTrue(contents.contains("\"settings\": \"example\""), "named settings form");
        assertTrue(contents.contains("// \"settings\": {"), "inline settings form");
    }

    private static void assertEnumValuesDocumented(String contents, Enum<?>[] values) {
        for (Enum<?> value : values) {
            assertTrue(
                    contents.contains(value.name()),
                    () -> value.getDeclaringClass().getSimpleName() + "." + value + " is undocumented");
        }
    }
}
