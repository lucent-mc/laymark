package cx.mia.lucent.laymark.runner.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.select.DependencyGraph;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real jars, written here rather than fetched, so the manifest shapes are exactly the ones tested. */
class JarProbeTest {

    @TempDir Path temp;

    private Path jar(String name, String entry, String contents) throws IOException {
        Path file = temp.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void readsRequiredDependenciesFromANeoForgeManifest() throws IOException {
        Path jar =
                jar(
                        "sodium.jar",
                        "META-INF/neoforge.mods.toml",
                        """
                        modLoader="javafml"
                        loaderVersion="[1,)"

                        [[mods]]
                        modId="sodium"
                        version="1.0"

                        [[dependencies.sodium]]
                        modId="neoforge"
                        type="required"

                        [[dependencies.sodium]]
                        modId="somelib"
                        type="required"

                        [[dependencies.sodium]]
                        modId="optionalthing"
                        type="optional"
                        """);

        DependencyGraph graph = JarProbe.probe(List.of(jar));

        assertEquals(
                Set.of("somelib"),
                graph.directRequirementsOf("sodium"),
                "optional dependencies do not have to be present, so they are not edges; and the"
                        + " loader itself is not a mod anyone can toggle");
        assertEquals(
                DependencyGraph.Provenance.JAR_METADATA, graph.provenanceOf("sodium", "somelib"));
    }

    @Test
    void readsDependenciesFromAFabricManifest() throws IOException {
        Path jar =
                jar(
                        "lithium.jar",
                        "fabric.mod.json",
                        """
                        { "id": "lithium",
                          "depends": { "fabricloader": ">=0.15", "minecraft": "*", "someapi": "*" } }
                        """);

        assertEquals(Set.of("someapi"), JarProbe.probe(List.of(jar)).directRequirementsOf("lithium"));
    }

    /**
     * A resource pack or a library with no mod manifest is an ordinary thing to find in a mods
     * folder. Refusing to run because one exists would make this unusable on real packs.
     */
    @Test
    void ignoresAJarThatDeclaresNothing() throws IOException {
        Path jar = jar("resources.jar", "pack.mcmeta", "{}");

        assertTrue(JarProbe.probe(List.of(jar)).requires().isEmpty());
    }

    @Test
    void probesEveryJarIntoOneGraph() throws IOException {
        Path a =
                jar("a.jar", "fabric.mod.json", "{\"id\":\"a\",\"depends\":{\"lib\":\"*\"}}");
        Path b =
                jar("b.jar", "fabric.mod.json", "{\"id\":\"b\",\"depends\":{\"lib\":\"*\"}}");

        DependencyGraph graph = JarProbe.probe(List.of(a, b));

        assertEquals(Set.of("lib"), graph.directRequirementsOf("a"));
        assertEquals(Set.of("lib"), graph.directRequirementsOf("b"));
    }

    /** Single quotes and loose spacing both appear in manifests written by hand. */
    @Test
    void toleratesTheSpacingRealManifestsUse() throws IOException {
        Path jar =
                jar(
                        "spaced.jar",
                        "META-INF/neoforge.mods.toml",
                        """
                        [[mods]]
                        modId = 'spaced'

                        [[dependencies.spaced]]
                        modId = 'needed'
                        type = 'required'
                        """);

        assertEquals(Set.of("needed"), JarProbe.probe(List.of(jar)).directRequirementsOf("spaced"));
    }
}
