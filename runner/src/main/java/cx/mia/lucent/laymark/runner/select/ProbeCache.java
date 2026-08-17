package cx.mia.lucent.laymark.runner.select;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Probe results remembered by content hash, so the planner does not reopen every jar on every
 * profile change.
 *
 * <p>Keyed by <strong>SHA-512 of the jar's bytes</strong>, never by name or mtime: a declaration
 * is a pure function of the bytes, so the hash is the whole truth about whether a cached answer
 * still applies. A renamed jar hits, a silently-replaced one misses — both correct, for free.
 *
 * <p>Lives in {@code ~/.laymark/} beside the machine profile: the same jar in two instances is the
 * same bytes. Best-effort like the profile — an unreadable cache costs re-probing, never
 * correctness.
 */
public final class ProbeCache {

    private static final Gson GSON = new Gson();

    private final Path file;
    private final Map<String, JarProbe.Declared> byHash;
    private boolean dirty;

    private ProbeCache(Path file, Map<String, JarProbe.Declared> byHash) {
        this.file = file;
        this.byHash = byHash;
    }

    public static ProbeCache open() {
        Path file = Path.of(System.getProperty("user.home"), ".laymark", "probe-cache.json");
        if (!Files.isRegularFile(file)) {
            return new ProbeCache(file, new LinkedHashMap<>());
        }
        try {
            Map<String, JarProbe.Declared> stored =
                    GSON.fromJson(
                            Files.readString(file, StandardCharsets.UTF_8),
                            new TypeToken<Map<String, JarProbe.Declared>>() {}.getType());
            return new ProbeCache(file, stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored));
        } catch (IOException | RuntimeException e) {
            System.err.println("could not read the probe cache, starting empty: " + e);
            return new ProbeCache(file, new LinkedHashMap<>());
        }
    }

    /** The jar's declarations, from the cache when the bytes match, probed and remembered when not. */
    JarProbe.Declared declared(Path jar) {
        String hash = sha512(jar);
        if (byHash.containsKey(hash)) {
            return byHash.get(hash);
        }
        JarProbe.Declared declared = JarProbe.declared(jar);
        // Absence is an answer too: a resource pack stays a cache entry, or it would be reopened
        // on every reload forever.
        byHash.put(hash, declared);
        dirty = true;
        return declared;
    }

    public void persist() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(byHash), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            System.err.println("could not persist the probe cache: " + e);
        }
    }

    private static String sha512(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file);
                    DigestInputStream digesting = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[64 * 1024];
                while (digesting.read(buffer) != -1) {
                    // Reading is what feeds the digest.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("could not hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is required of every JVM", e);
        }
    }
}
