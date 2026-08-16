package cx.mia.lucent.laymark.runner.launch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Resolves an instance's layout from a Modrinth App data directory.
 *
 * <p>Read-only. Laymark never writes to the launcher's database — it reads the layout, assembles
 * its own invocation and owns the resulting process.
 *
 * <p>0.x supports this one launcher, deliberately. The seam is here so another can be added, but
 * building for a second launcher before the first one works would be guessing at what varies.
 */
public record ModrinthInstance(Path root, String profileName, String versionId) {

    public static Path defaultRoot() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "ModrinthApp");
        }
        String home = System.getProperty("user.home");
        // macOS and Linux locations differ; platform is a stratum, so this is not incidental.
        Path macos = Path.of(home, "Library", "Application Support", "ModrinthApp");
        return Files.isDirectory(macos) ? macos : Path.of(home, ".local", "share", "ModrinthApp");
    }

    public Path gameDirectory() {
        return root.resolve("profiles").resolve(profileName);
    }

    public Path versionJson() {
        return root.resolve("meta/versions").resolve(versionId).resolve(versionId + ".json");
    }

    public InstanceLayout layout() {
        Path versionDir = root.resolve("meta/versions").resolve(versionId);
        return new InstanceLayout(
                gameDirectory().toString(),
                root.resolve("meta/libraries").toString(),
                root.resolve("meta/natives").resolve(versionId).toString(),
                root.resolve("meta/assets").toString(),
                versionDir.resolve(versionId + ".jar").toString());
    }

    public VersionDescriptor descriptor() {
        Path json = versionJson();
        if (!Files.isRegularFile(json)) {
            throw new LaunchException("no version descriptor at " + json);
        }
        try {
            return VersionDescriptor.parse(Files.readString(json, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + json, e);
        }
    }

    /**
     * The JVM the launcher manages for this instance.
     *
     * <p>Explicitly not the runner's own JVM. The instance expects a particular Java version, and
     * a benchmark launched on a different one is measuring a different runtime — the JIT and GC
     * are exactly what a mod stack is being compared on.
     */
    public Path javaExecutable() {
        Path javaVersions = root.resolve("meta/java_versions");
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        if (Files.isDirectory(javaVersions)) {
            try (Stream<Path> candidates = Files.list(javaVersions)) {
                return candidates
                        .map(dir -> dir.resolve("bin").resolve(executable))
                        .filter(Files::isRegularFile)
                        .max(Comparator.comparing(Path::toString))
                        .orElseThrow(
                                () ->
                                        new LaunchException(
                                                "no managed JVM under " + javaVersions));
            } catch (IOException e) {
                throw new UncheckedIOException("could not list " + javaVersions, e);
            }
        }
        throw new LaunchException("no managed JVMs at " + javaVersions);
    }

    public void requireUsable() {
        if (!Files.isDirectory(gameDirectory())) {
            throw new LaunchException("no such profile: " + gameDirectory());
        }
        if (!Files.isRegularFile(versionJson())) {
            throw new LaunchException("no version descriptor at " + versionJson());
        }
    }
}
