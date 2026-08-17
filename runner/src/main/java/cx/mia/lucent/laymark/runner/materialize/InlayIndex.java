package cx.mia.lucent.laymark.runner.materialize;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads which mods an Inlay layer adds on top of whatever it was built from.
 *
 * <p>That is the one question Laymark asks of it, and the answer is what separates "the pack minus
 * the candidates" from "the pack this pack was derived from". Benchmarking a mod against a stack
 * that already contains thirty other tuning mods answers a different question than benchmarking it
 * against the pack someone actually started from.
 *
 * <p>Read-only, and never written. {@code inlay.index.json} belongs to Inlay; Laymark reads the
 * layer's file list and touches nothing.
 *
 * <p>A missing index is not an error. It means nothing is known to have been added, which the
 * caller reads as "everything here is this layer's" — the honest reading, since an instance with no
 * index has no recorded ancestor.
 */
public final class InlayIndex {

    private InlayIndex() {}

    public static final String FILE_NAME = "inlay.index.json";

    private static final String MODS_PREFIX = "mods/";

    /**
     * @return the mod file names this layer adds, or null when the instance has no index
     */
    public static Set<String> addedMods(Path gameDirectory) {
        Path index = gameDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(index)) {
            return null;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // An unreadable index is treated as an absent one. It is another tool's file, and
            // refusing to plan a benchmark because of its contents would be Laymark's opinion of
            // someone else's format.
            return null;
        }

        Set<String> mods = new TreeSet<>();
        JsonElement files = root.get("files");
        if (files == null || !files.isJsonArray()) {
            return mods;
        }
        for (JsonElement entry : files.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonElement path = entry.getAsJsonObject().get("path");
            if (path == null || !path.isJsonPrimitive()) {
                continue;
            }
            String value = path.getAsString().replace('\\', '/');
            if (value.startsWith(MODS_PREFIX) && value.endsWith(".jar")) {
                mods.add(value.substring(MODS_PREFIX.length()));
            }
        }
        return mods;
    }
}
