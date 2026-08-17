package cx.mia.lucent.laymark.runner;

import com.google.gson.GsonBuilder;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code environment.json}: the strata this experiment ran under (§2, §5.5).
 *
 * <p>Results are never pooled or ranked across strata, and refusing that later requires the
 * strata to have been recorded now. Written beside {@code experiment.json} so a result folder is
 * interpretable on its own a year later, on a different machine, with nothing else surviving.
 */
final class EnvironmentFile {

    private EnvironmentFile() {}

    static void write(Path outputDirectory, ModrinthInstance instance, String scenarioListRevision) {
        Map<String, Object> environment = new LinkedHashMap<>();
        // The five strata (§2): platform+arch, loader tuple, Inlay lineage, Spark engine,
        // scenario-list revision. Plus the incidental facts a reader always ends up wanting.
        environment.put("platform", System.getProperty("os.name"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("loaderTuple", instance.versionId());
        environment.put("inlayLineage", inlayLineage(instance));
        environment.put("sparkEngine", instrumentationJar(instance, "spark"));
        environment.put(
                "scenarioListRevision",
                scenarioListRevision == null ? "unrecorded" : scenarioListRevision);
        environment.put("cpus", Runtime.getRuntime().availableProcessors());
        environment.put("java", System.getProperty("java.version"));
        environment.put("machineFingerprint", MachineProfiles.fingerprint());
        environment.put("profile", instance.profileName());

        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(
                    outputDirectory.resolve("environment.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(environment),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The strata matter, but not more than the results they describe.
            System.err.println("could not write environment.json: " + e);
        }
    }

    private static String inlayLineage(ModrinthInstance instance) {
        try {
            Path index = instance.gameDirectory().resolve("inlay.index.json");
            if (!Files.isRegularFile(index)) {
                return "no index";
            }
            var root =
                    com.google.gson.JsonParser.parseString(
                                    Files.readString(index, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var name = root.get("name");
            var version = root.get("versionId");
            return (name == null ? "?" : name.getAsString())
                    + " "
                    + (version == null ? "?" : version.getAsString());
        } catch (IOException | RuntimeException e) {
            return "unreadable index";
        }
    }

    private static String instrumentationJar(ModrinthInstance instance, String prefix) {
        try (var entries = Files.list(instance.gameDirectory().resolve("mods"))) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                    .findFirst()
                    .orElse("not installed");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
