package cx.mia.lucent.laymark.runner.select;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cx.mia.lucent.laymark.core.select.Branching;
import cx.mia.lucent.laymark.core.select.DependencyGraph;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The operator's word on dependencies, for the cases the jars get wrong.
 *
 * <p>Hand-authored at {@code config/laymark-dependencies.json}, beside the scenario config:
 *
 * <pre>
 *   {
 *     "requires":     { "some_mod": ["library_it_forgot_to_declare"] },
 *     "incompatible": [ ["mod_a", "mod_b"] ]
 *   }
 * </pre>
 *
 * <p>Mod ids, not file names — the same vocabulary the manifests use. Overrides are the
 * <em>third</em> source and carry {@link DependencyGraph.Provenance#OVERRIDE}, which loses to jar
 * metadata on the same edge: an operator's memory of what a mod needs goes stale faster than the
 * mod's own manifest does. What overrides are for is the edge no manifest declares at all.
 *
 * <p>Absent file, no overrides. A present-but-unreadable file fails, like every other descriptor:
 * a config someone wrote and Laymark ignored is worse than an error.
 */
public final class DependencyOverrides {

    /** File name under {@code config/}, beside {@code laymark.json}. */
    public static final String FILE_NAME = "laymark-dependencies.json";

    public record Overrides(DependencyGraph graph, List<Branching.Conflict> conflicts) {

        public static Overrides none() {
            return new Overrides(DependencyGraph.empty(), List.of());
        }
    }

    private DependencyOverrides() {}

    public static Overrides load(Path gameDirectory) {
        Path file = gameDirectory.resolve("config").resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Overrides.none();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new LaunchException(file + " is not readable JSON", e);
        }

        Map<String, Set<String>> requires = new LinkedHashMap<>();
        JsonElement requiresElement = root.get("requires");
        if (requiresElement != null && requiresElement.isJsonObject()) {
            for (String modId : requiresElement.getAsJsonObject().keySet()) {
                JsonElement needs = requiresElement.getAsJsonObject().get(modId);
                if (!needs.isJsonArray()) {
                    throw new LaunchException(
                            file + ": requires." + modId + " must be an array of mod ids");
                }
                Set<String> ids = new TreeSet<>();
                needs.getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
                requires.put(modId, ids);
            }
        }

        List<Branching.Conflict> conflicts = new ArrayList<>();
        JsonElement incompatible = root.get("incompatible");
        if (incompatible != null && incompatible.isJsonArray()) {
            for (JsonElement pair : incompatible.getAsJsonArray()) {
                if (!pair.isJsonArray() || pair.getAsJsonArray().size() != 2) {
                    throw new LaunchException(
                            file + ": every incompatible entry must be a pair of mod ids");
                }
                conflicts.add(
                        new Branching.Conflict(
                                pair.getAsJsonArray().get(0).getAsString(),
                                pair.getAsJsonArray().get(1).getAsString()));
            }
        }

        return new Overrides(
                DependencyGraph.from(requires, DependencyGraph.Provenance.OVERRIDE),
                List.copyOf(conflicts));
    }
}
