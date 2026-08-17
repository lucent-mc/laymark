package cx.mia.lucent.laymark.runner.select;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cx.mia.lucent.laymark.core.select.DependencyGraph;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads dependencies out of the jars that will actually load.
 *
 * <p>The most trustworthy source in the graph, because it describes the file rather than the
 * project. A registry knows what a mod's published version requires; only the jar knows what this
 * build of it requires, and repackaged or locally-patched jars are exactly the ones a benchmark
 * ends up holding.
 *
 * <p>Reads both loaders' manifests, which costs almost nothing and means the graph is built the
 * same way whichever loader the instance uses.
 */
public final class JarProbe {

    private JarProbe() {}

    private static final String NEOFORGE_MANIFEST = "META-INF/neoforge.mods.toml";
    private static final String FABRIC_MANIFEST = "fabric.mod.json";

    /**
     * A jar that declares nothing readable contributes no edges.
     *
     * <p>Silently rather than fatally: a resource pack, a datapack-only jar or a library with no
     * mod manifest is a perfectly ordinary thing to find in a mods folder, and refusing to run
     * because one exists would make the benchmark unusable on real packs.
     */
    public static DependencyGraph probe(List<Path> jars) {
        return inspect(jars).graph();
    }

    /**
     * The graph, plus which file each mod id came out of.
     *
     * <p>Edges are between mod ids because that is how a jar declares what it needs, but everything
     * an operator touches is a file name. Something has to hold both, and it is the pass that
     * already opened every jar.
     *
     * @param modIdByFile keyed by file name; a jar declaring no readable manifest is absent
     */
    public record Probed(DependencyGraph graph, Map<String, String> modIdByFile) {}

    public static Probed inspect(List<Path> jars) {
        Map<String, Set<String>> requires = new LinkedHashMap<>();
        Map<String, String> modIdByFile = new LinkedHashMap<>();

        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Declaration declaration = read(zip, jar);
                if (declaration != null) {
                    requires
                            .computeIfAbsent(declaration.modId(), unused -> new TreeSet<>())
                            .addAll(declaration.requires());
                    modIdByFile.put(jar.getFileName().toString(), declaration.modId());
                }
            } catch (IOException e) {
                throw new LaunchException("could not read " + jar, e);
            }
        }
        return new Probed(
                DependencyGraph.from(requires, DependencyGraph.Provenance.JAR_METADATA),
                modIdByFile);
    }

    private record Declaration(String modId, Set<String> requires) {}

    private static Declaration read(ZipFile zip, Path jar) throws IOException {
        ZipEntry fabric = zip.getEntry(FABRIC_MANIFEST);
        if (fabric != null) {
            return fabric(text(zip, fabric), jar);
        }
        ZipEntry neoforge = zip.getEntry(NEOFORGE_MANIFEST);
        if (neoforge != null) {
            return neoforge(text(zip, neoforge));
        }
        return null;
    }

    private static String text(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Declaration fabric(String json, Path jar) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new LaunchException(jar + " has an unreadable " + FABRIC_MANIFEST, e);
        }
        String modId = string(root, "id");
        if (modId == null) {
            return null;
        }
        Set<String> requires = new LinkedHashSet<>();
        if (root.has("depends") && root.get("depends").isJsonObject()) {
            requires.addAll(root.getAsJsonObject("depends").keySet());
        }
        // Fabric's built-ins are always present and are not mods anyone can toggle, so an edge to
        // one would only ever make a bundle look larger than it is.
        requires.removeAll(Set.of("fabricloader", "minecraft", "java"));
        return new Declaration(modId, requires);
    }

    /**
     * Reads the two fields needed out of {@code neoforge.mods.toml}.
     *
     * <p>Deliberately not a TOML parser. Only {@code modId} under {@code [[mods]]} and the
     * {@code modId}/{@code type} pairs under {@code [[dependencies.*]]} are wanted, and both are
     * written one per line by every generator in use. A full parser would be a dependency inside
     * the runner to read four fields — and the fallback when this is wrong is an operator override,
     * which the graph already supports.
     */
    private static Declaration neoforge(String toml) {
        String modId = null;
        Set<String> requires = new LinkedHashSet<>();

        boolean inDependencies = false;
        String pendingId = null;
        Boolean pendingRequired = null;

        for (String raw : toml.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("[[dependencies.")) {
                inDependencies = true;
                flush(requires, pendingId, pendingRequired);
                pendingId = null;
                pendingRequired = null;
                continue;
            }
            if (line.startsWith("[[mods]]") || line.startsWith("[")) {
                if (inDependencies) {
                    flush(requires, pendingId, pendingRequired);
                    pendingId = null;
                    pendingRequired = null;
                }
                inDependencies = line.startsWith("[[dependencies.");
                continue;
            }

            String value = value(line, "modId");
            if (value != null) {
                if (inDependencies) {
                    pendingId = value;
                } else if (modId == null) {
                    modId = value;
                }
                continue;
            }
            String type = value(line, "type");
            if (type != null && inDependencies) {
                pendingRequired = "required".equalsIgnoreCase(type);
            }
        }
        flush(requires, pendingId, pendingRequired);

        if (modId == null) {
            return null;
        }
        requires.removeAll(Set.of("neoforge", "minecraft", "java"));
        return new Declaration(modId, requires);
    }

    /** Only required dependencies form edges; an optional one does not have to be present. */
    private static void flush(Set<String> requires, String modId, Boolean required) {
        if (modId != null && Boolean.TRUE.equals(required)) {
            requires.add(modId);
        }
    }

    private static final Pattern ASSIGNMENT =
            Pattern.compile("^(\\w+)\\s*=\\s*[\"']([^\"']*)[\"']");

    private static String value(String line, String key) {
        Matcher matcher = ASSIGNMENT.matcher(line);
        return matcher.find() && matcher.group(1).equals(key) ? matcher.group(2) : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
