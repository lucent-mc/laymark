package cx.mia.lucent.laymark.runner.launch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Works out which installed version a profile actually runs, from the profile itself.
 *
 * <p>The launcher keeps that mapping in its own database, which Laymark does not read — so this
 * reads the evidence the game leaves behind instead. A launch log names the loader jar it booted
 * by its Maven path, {@code .../neoforged/neoforge/26.1.2.95/...}, and exactly one installed
 * version id ends in that loader version.
 *
 * <p>Loader-agnostic by construction: nothing here knows what NeoForge is. It looks for the loader
 * half of each installed id as a path segment in the log, so a Fabric profile resolves the same way
 * through {@code .../fabric-loader/0.19.3/...}.
 *
 * <p>Ambiguity is not resolved by guessing. If no log names an installed version, or more than one
 * matches, this returns nothing and the caller asks.
 */
public final class InstalledVersion {

    private InstalledVersion() {}

    /** Enough of a log to carry the classpath, which is printed during startup. */
    private static final int LOG_PREFIX_BYTES = 512 * 1024;

    /**
     * @param installed every version id under {@code meta/versions}
     * @return the one this profile last ran, or null if the logs do not say so unambiguously
     */
    public static String detect(Path gameDirectory, List<String> installed) {
        String log = readLog(gameDirectory);
        if (log == null) {
            return null;
        }
        List<String> matches = installed.stream().filter(id -> mentions(log, id)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * Whether the log booted this version's loader.
     *
     * <p>Matched as a whole path segment. A bare substring test would let {@code 26.1.2.9} match a
     * log that only ever mentions {@code 26.1.2.95}.
     */
    private static boolean mentions(String log, String versionId) {
        int separator = versionId.indexOf('-');
        if (separator < 0) {
            return false;
        }
        String loader = versionId.substring(separator + 1);
        return log.contains("/" + loader + "/") || log.contains("\\" + loader + "\\");
    }

    /** The most recent log, whatever it is called; rotated logs are compressed and skipped. */
    private static String readLog(Path gameDirectory) {
        Path logs = gameDirectory.resolve("logs");
        if (!Files.isDirectory(logs)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(logs)) {
            Path newest =
                    entries.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".log"))
                            .max(Comparator.comparing(InstalledVersion::modified))
                            .orElse(null);
            if (newest == null) {
                return null;
            }
            byte[] bytes = Files.newInputStream(newest).readNBytes(LOG_PREFIX_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A profile whose logs cannot be read is a profile whose version gets asked for.
            return null;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
