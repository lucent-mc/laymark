package cx.mia.lucent.laymark.runner;

import com.google.gson.Gson;
import cx.mia.lucent.laymark.core.stats.MachineProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The machine profile on disk: {@code ~/.laymark/machine-<fingerprint>.json}.
 *
 * <p>Under the home directory rather than any instance, because the wobble being remembered
 * belongs to the machine — the same hardware benchmarking two packs has one noise history, and a
 * profile buried in one instance would leave the other starting cold.
 *
 * <p>The fingerprint keys the file name, so a hardware change simply fails to find a file rather
 * than needing change-detection logic. Best-effort throughout: a profile that cannot be read or
 * written costs cautiousness, never correctness, so IO failures degrade to the empty profile
 * instead of failing a run.
 */
public final class MachineProfiles {

    private MachineProfiles() {}

    private static final Gson GSON = new Gson();

    /**
     * Coarse on purpose. CPU model and core count catch a machine swap; a driver update slips
     * through, which is exactly what widen-only semantics make survivable.
     */
    public static String fingerprint() {
        return (System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", System.getProperty("os.arch"))
                        + "-"
                        + Runtime.getRuntime().availableProcessors()
                        + "-"
                        + System.getProperty("os.name"))
                .replaceAll("[^A-Za-z0-9.-]", "_");
    }

    public static MachineProfile load() {
        String fingerprint = fingerprint();
        Path path = pathFor(fingerprint);
        if (!Files.isRegularFile(path)) {
            return MachineProfile.empty(fingerprint);
        }
        try {
            MachineProfile stored =
                    GSON.fromJson(
                            Files.readString(path, StandardCharsets.UTF_8), MachineProfile.class);
            return stored != null && stored.matches(fingerprint)
                    ? stored
                    : MachineProfile.empty(fingerprint);
        } catch (IOException | RuntimeException e) {
            System.err.println("could not read the machine profile, starting empty: " + e);
            return MachineProfile.empty(fingerprint);
        }
    }

    public static void store(MachineProfile profile) {
        Path path = pathFor(profile.fingerprint());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(profile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("could not persist the machine profile: " + e);
        }
    }

    private static Path pathFor(String fingerprint) {
        return Path.of(System.getProperty("user.home"), ".laymark", "machine-" + fingerprint + ".json");
    }
}
