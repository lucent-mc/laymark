package cx.mia.lucent.laymark.runner.select;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A jar shipping an FML loader-plugin service loads before mod construction and never appears in
 * the runtime mod list — the inventory check must be able to tell it apart from a jar that truly
 * failed to load. Configured Defaults is the canonical case.
 */
class JarProbeLoaderPluginTest {

    @TempDir Path dir;

    @Test
    void aLanguageLoaderServiceMarksTheJarAsPlugin() throws Exception {
        Path jar =
                jar(
                        "plugin.jar",
                        Map.of(
                                "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader",
                                "some.PluginClass",
                                "META-INF/neoforge.mods.toml",
                                "modLoader=\"javafml\"\n[[mods]]\nmodId=\"plugin\"\n"));
        assertTrue(JarProbe.loaderPlugin(jar));
    }

    @Test
    void anOrdinaryModIsNotAPlugin() throws Exception {
        Path jar =
                jar(
                        "mod.jar",
                        Map.of(
                                "META-INF/neoforge.mods.toml",
                                "modLoader=\"javafml\"\n[[mods]]\nmodId=\"mod\"\n",
                                "META-INF/services/some.other.Service",
                                "irrelevant.Class"));
        assertFalse(JarProbe.loaderPlugin(jar));
    }

    @Test
    void anUnreadableJarAnswersFalseSoSuspicionStands() {
        assertFalse(JarProbe.loaderPlugin(dir.resolve("absent.jar")));
    }

    private Path jar(String name, Map<String, String> entries) throws Exception {
        Path path = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }
}
