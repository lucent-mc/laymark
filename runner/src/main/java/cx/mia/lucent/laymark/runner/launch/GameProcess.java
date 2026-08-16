package cx.mia.lucent.laymark.runner.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A launched game, owned by the runner.
 *
 * <p>Owning the child is the entire reason the runner builds its own invocation instead of asking
 * a launcher to do it. A launcher deep link is fire-and-forget: no exit code, no error channel,
 * no pid, with every failure surfacing as a toast on someone's screen. Spawning it here gives all
 * three for free, and is also the only way to pin heap size and GC flags — which would otherwise
 * sit underneath every A/B comparison as an uncontrolled confound.
 *
 * <p>stdout and stderr are redirected to files rather than inherited. They must be drained one way
 * or another: leave a child's output in an OS pipe nobody reads and it blocks on its next log
 * write, which for a benchmark means a hang mid-run with no indication why.
 */
public final class GameProcess implements AutoCloseable {

    private final Process process;

    private GameProcess(Process process) {
        this.process = process;
    }

    /**
     * @param javaExecutable the JVM to launch with. Explicit rather than inherited: the game runs
     *     on the JVM the instance expects, which is not necessarily the one the runner runs on.
     * @param argv everything after the executable, as built by {@link LaunchAssembly}
     */
    public static GameProcess start(
            Path javaExecutable, List<String> argv, Path workingDirectory, Path stdout, Path stderr) {

        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(argv);

        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(workingDirectory.toFile())
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile());
        try {
            return new GameProcess(builder.start());
        } catch (IOException e) {
            throw new LaunchException("could not start " + javaExecutable, e);
        }
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** @return the exit code, or empty if it was still running when the wait expired */
    public java.util.OptionalInt waitFor(Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    ? java.util.OptionalInt.of(process.exitValue())
                    : java.util.OptionalInt.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("interrupted waiting for the game to exit", e);
        }
    }

    /**
     * Ends the run.
     *
     * <p>Killing the process is the correct abort for a benchmark: there is nothing to negotiate,
     * the plan was fully resolved before launch, and the mod-directory transaction is rolled back
     * by the runner afterwards regardless of how the game ended.
     */
    public void terminate(Duration grace) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (waitFor(grace).isEmpty()) {
            process.destroyForcibly();
            waitFor(grace);
        }
    }

    @Override
    public void close() {
        terminate(Duration.ofSeconds(10));
    }
}
