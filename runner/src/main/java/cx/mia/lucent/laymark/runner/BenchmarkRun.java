package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.harness.Measurement;
import cx.mia.lucent.laymark.core.harness.MemorySnapshot;
import cx.mia.lucent.laymark.core.harness.SparkStatistics;
import cx.mia.lucent.laymark.core.harness.TimingChannel;
import cx.mia.lucent.laymark.core.harness.WorkCounters;
import cx.mia.lucent.laymark.core.plan.PlanCodec;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.ResultCodec;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.PhaseResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
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
import java.nio.file.StandardCopyOption;
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
            ModrinthInstance instance,
            RunPlan plan,
            Path outputDirectory,
            Path sceneRoot,
            Duration timeout,
            RunControl control,
            ExperimentListener listener)
            throws IOException {

        instance.requireUsable();
        Files.createDirectories(outputDirectory);

        Path events = outputDirectory.resolve("events.jsonl");
        Path stdout = outputDirectory.resolve("game-stdout.log");
        Path stderr = outputDirectory.resolve("game-stderr.log");

        requireConfig(instance);
        stageScenes(instance, plan, sceneRoot);

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
                            server.token(),
                            plan.runId(),
                            plan.outputDirectory());

            System.out.printf(
                    "listening on 127.0.0.1:%d%nrunning %d scenario(s) from plan %s%n",
                    server.port(), plan.scenarios().size(), plan.runId());

            try (GameProcess game =
                    GameProcess.start(
                            instance.javaExecutable(), argv, instance.gameDirectory(), stdout, stderr)) {

                // While this game lives, a stop kills it rather than waiting for the boundary.
                control.registerAbort(() -> game.terminate(Duration.ofSeconds(5)));

                HarnessServer.Session session = handshake(server, game, timeout, stdout, stderr);
                System.out.printf("handshake ok from pid %d; the run has started%n", session.pid());

                return collect(server, session, game, outputDirectory, timeout, listener, plan);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LaunchException("interrupted during the run", e);
            } finally {
                control.clearAbort();
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
            Duration timeout,
            ExperimentListener listener,
            RunPlan plan)
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
                                                    if (frame instanceof Frame.ScenarioStarted started) {
                                                        listener.scenarioStarted(
                                                                started.scenarioId(), started.repetition());
                                                    }
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

        return readResult(((Frame.RunFinished) outcome).resultPath(), plan);
    }

    private static RunResult readResult(String path, RunPlan plan) throws IOException {
        Path resultPath = Path.of(path);
        if (!Files.isRegularFile(resultPath)) {
            throw new LaunchException("the harness reported a result at " + path + " that is not there");
        }
        RunResult result = ResultCodec.read(Files.readString(resultPath, StandardCharsets.UTF_8));
        // The one check that catches a stale mod build. A harness from before a protocol-compatible
        // change happily runs whatever it knows how to read -- observed running a leftover plan
        // file from a previous run -- and everything downstream then scores the wrong scenarios.
        if (!plan.runId().equals(result.runId())) {
            throw new LaunchException(
                    "the game executed run " + result.runId() + " but this launch is "
                            + plan.runId() + "; the Laymark mod in the instance is a stale build"
                            + " running something it found on disk -- update mods/laymark-*.jar");
        }
        return result;
    }

    /**
     * Confirms the config the harness will read is there, before paying for a launch.
     *
     * <p>The runner never writes it. {@code config/laymark.json} is hand-authored and both sides
     * resolve the same document, so there is no plan file to stage and nothing to drift.
     */
    private static void requireConfig(ModrinthInstance instance) {
        if (!Files.isRegularFile(instance.gameDirectory().resolve(Laymark.CONFIG_PATH))) {
            throw new LaunchException(
                    "no scenario config at " + instance.gameDirectory().resolve(Laymark.CONFIG_PATH)
                            + "; it is hand-authored and the harness reads it from there");
        }
    }

    /**
     * Copies every scene a scenario references into the instance.
     *
     * <p>A config and its schematics live wherever the operator keeps them; the harness reads them
     * from inside the game, which has no idea where that was. Staging them under a known directory
     * is what lets a plan name a scene by something the harness can actually resolve.
     *
     * <p>Copied fresh every run rather than cached. A stale scene from a previous run would place
     * silently and measure geometry nobody configured, which is exactly the class of error the
     * count verification exists to catch — and cheaper to prevent than to detect.
     *
     * @param sceneRoot where the config's relative paths resolve from
     */
    private static void stageScenes(ModrinthInstance instance, RunPlan plan, Path sceneRoot)
            throws IOException {
        List<ScenePlacement> scenes =
                plan.scenarios().stream().flatMap(scenario -> scenario.content().stream()).toList();
        if (scenes.isEmpty()) {
            return;
        }

        Path destination = instance.gameDirectory().resolve(Laymark.SCENE_DIR);
        Files.createDirectories(destination);

        for (ScenePlacement scene : scenes) {
            Path source = sceneRoot.resolve(scene.schematic()).normalize();
            if (!Files.isRegularFile(source)) {
                // Before the launch, not after. A missing scene discovered in-game costs a game
                // start and reports as a harness failure rather than as the config error it is.
                throw new LaunchException("no scene file at " + source);
            }
            Path target = destination.resolve(scene.schematic()).normalize();
            if (!target.startsWith(destination)) {
                throw new LaunchException("scene path escapes the staging directory: " + scene.schematic());
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.printf("staged %d scene file(s)%n", scenes.size());
    }

    private static void describe(Frame frame) {
        System.out.println("  <- " + frame);
    }

    /**
     * A short human summary. The full document is the artifact; this is for the operator.
     *
     * <p>Prints the channels beneath the headline rather than only the headline, because the first
     * question after "it got slower" is always "where" — and the answer is already in the file.
     */
    public static void print(RunResult result) {
        System.out.printf("%nrun %s%n", result.runId());
        for (ScenarioResult scenario : result.scenarios()) {
            if (!scenario.measured()) {
                System.out.printf(
                        "  %-24s FAILED  %s%n", scenario.scenarioId(), scenario.failureReason());
                continue;
            }
            printScenario(scenario);
        }
        for (String flag : result.flags()) {
            System.out.printf("  ! %s%n", flag);
        }
    }

    private static void printScenario(ScenarioResult scenario) {
        System.out.printf(
                "  %-24s barrier %dms%n",
                scenario.scenarioId() + "#" + scenario.repetition(),
                scenario.barrier().totalMillis());
        scenario.segments().forEach(BenchmarkRun::printSegment);
        for (String flag : scenario.flags()) {
            System.out.printf("      ! %s%n", flag);
        }
    }

    /** One line per measured phase, then its channels beneath. */
    private static void printSegment(PhaseResult segment) {
        Measurement measurement = segment.measurement();
        FrameStatistics frames = measurement.frameStatistics();

        System.out.printf(
                "    %-22s %5d frames  mean %6.2fms (%5.1f fps)  p95 %6.2fms  p99 %6.2fms  max %6.2fms%n",
                segment.phase(),
                frames.count(),
                frames.meanMillis(),
                frames.meanFramesPerSecond(),
                frames.p95Millis(),
                frames.p99Millis(),
                frames.maxMillis());

        // Nested channels, widest first, so the gaps between them read as the decomposition they
        // are rather than as three competing measurements of the same thing.
        printChannel("render call", measurement.frameStatistics(TimingChannel.RENDER_CALL));
        printChannel("submit", measurement.frameStatistics(TimingChannel.SUBMIT));
        if (!measurement.gpu().isEmpty()) {
            printChannel("gpu", measurement.gpuStatistics());
        }
        SparkStatistics spark = measurement.spark();
        if (spark != null) {
            System.out.printf(
                    "      %-12s %5.1f tps    mean %6.2fms  p95 %6.2fms  max %6.2fms  (%ds window)%n",
                    "server",
                    spark.ticksPerSecond(),
                    spark.millisPerTickMean(),
                    spark.millisPerTickPercentile95(),
                    spark.millisPerTickMax(),
                    spark.windowMillis() / 1000);
        }

        WorkCounters work = measurement.work();
        if (work != null) {
            System.out.printf(
                    "      %-12s sections %+d  client chunks %+d  server chunks %+d%n",
                    "work", work.renderedSections(), work.clientChunks(), work.serverChunks());
        }
        if (measurement.memoryAfter() != null) {
            System.out.printf(
                    "      %-12s heap %6.1fMB%s%n",
                    "memory",
                    measurement.memoryAfter().heapUsedMegabytes(),
                    spark == null
                            ? ""
                            : "  %d collections, %dms paused"
                                    .formatted(spark.totalCollections(), spark.totalGcMillis()));
        }

        for (String flag : segment.flags()) {
            System.out.printf("      ! %s%n", flag);
        }
    }

    private static void printChannel(String label, FrameStatistics stats) {
        System.out.printf(
                "      %-12s %5d        mean %6.2fms  p95 %6.2fms  max %6.2fms%n",
                label, stats.count(), stats.meanMillis(), stats.p95Millis(), stats.maxMillis());
    }
}
