package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.plan.PlanCodec;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.ResultCodec;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import cx.mia.lucent.laymark.runner.launch.GameProcess;
import cx.mia.lucent.laymark.runner.launch.HostPlatform;
import cx.mia.lucent.laymark.runner.launch.LaunchAssembly;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import cx.mia.lucent.laymark.runner.launch.OfflineIdentity;
import cx.mia.lucent.laymark.runner.transport.HarnessServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One launch, start to finish: write the plan, run the game, collect the result.
 *
 * <p>The first thing in the project that produces a number. It cannot be tier-1 tested — it
 * launches a real game — which is also why the release gate keeps a manual in-game check: a
 * machine busy running CI is a machine whose thermal and load state makes it unfit to benchmark
 * on.
 */
public final class BenchmarkRun {

    private BenchmarkRun() {}

    /** How long to wait after the run reports finished before killing the process anyway. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    public static RunResult execute(
            ModrinthInstance instance, RunPlan plan, Path outputDirectory, Duration timeout)
            throws IOException {

        instance.requireUsable();
        Files.createDirectories(outputDirectory);

        Path events = outputDirectory.resolve("events.jsonl");
        Path stdout = outputDirectory.resolve("game-stdout.log");
        Path stderr = outputDirectory.resolve("game-stderr.log");

        writePlan(instance, plan);

        // Bind before launching. The port then exists before anything could connect to it, so
        // there is no startup race and nothing to discover.
        try (HarnessServer server = HarnessServer.bind(events)) {

            List<String> argv =
                    LaunchAssembly.assemble(
                            instance.descriptor(),
                            instance.layout(),
                            HostPlatform.current(),
                            OfflineIdentity.of("LaymarkProbe"),
                            server.port(),
                            server.token());

            System.out.printf(
                    "listening on 127.0.0.1:%d%nrunning %d scenario(s) from plan %s%n",
                    server.port(), plan.scenarios().size(), plan.runId());

            try (GameProcess game =
                    GameProcess.start(
                            instance.javaExecutable(), argv, instance.gameDirectory(), stdout, stderr)) {

                HarnessServer.Session session = handshake(server, game, timeout, stdout, stderr);
                System.out.printf("handshake ok from pid %d; the run has started%n", session.pid());

                return collect(server, session, game, outputDirectory, timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LaunchException("interrupted during the run", e);
            }
        }
    }

    private static HarnessServer.Session handshake(
            HarnessServer server, GameProcess game, Duration timeout, Path stdout, Path stderr) {
        try {
            return server.accept((int) timeout.toMillis());
        } catch (IOException e) {
            // A dead child explains a missing handshake far better than a socket timeout does.
            if (!game.isAlive()) {
                throw new LaunchException("the game exited before connecting; see " + stderr, e);
            }
            throw new LaunchException("the game is running but never connected; see " + stdout, e);
        }
    }

    /**
     * Reads frames until the harness reports a terminal outcome, then reads the result it wrote.
     *
     * <p>The pump runs on its own thread so a harness that goes silent cannot wedge the shutdown.
     * A benchmark left holding a machine is worse than one that failed.
     */
    private static RunResult collect(
            HarnessServer server,
            HarnessServer.Session session,
            GameProcess game,
            Path outputDirectory,
            Duration timeout)
            throws IOException, InterruptedException {

        AtomicReference<Frame> terminal = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Thread pump =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        server.pump(
                                                session,
                                                frame -> {
                                                    describe(frame);
                                                    if (frame instanceof Frame.RunFinished
                                                            || frame instanceof Frame.RunFailed) {
                                                        terminal.set(frame);
                                                        finished.countDown();
                                                    }
                                                });
                                    } catch (IOException | RuntimeException ignored) {
                                        // The socket closing is one of the ways a run ends.
                                    } finally {
                                        finished.countDown();
                                    }
                                });

        boolean reported = finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        game.waitFor(SHUTDOWN_GRACE);
        game.terminate(Duration.ofSeconds(15));
        pump.join(Duration.ofSeconds(5));

        Frame outcome = terminal.get();
        if (!reported || outcome == null) {
            throw new LaunchException(
                    "the run produced no terminal frame within "
                            + timeout.toSeconds()
                            + "s; the harness died or hung. Events so far: "
                            + outputDirectory.resolve("events.jsonl"));
        }
        if (outcome instanceof Frame.RunFailed failed) {
            throw new LaunchException("the run failed: " + failed.reason() + ": " + failed.detail());
        }

        return readResult(((Frame.RunFinished) outcome).resultPath());
    }

    private static RunResult readResult(String path) throws IOException {
        Path resultPath = Path.of(path);
        if (!Files.isRegularFile(resultPath)) {
            throw new LaunchException("the harness reported a result at " + path + " that is not there");
        }
        return ResultCodec.read(Files.readString(resultPath, StandardCharsets.UTF_8));
    }

    /**
     * Writes the plan into the instance so the harness can find it.
     *
     * <p>The one place the runner writes inside the instance. It is Laymark's own config
     * directory, and it is overwritten every run.
     */
    private static void writePlan(ModrinthInstance instance, RunPlan plan) throws IOException {
        Path path = instance.gameDirectory().resolve(Laymark.PLAN_PATH);
        Files.createDirectories(path.getParent());
        Files.writeString(path, PlanCodec.write(plan), StandardCharsets.UTF_8);
    }

    private static void describe(Frame frame) {
        System.out.println("  <- " + frame);
    }

    /** A short human summary. The full document is the artifact; this is for the operator. */
    public static void print(RunResult result) {
        System.out.printf("%nrun %s%n", result.runId());
        for (ScenarioResult scenario : result.scenarios()) {
            if (!scenario.measured()) {
                System.out.printf(
                        "  %-24s FAILED  %s%n", scenario.scenarioId(), scenario.failureReason());
                continue;
            }
            FrameStatistics stats = scenario.statistics();
            System.out.printf(
                    "  %-24s %5d frames  mean %6.2fms (%5.1f fps)  p95 %6.2fms  p99 %6.2fms  max %6.2fms%n",
                    scenario.scenarioId() + "#" + scenario.repetition(),
                    stats.count(),
                    stats.meanMillis(),
                    stats.meanFramesPerSecond(),
                    stats.p95Millis(),
                    stats.p99Millis(),
                    stats.maxMillis());
            for (String flag : scenario.flags()) {
                System.out.printf("      ! %s%n", flag);
            }
        }
        for (String flag : result.flags()) {
            System.out.printf("  ! %s%n", flag);
        }
    }
}
