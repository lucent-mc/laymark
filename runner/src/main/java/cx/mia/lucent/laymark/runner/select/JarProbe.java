package cx.mia.lucent.laymark.runner.select;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cx.mia.lucent.laymark.core.select.Branching;
import cx.mia.lucent.laymark.core.select.DependencyGraph;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.zip.ZipInputStream;

/**
 * Reads dependencies, incompatibilities and identities out of the jars that will actually load.
 *
 * <p>The most trustworthy source in the graph, because it describes the file rather than the
 * project. A registry knows what a mod's published version requires; only the jar knows what this
 * build of it requires, and repackaged or locally-patched jars are exactly the ones a benchmark
 * ends up holding.
 *
 * <p><strong>Jar-in-jar payloads are traversed</strong> ({@code META-INF/jarjar/metadata.json}).
 * They matter twice over: an embedded mod satisfies its host's requirement from inside the file,
 * so the edge must not demand an external jar — and a candidate's requirement satisfied by some
 * <em>other</em> mod's payload is satisfied only while that mod is enabled.
 *
 * <p><strong>Fail-closed on an unrecognised descriptor.</strong> A jar with no manifest at all is
 * ordinary — resource packs and libraries live in mods folders — but a manifest or jar-in-jar
 * descriptor that is present and unreadable means the one authoritative source is telling us
 * something we cannot hear, and guessing "no dependencies" builds arms that do not load.
 */
public final class JarProbe {

    private JarProbe() {}

    private static final String NEOFORGE_MANIFEST = "META-INF/neoforge.mods.toml";
    private static final String FABRIC_MANIFEST = "fabric.mod.json";
    private static final String JARJAR_DESCRIPTOR = "META-INF/jarjar/metadata.json";

    /**
     * Everything probing the jars learned.
     *
     * @param modIdByFile the outer mod id per file; a jar with no readable manifest is absent
     * @param displayNameByFile the manifest's display name; absent when the manifest names none
     * @param providerByModId which file provides each mod id, embedded payloads included — the
     *     map dependency resolution goes through, because a requirement is satisfied by whoever
     *     carries the id, not only by a jar named after it
     * @param conflicts declared incompatibilities between installed files, the input Branching
     *     splits a selection on
     */
    public record Probed(
            DependencyGraph graph,
            Map<String, String> modIdByFile,
            Map<String, String> displayNameByFile,
            Map<String, String> providerByModId,
            List<Branching.Conflict> conflicts) {}

    /** One jar's declarations, cacheable because they are a pure function of the jar's bytes. */
    public record Declared(
            String modId,
            String displayName,
            Set<String> requires,
            Set<String> incompatibleWith,
            Set<String> provides) {}

    public static DependencyGraph probe(List<Path> jars) {
        return inspect(jars).graph();
    }

    public static Probed inspect(List<Path> jars) {
        return inspect(jars, null);
    }

    /** @param cache reuses prior probes by content hash; null probes everything fresh */
    public static Probed inspect(List<Path> jars, ProbeCache cache) {
        Map<String, Declared> byFile = new LinkedHashMap<>();
        for (Path jar : jars) {
            Declared declared = cache == null ? declared(jar) : cache.declared(jar);
            if (declared != null) {
                byFile.put(jar.getFileName().toString(), declared);
            }
        }

        Map<String, String> providerByModId = new LinkedHashMap<>();
        byFile.forEach(
                (file, declared) -> {
                    providerByModId.putIfAbsent(declared.modId(), file);
                    declared.provides().forEach(id -> providerByModId.putIfAbsent(id, file));
                });

        Map<String, Set<String>> requires = new LinkedHashMap<>();
        Map<String, String> modIdByFile = new LinkedHashMap<>();
        Map<String, String> displayNameByFile = new LinkedHashMap<>();
        List<Branching.Conflict> conflicts = new ArrayList<>();

        byFile.forEach(
                (file, declared) -> {
                    modIdByFile.put(file, declared.modId());
                    if (declared.displayName() != null) {
                        displayNameByFile.put(file, declared.displayName());
                    }
                    Set<String> external = new TreeSet<>(declared.requires());
                    // Satisfied from inside the file: not an edge, or every jar-in-jar host would
                    // appear to need a jar nobody has.
                    external.removeAll(declared.provides());
                    external.remove(declared.modId());
                    requires.computeIfAbsent(declared.modId(), unused -> new TreeSet<>())
                            .addAll(external);
                    for (String enemy : declared.incompatibleWith()) {
                        String enemyFile = providerByModId.get(enemy);
                        if (enemyFile != null && !enemyFile.equals(file)) {
                            conflicts.add(new Branching.Conflict(file, enemyFile));
                        }
                    }
                });

        return new Probed(
                DependencyGraph.from(requires, DependencyGraph.Provenance.JAR_METADATA),
                modIdByFile,
                displayNameByFile,
                providerByModId,
                List.copyOf(conflicts));
    }

    /**
     * Whether this jar loads through FML's plugin path rather than as an ordinary mod.
     *
     * <p>A jar that ships a language loader or transformation service is picked up before mod
     * construction ("Loading FML Plugins" in the game log) and does its work there; it never
     * becomes a runtime mod-list entry, however honest its {@code mods.toml} looks. The runtime
     * inventory check has to know, or it reads FML working as designed as a materialisation
     * failure. An unreadable jar answers false — the caller keeps whatever suspicion it had.
     */
    public static boolean loaderPlugin(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream()
                    .anyMatch(
                            entry ->
                                    entry.getName().startsWith("META-INF/services/net.neoforged.neoforgespi.")
                                            || entry.getName()
                                                    .equals(
                                                            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Everything one jar declares, payloads included; null for a jar with no manifest at all. */
    static Declared declared(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Declaration outer = read(zip, jar);

            Set<String> provides = new TreeSet<>();
            Set<String> payloadRequires = new TreeSet<>();
            for (String payloadPath : payloadPaths(zip, jar)) {
                Declaration nested = readNested(zip, jar, payloadPath);
                if (nested != null) {
                    provides.add(nested.modId());
                    payloadRequires.addAll(nested.requires());
                }
            }

            if (outer == null && provides.isEmpty()) {
                return null; // no manifest anywhere: a library or resource pack, not a mod
            }
            if (outer == null) {
                // A pure carrier: jar-in-jar payloads with no outer mod. Rare but legal; the file
                // is identified by its first payload so it can participate in the graph.
                String id = provides.iterator().next();
                return new Declared(id, null, payloadRequires, Set.of(), provides);
            }
            Set<String> requires = new TreeSet<>(outer.requires());
            requires.addAll(payloadRequires);
            return new Declared(
                    outer.modId(),
                    outer.displayName(),
                    requires,
                    outer.incompatibleWith(),
                    provides);
        } catch (IOException e) {
            throw new LaunchException("could not read " + jar, e);
        }
    }

    /** The payload paths the jar-in-jar descriptor names; fail-closed when it is unreadable. */
    private static List<String> payloadPaths(ZipFile zip, Path jar) throws IOException {
        ZipEntry descriptor = zip.getEntry(JARJAR_DESCRIPTOR);
        if (descriptor == null) {
            return List.of();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text(zip, descriptor)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new LaunchException(
                    jar + " has an unreadable " + JARJAR_DESCRIPTOR
                            + "; refusing to guess what it embeds",
                    e);
        }
        JsonElement jars = root.get("jars");
        if (jars == null || !jars.isJsonArray()) {
            throw new LaunchException(
                    jar + "'s " + JARJAR_DESCRIPTOR + " declares no jars array;"
                            + " refusing to guess what it embeds");
        }
        List<String> paths = new ArrayList<>();
        for (JsonElement entry : (JsonArray) jars) {
            JsonElement path = entry.getAsJsonObject().get("path");
            if (path == null || !path.isJsonPrimitive()) {
                throw new LaunchException(
                        jar + "'s " + JARJAR_DESCRIPTOR + " has an entry without a path");
            }
            paths.add(path.getAsString());
        }
        return paths;
    }

    /** Reads a nested payload's manifest without extracting the payload to disk. */
    private static Declaration readNested(ZipFile zip, Path jar, String payloadPath)
            throws IOException {
        ZipEntry payload = zip.getEntry(payloadPath);
        if (payload == null) {
            throw new LaunchException(
                    jar + " declares payload " + payloadPath + " that is not in the jar");
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(payload)) {
            bytes = in.readAllBytes();
        }
        String fabric = null;
        String neoforge = null;
        try (ZipInputStream nested = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = nested.getNextEntry()) != null) {
                if (entry.getName().equals(FABRIC_MANIFEST)) {
                    fabric = new String(nested.readAllBytes(), StandardCharsets.UTF_8);
                } else if (entry.getName().equals(NEOFORGE_MANIFEST)) {
                    neoforge = new String(nested.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        if (fabric != null) {
            return fabric(fabric, jar);
        }
        if (neoforge != null) {
            return neoforge(neoforge, jar);
        }
        return null; // an embedded plain library
    }

    private record Declaration(
            String modId, Set<String> requires, Set<String> incompatibleWith, String displayName) {}

    private static Declaration read(ZipFile zip, Path jar) throws IOException {
        ZipEntry fabric = zip.getEntry(FABRIC_MANIFEST);
        if (fabric != null) {
            return fabric(text(zip, fabric), jar);
        }
        ZipEntry neoforge = zip.getEntry(NEOFORGE_MANIFEST);
        if (neoforge != null) {
            return neoforge(text(zip, neoforge), jar);
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
            // Present but incomplete is unrecognised, not absent: the manifest is the one
            // authoritative source, and it is malformed.
            throw new LaunchException(jar + "'s " + FABRIC_MANIFEST + " declares no id");
        }
        Set<String> requires = new LinkedHashSet<>();
        if (root.has("depends") && root.get("depends").isJsonObject()) {
            requires.addAll(root.getAsJsonObject("depends").keySet());
        }
        Set<String> breaks = new LinkedHashSet<>();
        if (root.has("breaks") && root.get("breaks").isJsonObject()) {
            breaks.addAll(root.getAsJsonObject("breaks").keySet());
        }
        // Fabric's built-ins are always present and are not mods anyone can toggle, so an edge to
        // one would only ever make a bundle look larger than it is.
        requires.removeAll(Set.of("fabricloader", "minecraft", "java"));
        return new Declaration(modId, requires, breaks, string(root, "name"));
    }

    /**
     * Reads the needed fields out of {@code neoforge.mods.toml}.
     *
     * <p>Deliberately not a TOML parser. Only {@code modId}/{@code displayName} under
     * {@code [[mods]]} and the {@code modId}/{@code type} pairs under {@code [[dependencies.*]]}
     * are wanted, and all are written one per line by every generator in use. The fallback when
     * this is wrong is an operator override, which the graph supports.
     */
    private static Declaration neoforge(String toml, Path jar) {
        String modId = null;
        String displayName = null;
        Set<String> requires = new LinkedHashSet<>();
        Set<String> incompatible = new LinkedHashSet<>();

        boolean inDependencies = false;
        boolean sawModsTable = false;
        String pendingId = null;
        String pendingType = null;

        for (String raw : toml.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("[[dependencies.")) {
                inDependencies = true;
                flush(requires, incompatible, pendingId, pendingType);
                pendingId = null;
                pendingType = null;
                continue;
            }
            if (line.startsWith("[")) {
                if (inDependencies) {
                    flush(requires, incompatible, pendingId, pendingType);
                    pendingId = null;
                    pendingType = null;
                }
                sawModsTable |= line.startsWith("[[mods]]");
                inDependencies = false;
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
            String name = value(line, "displayName");
            if (name != null && !inDependencies && displayName == null) {
                displayName = name;
                continue;
            }
            String type = value(line, "type");
            if (type != null && inDependencies) {
                pendingType = type;
            }
        }
        flush(requires, incompatible, pendingId, pendingType);

        if (modId == null) {
            if (sawModsTable) {
                throw new LaunchException(
                        jar + "'s " + NEOFORGE_MANIFEST + " has a [[mods]] table with no modId");
            }
            return null;
        }
        requires.removeAll(Set.of("neoforge", "minecraft", "java"));
        return new Declaration(modId, requires, incompatible, displayName);
    }

    /** Required dependencies form edges; incompatible ones form conflicts; the rest are optional. */
    private static void flush(
            Set<String> requires, Set<String> incompatible, String modId, String type) {
        if (modId == null) {
            return;
        }
        if ("required".equalsIgnoreCase(type)) {
            requires.add(modId);
        } else if ("incompatible".equalsIgnoreCase(type)) {
            incompatible.add(modId);
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
