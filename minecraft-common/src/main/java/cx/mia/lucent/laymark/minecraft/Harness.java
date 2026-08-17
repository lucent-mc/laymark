package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.HarnessRun;
import cx.mia.lucent.laymark.core.plan.PlanCodec;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.scenario.ConfigCodec;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.ResultCodec;
import cx.mia.lucent.laymark.core.result.RunResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Reads the plan, runs it, writes the result, and stops the game.
 *
 * <p>The whole run lives on one dedicated thread. It has to: the sequence is written as blocking
 * calls, and the thread it would otherwise block is the one that renders the frames it is trying
 * to measure. {@link ClientThread} is what bridges the two.
 *
 * <p>Loader-free by construction. A loader module decides <em>when</em> to call {@link #start} and
 * supplies the frame trigger; everything after that is vanilla.
 */
public final class Harness {

    private Harness() {}

    /** How long to wait for the client to finish booting before giving up on the run. */
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration BOOT_POLL = Duration.ofMillis(200);

    /**
     * Starts the run on its own thread and returns immediately.
     *
     * @param events where to report progress; the runner is on the other end
     */
    public static Thread start(ClientChannels channels, Consumer<Frame> events) {
        Thread thread = new Thread(() -> execute(channels, events), "laymark-harness");
        // Daemon: if the run dies in a way that leaves this thread parked, the game must still be
        // able to exit. A benchmark that will not quit is worse than one that failed.
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void execute(ClientChannels channels, Consumer<Frame> events) {
        try {
            if (!ClientThread.await("waiting for the client to boot", BOOT_TIMEOUT, BOOT_POLL, 3,
                    Readiness::idleAtMenu)) {
                throw new HarnessException("the client never finished booting");
            }

            RunPlan plan = readPlan();
            MinecraftHarnessPort port = new MinecraftHarnessPort(channels);
            RunResult result = new HarnessRun(plan, port, events).execute();
            Path written = writeResult(plan, withChannelFlags(result, port));

            events.accept(new Frame.RunFinished(written.toString()));
        } catch (RuntimeException e) {
            // Every failure is terminal here and every one has to be reported. The runner is
            // holding a socket open waiting for a verdict; dying quietly turns a diagnosable
            // failure into a timeout with nothing to read.
            events.accept(new Frame.RunFailed(e.getClass().getSimpleName(), String.valueOf(e.getMessage())));
        } finally {
            // Unconditional. The game exists to serve this run, so it exits when the run is over
            // whatever the outcome -- a benchmark left sitting at a title screen holds the
            // machine hostage for the rest of an unattended schedule.
            ClientThread.run("stopping the game", () -> Minecraft.getInstance().stop());
        }
    }

    /**
     * Records any channel that could not be measured on this machine.
     *
     * <p>Run level rather than per scenario, because a capability like GPU timer queries is a
     * property of the driver and does not come and go between scenarios. Said explicitly because
     * an empty GPU series and a GPU series of zeros look the same to a reader who was not told
     * which one this is.
     */
    private static RunResult withChannelFlags(RunResult result, MinecraftHarnessPort port) {
        List<String> flags = new ArrayList<>(result.flags());
        if (port.gpuUnavailableReason() != null) {
            flags.add("gpu timing unavailable: " + port.gpuUnavailableReason());
        }
        if (port.sparkUnavailableReason() != null) {
            flags.add("server statistics unavailable: " + port.sparkUnavailableReason());
        }
        if (flags.size() == result.flags().size()) {
            return result;
        }
        return new RunResult(result.runId(), result.protocolVersion(), result.scenarios(), flags);
    }

    /**
     * Resolves the hand-authored config into this launch's plan.
     *
     * <p>{@code config/laymark.json} is the single source of what a run measures; the runner reads
     * the same file, so the two sides cannot drift. The only run-shaped facts the config cannot
     * carry — which run this is and where its results go — arrive as system properties on the
     * command line the runner assembled.
     */
    private static RunPlan readPlan() {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve(Laymark.CONFIG_PATH);
        if (!Files.isRegularFile(path)) {
            throw new HarnessException("no scenario config at " + path);
        }
        String runId = System.getProperty(Laymark.PROPERTY_RUN_ID);
        String output = System.getProperty(Laymark.PROPERTY_OUTPUT);
        if (runId == null || output == null) {
            throw new HarnessException(
                    "launched without " + Laymark.PROPERTY_RUN_ID + " and " + Laymark.PROPERTY_OUTPUT
                            + "; the game was not started by the runner");
        }
        try {
            return ConfigCodec.read(Files.readString(path, StandardCharsets.UTF_8))
                    .resolve(runId, output);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /**
     * Writes the result, and the plan beside it.
     *
     * <p>Both, because a result is only interpretable next to the plan that produced it, and the
     * plan lives inside the instance where the next run will overwrite it.
     */
    private static Path writeResult(RunPlan plan, RunResult result) {
        Path directory = Path.of(plan.outputDirectory());
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve("plan.json"), PlanCodec.write(plan), StandardCharsets.UTF_8);
            Path resultPath = directory.resolve(Laymark.RESULT_FILE);
            Files.writeString(resultPath, ResultCodec.write(result), StandardCharsets.UTF_8);
            return resultPath;
        } catch (IOException e) {
            throw new UncheckedIOException("could not write results to " + directory, e);
        }
    }
}
