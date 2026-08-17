package cx.mia.lucent.laymark.runner.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.materialize.FileOperation;
import cx.mia.lucent.laymark.core.materialize.InstanceState;
import cx.mia.lucent.laymark.core.materialize.Materialization;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Still tier 1: a real filesystem, but not a real instance and no game. Renames are the whole
 * mechanism, so they are worth exercising against an actual disk — {@code ATOMIC_MOVE} and file
 * locking are where this would fail in practice, and neither shows up in an in-memory double.
 */
class ModsDirectoryTest {

    @TempDir Path instance;

    private ModsDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new ModsDirectory(instance);
    }

    private void jar(String name, String contents) throws IOException {
        Path file = instance.resolve("mods").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private boolean exists(String relative) {
        return Files.exists(instance.resolve(relative));
    }

    @Test
    void readsEnabledAndDisabledJarsApart() throws IOException {
        jar("sodium.jar", "a");
        jar("candidate.jar.disabled", "b");

        InstanceState state = directory.read();

        assertEquals(Set.of("sodium.jar"), state.enabledNames());
        assertEquals(Set.of("candidate.jar"), state.disabledNames());
    }

    /** The only way to notice a jar replaced between rounds. */
    @Test
    void hashesContentSoAReplacedJarIsNotTheSameState() throws IOException {
        jar("sodium.jar", "original");
        InstanceState before = directory.read();

        Files.writeString(instance.resolve("mods").resolve("sodium.jar"), "swapped");
        InstanceState after = directory.read();

        assertFalse(before.matches(after), "same name, different bytes, must not read as unchanged");
    }

    @Test
    void ignoresFilesThatAreNotJars() throws IOException {
        jar("sodium.jar", "a");
        Files.writeString(instance.resolve("mods").resolve("README.txt"), "not a mod");

        assertEquals(Set.of("sodium.jar"), directory.read().enabledNames());
    }

    /** Toggling by rename is what makes this work on both loaders without loader-specific code. */
    @Test
    void disablingRenamesRatherThanDeletes() throws IOException {
        jar("candidate.jar", "a");

        directory.apply(List.of(new FileOperation.Disable("candidate.jar")));

        assertFalse(exists("mods/candidate.jar"));
        assertTrue(exists("mods/candidate.jar.disabled"));
        assertEquals("a", Files.readString(instance.resolve("mods/candidate.jar.disabled")));
    }

    @Test
    void enablingRenamesBack() throws IOException {
        jar("candidate.jar.disabled", "a");

        directory.apply(List.of(new FileOperation.Enable("candidate.jar")));

        assertTrue(exists("mods/candidate.jar"));
        assertFalse(exists("mods/candidate.jar.disabled"));
    }

    @Test
    void withholdingMovesOutOfModsAndRestoringBringsItBack() throws IOException {
        jar("unrelated.jar", "a");

        directory.apply(List.of(new FileOperation.Withhold("unrelated.jar")));
        assertFalse(exists("mods/unrelated.jar"));
        assertTrue(exists("laymark/withheld/unrelated.jar"));

        directory.apply(List.of(new FileOperation.Restore("unrelated.jar")));
        assertTrue(exists("mods/unrelated.jar"));
        assertFalse(exists("laymark/withheld/unrelated.jar"));
    }

    /** A rename fails for reasons an operator can act on, so the message names both paths. */
    @Test
    void namesBothPathsWhenAMoveCannotHappen() {
        LaunchException e =
                assertThrows(
                        LaunchException.class,
                        () -> directory.apply(List.of(new FileOperation.Disable("ghost.jar"))));
        assertTrue(e.getMessage().contains("ghost.jar"), e.getMessage());
    }

    /** The end-to-end shape: plan an arm, apply it, and confirm the folder is that arm. */
    @Test
    void materialisesAnArmAndVerifiesIt() throws IOException {
        jar("sodium.jar", "a");
        jar("candidate.jar", "b");
        jar("unrelated.jar", "c");

        Set<String> participants = Set.of("sodium.jar", "candidate.jar");
        Set<String> baselineArm = Set.of("sodium.jar");

        directory.apply(Materialization.plan(directory.read(), participants, baselineArm));
        Materialization.verify(directory.read(), baselineArm);

        assertTrue(exists("mods/sodium.jar"));
        assertTrue(exists("mods/candidate.jar.disabled"));
        assertTrue(exists("laymark/withheld/unrelated.jar"), "non-participants leave mods/");
    }

    /** Switching arms between launches is the operation the whole design exists for. */
    @Test
    void switchesBetweenArmsWithoutLosingAnything() throws IOException {
        jar("sodium.jar", "a");
        jar("candidate.jar", "b");
        Set<String> participants = Set.of("sodium.jar", "candidate.jar");

        directory.apply(Materialization.plan(directory.read(), participants, Set.of("sodium.jar")));
        directory.apply(Materialization.plan(directory.read(), participants, participants));

        Materialization.verify(directory.read(), participants);
        assertEquals("b", Files.readString(instance.resolve("mods/candidate.jar")));
    }

    /** Recovery restores the recorded start, so an interrupted run ends like a clean one. */
    @Test
    void restoresTheInstanceToHowItWasFound() throws IOException {
        jar("sodium.jar", "a");
        jar("candidate.jar", "b");
        jar("unrelated.jar", "c");
        InstanceState initial = directory.read();

        directory.apply(
                Materialization.plan(initial, Set.of("sodium.jar"), Set.of("sodium.jar")));
        directory.apply(Materialization.restore(directory.read(), initial));

        assertTrue(directory.read().matches(initial), "the instance must be exactly as found");
        assertTrue(exists("mods/unrelated.jar"));
    }

    /** Verification is the guard, so it has to fail on a folder that merely looks plausible. */
    @Test
    void verificationCatchesARenameThatDidNotHappen() throws IOException {
        jar("sodium.jar", "a");
        jar("candidate.jar", "b");

        assertThrows(
                Exception.class,
                () -> Materialization.verify(directory.read(), Set.of("sodium.jar")));
    }
}
