package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.Laymark;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs and locates the human-authored scenario config shared by runner and harness. */
public final class ScenarioConfigFile {

    private static final String REFERENCE_RESOURCE = "/laymark-reference.jsonc";

    private ScenarioConfigFile() {}

    /** The config path for one game instance. */
    public static Path path(Path gameDirectory) {
        return gameDirectory.resolve(Laymark.CONFIG_PATH);
    }

    /**
     * Writes the complete commented reference when this instance has no config yet.
     *
     * <p>An existing path is never replaced. {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}
     * is deliberately absent: after first creation this document belongs to the operator, including
     * when two startup paths happen to race to ensure it.
     *
     * @return the existing or newly created config path
     * @throws IOException if the reference cannot be created as a regular file
     */
    public static Path ensureExists(Path gameDirectory) throws IOException {
        Path target = path(gameDirectory);
        if (Files.isRegularFile(target)) {
            return target;
        }
        if (Files.exists(target)) {
            throw new IOException("scenario config path is not a regular file: " + target);
        }

        Files.createDirectories(target.getParent());
        try (var reference = ScenarioConfigFile.class.getResourceAsStream(REFERENCE_RESOURCE)) {
            if (reference == null) {
                throw new FileNotFoundException(
                        "packaged scenario reference is missing: " + REFERENCE_RESOURCE);
            }
            try {
                Files.copy(reference, target);
            } catch (FileAlreadyExistsException raced) {
                if (!Files.isRegularFile(target)) {
                    throw raced;
                }
            }
        }
        return target;
    }
}
