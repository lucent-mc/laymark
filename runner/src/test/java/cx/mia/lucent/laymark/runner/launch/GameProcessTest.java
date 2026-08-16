package cx.mia.lucent.laymark.runner.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Still tier 1: spawns a JVM, but not <em>the</em> JVM. Process ownership is the property under
 * test, and it does not need Minecraft to be exercised.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GameProcessTest {

    @TempDir Path temp;

    private static Path thisJvm() {
        String executable =
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    @Test
    void ownsTheChildAndReportsItsExitCode() throws IOException {
        Path out = temp.resolve("out.log");
        Path err = temp.resolve("err.log");

        try (GameProcess process =
                GameProcess.start(thisJvm(), List.of("--version"), temp, out, err)) {
            assertTrue(process.pid() > 0, "owning the child is why we build the invocation");
            var exit = process.waitFor(Duration.ofSeconds(30));
            assertTrue(exit.isPresent(), "the child should have exited");
            assertEquals(0, exit.getAsInt());
            assertFalse(process.isAlive());
        }
        assertTrue(Files.exists(out) && Files.exists(err), "both streams must be drained to disk");
    }

    /**
     * Output is redirected rather than inherited. A child whose stdout nobody reads blocks on its
     * next write once the OS pipe fills -- for a benchmark that is a hang mid-run with no
     * indication why.
     */
    @Test
    void redirectsOutputRatherThanLeavingItInAPipe() throws IOException {
        Path out = temp.resolve("out.log");
        try (GameProcess process =
                GameProcess.start(
                        thisJvm(),
                        List.of("-e", "unused"), // invalid: we only care that stderr lands on disk
                        temp,
                        out,
                        temp.resolve("err.log"))) {
            process.waitFor(Duration.ofSeconds(30));
        }
        assertTrue(Files.exists(out));
    }

    @Test
    void terminateIsIdempotentAndSurvivesAnAlreadyDeadChild() throws IOException {
        try (GameProcess process =
                GameProcess.start(
                        thisJvm(),
                        List.of("--version"),
                        temp,
                        temp.resolve("o.log"),
                        temp.resolve("e.log"))) {
            process.waitFor(Duration.ofSeconds(30));
            process.terminate(Duration.ofSeconds(5));
            process.terminate(Duration.ofSeconds(5));
            assertFalse(process.isAlive());
        }
    }

    /** A long-lived child must actually die when the run ends. */
    @Test
    void terminateStopsALiveChild() throws IOException {
        try (GameProcess process =
                GameProcess.start(
                        thisJvm(),
                        List.of("-cp", ".", "-Dx=1", "--version"),
                        temp,
                        temp.resolve("o2.log"),
                        temp.resolve("e2.log"))) {
            process.terminate(Duration.ofSeconds(20));
            assertFalse(process.isAlive(), "killing the process is the correct abort");
        }
    }

    @Test
    void reportsAMissingExecutableClearly() {
        LaunchException e =
                assertThrows(
                        LaunchException.class,
                        () ->
                                GameProcess.start(
                                        temp.resolve("no-such-java"),
                                        List.of(),
                                        temp,
                                        temp.resolve("o.log"),
                                        temp.resolve("e.log")));
        assertTrue(e.getMessage().contains("no-such-java"), e.getMessage());
    }
}
