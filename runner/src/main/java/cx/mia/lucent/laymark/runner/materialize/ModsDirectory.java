package cx.mia.lucent.laymark.runner.materialize;

import cx.mia.lucent.laymark.core.materialize.FileOperation;
import cx.mia.lucent.laymark.core.materialize.InstanceState;
import cx.mia.lucent.laymark.core.materialize.ModFile;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The instance's mod directories, read and rearranged.
 *
 * <p>All of it is renames within one filesystem, which is what keeps it loader-agnostic: NeoForge
 * and Fabric both scan for names ending {@code .jar}, so a {@code .jar.disabled} file is invisible
 * to each without either being told anything. Nothing here knows which loader is installed.
 *
 * <p>The decisions live in {@code core}; this only carries them out. That split is why the awkward
 * cases — a half-materialised instance, a mod that vanished between rounds — are tested without a
 * disk, and why what remains here is small enough to read.
 */
public final class ModsDirectory {

    private final Path mods;
    private final Path withheld;

    public ModsDirectory(Path instanceRoot) {
        this.mods = instanceRoot.resolve("mods");
        this.withheld =
                instanceRoot
                        .resolve(cx.mia.lucent.laymark.core.Laymark.WORK_DIR)
                        .resolve("withheld");
    }

    /**
     * Reads what is currently on disk, hashing every jar.
     *
     * <p>Hashing costs a pass over every mod, which is seconds on a large pack, and it is the only
     * way to notice a jar replaced between rounds. A run that compares two stacks has to know the
     * bytes did not move under it.
     */
    public InstanceState read() {
        List<ModFile> enabled = new ArrayList<>();
        List<ModFile> disabled = new ArrayList<>();

        for (Path file : jars(mods)) {
            String onDisk = file.getFileName().toString();
            ModFile mod = new ModFile(ModFile.stripDisabled(onDisk), sha256(file));
            if (ModFile.isDisabled(onDisk)) {
                disabled.add(mod);
            } else {
                enabled.add(mod);
            }
        }

        List<ModFile> aside = new ArrayList<>();
        for (Path file : jars(withheld)) {
            String onDisk = file.getFileName().toString();
            aside.add(new ModFile(ModFile.stripDisabled(onDisk), sha256(file)));
        }
        return new InstanceState(enabled, disabled, aside);
    }

    /** Carries out a plan, in order. Order is load-bearing: a file cannot be enabled elsewhere. */
    public void apply(List<FileOperation> operations) {
        if (operations.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(withheld);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + withheld, e);
        }

        for (FileOperation operation : operations) {
            switch (operation) {
                case FileOperation.Enable enable ->
                        move(
                                mods.resolve(enable.fileName() + ModFile.DISABLED_SUFFIX),
                                mods.resolve(enable.fileName()));
                case FileOperation.Disable disable ->
                        move(
                                mods.resolve(disable.fileName()),
                                mods.resolve(disable.fileName() + ModFile.DISABLED_SUFFIX));
                case FileOperation.Withhold hold ->
                        move(mods.resolve(hold.fileName()), withheld.resolve(hold.fileName()));
                case FileOperation.Restore restore ->
                        move(withheld.resolve(restore.fileName()), mods.resolve(restore.fileName()));
            }
        }
    }

    /**
     * @throws LaunchException naming both paths. A rename fails for reasons an operator can act on
     *     — a launcher holding the file open, antivirus, permissions — and none of them are
     *     guessable from "could not rename".
     */
    private static void move(Path from, Path to) {
        if (!Files.exists(from)) {
            throw new LaunchException("expected " + from + " but it is not there");
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new LaunchException("could not move " + from + " to " + to, e);
        }
    }

    private static List<Path> jars(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> ModFile.stripDisabled(path.getFileName().toString()).endsWith(".jar"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + directory, e);
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                    DigestInputStream digesting = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[64 * 1024];
                while (digesting.read(buffer) != -1) {
                    // Reading is what feeds the digest.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("could not hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
