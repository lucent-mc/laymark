package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.scenario.ConfigCodec;
import cx.mia.lucent.laymark.core.scenario.ScenarioConfig;
import cx.mia.lucent.laymark.core.scenario.PresetRef;
import cx.mia.lucent.laymark.core.scenario.ScenarioDefinition;
import cx.mia.lucent.laymark.core.scenario.StopSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runner entry point.
 *
 * <p>No interactive CLI: invocation arguments are fine, but there is no TUI, no subcommand tree
 * and no prompting. Every ambiguity is resolved by an argument or fails the run, because a
 * selection run may go unattended for days.
 */
public final class Main {

    private Main() {}

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Above any terrain vanilla generates, so the pose does not depend on the seed's landscape. */
    private static final double OBSERVATION_ALTITUDE = 200;

    public static void main(String[] args) {
        Map<String, String> options = parse(args);

        if (options.containsKey("help") || options.isEmpty()) {
            usage();
            return;
        }

        try {
            ModrinthInstance instance =
                    new ModrinthInstance(
                            options.containsKey("root")
                                    ? Path.of(options.get("root"))
                                    : ModrinthInstance.defaultRoot(),
                            required(options, "profile"),
                            required(options, "version"));

            String runId = LocalDateTime.now().format(RUN_ID);
            // Absolute, always. The harness reads this path from inside the game process, whose
            // working directory is the instance -- a relative path would write results into the
            // modpack.
            Path outputDirectory =
                    Path.of(options.getOrDefault("out", "benchmark-results"))
                            .resolve(runId)
                            .toAbsolutePath();

            RunPlan plan = plan(runId, outputDirectory, options);

            if (options.containsKey("selftest")) {
                selfTest(instance, plan, outputDirectory, sceneRoot(options), options);
                return;
            }

            RunResult result =
                    BenchmarkRun.execute(
                            instance,
                            plan,
                            outputDirectory,
                            sceneRoot(options),
                            Duration.ofSeconds(
                                    Long.parseLong(options.getOrDefault("timeout", "900"))));

            BenchmarkRun.print(result);
            System.out.printf("%nresults written to %s%n", outputDirectory);
            if (!result.complete()) {
                // A partial run is not a successful run, and an unattended schedule chaining
                // invocations has nothing to read but the exit code.
                System.exit(2);
            }

        } catch (LaunchException e) {
            System.err.println("launch failed: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("  caused by: " + e.getCause());
            }
            System.exit(1);
        } catch (Exception e) {
            System.err.println("failed: " + e);
            System.exit(1);
        }
    }

    /**
     * Resolves the scenarios to run into a plan.
     *
     * <p>A {@code --config} file is the real path; the flags below build an equivalent one-scenario
     * config when none is given, so a quick invocation stays a quick invocation. Either way the
     * plan is produced by the same resolution, so what a flag means and what a config field means
     * cannot drift apart.
     *
     * <p>Laymark ships no scenarios of its own. What to measure is the operator's decision.
     */
    private static RunPlan plan(String runId, Path outputDirectory, Map<String, String> options) {
        ScenarioConfig config =
                options.containsKey("config")
                        ? readConfig(Path.of(options.get("config")))
                        : configFromFlags(options);
        return config.resolve(runId, outputDirectory.toString());
    }

    /**
     * Four identical baseline runs, which must report no difference between any of them.
     *
     * <p>The first evidence the spec demands, and the cheapest thing that can invalidate
     * everything else: if Laymark claims one identical run beat another, nothing it says about a
     * real candidate is worth reading. Every mod currently installed participates and stays
     * enabled in every arm, so no file moves and the only variable is the machine.
     */
    private static void selfTest(
            ModrinthInstance instance,
            RunPlan plan,
            Path outputDirectory,
            Path sceneRoot,
            Map<String, String> options)
            throws java.io.IOException {

        var mods = new cx.mia.lucent.laymark.runner.materialize.ModsDirectory(instance.gameDirectory());
        var installed = mods.read().enabledNames();

        // Alternating B,C,B,C... where the "candidate" is byte-identical to the baseline. All
        // baselines would compare nothing at all, which is a test that cannot fail.
        int arms = Integer.parseInt(options.getOrDefault("arms", "6"));
        List<cx.mia.lucent.laymark.core.experiment.Arm> runs = new java.util.ArrayList<>();
        for (int i = 0; i < arms; i++) {
            boolean baseline = i % 2 == 0;
            runs.add(
                    new cx.mia.lucent.laymark.core.experiment.Arm(
                            baseline ? "baseline" : "control",
                            baseline
                                    ? cx.mia.lucent.laymark.core.experiment.Arm.Kind.BASELINE
                                    : cx.mia.lucent.laymark.core.experiment.Arm.Kind.CANDIDATE,
                            installed));
        }

        var report =
                ExperimentRun.execute(
                        instance,
                        plan,
                        runs,
                        installed,
                        outputDirectory,
                        sceneRoot,
                        Duration.ofSeconds(Long.parseLong(options.getOrDefault("timeout", "900"))));

        System.out.printf("%nself-test: %d runs, %d voided window(s)%n", arms, report.voids().size());
        if (report.comparisons().isEmpty()) {
            System.out.println("FAILED: nothing was compared, so nothing was tested");
            System.exit(2);
        }
        boolean clean = true;
        for (var comparison : report.comparisons()) {
            System.out.printf("  %s%n", comparison.describe());
            if (comparison.band() != cx.mia.lucent.laymark.core.stats.Band.NO_MEASURABLE_DIFFERENCE) {
                clean = false;
            }
        }
        System.out.println(
                clean
                        ? "PASSED: no identical run beat another"
                        : "FAILED: an identical run was reported as different");
        if (!clean) {
            System.exit(2);
        }
        System.out.printf("report written to %s%n", outputDirectory.resolve("report.md"));
    }

    /** Scene paths are relative to the config that declared them, or to the working directory. */
    private static Path sceneRoot(Map<String, String> options) {
        Path config = options.containsKey("config") ? Path.of(options.get("config")) : null;
        Path parent = config == null ? null : config.toAbsolutePath().getParent();
        return parent == null ? Path.of("").toAbsolutePath() : parent;
    }

    private static ScenarioConfig readConfig(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new LaunchException("no scenario config at " + path);
        }
        try {
            return ConfigCodec.read(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /** The one-scenario shape the flags describe. */
    private static ScenarioConfig configFromFlags(Map<String, String> options) {
        int renderDistance = Integer.parseInt(options.getOrDefault("render-distance", "12"));
        Preset defaults = Preset.defaults();
        Preset preset =
                new Preset(
                        renderDistance,
                        renderDistance,
                        defaults.framerateLimit(),
                        defaults.vsync(),
                        defaults.particles(),
                        defaults.clouds(),
                        defaults.entityShadows(),
                        defaults.biomeBlendRadius(),
                        defaults.fieldOfView());

        long captureMillis =
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("duration", "30")))
                        .toMillis();

        return ScenarioConfig.of(
                new ScenarioDefinition(
                        "resident-render",
                        List.of(),
                        List.of(Phase.RESIDENT_RENDER),
                        StopSpec.of(StopCondition.Kind.TIME, captureMillis, 0),
                        Integer.parseInt(options.getOrDefault("repetitions", "1")),
                        PresetRef.inline(preset),
                        Pose.lookingDown(0.5, OBSERVATION_ALTITUDE, 0.5),
                        Long.parseLong(options.getOrDefault("seed", "1")),
                        false,
                        List.of()));
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new LaunchException("--" + name + " is required");
        }
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new LaunchException("unexpected argument: " + arg);
            }
            String name = arg.substring(2);
            int equals = name.indexOf('=');
            if (equals >= 0) {
                options.put(name.substring(0, equals), name.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(name, args[++i]);
            } else {
                options.put(name, "");
            }
        }
        return options;
    }

    private static void usage() {
        System.out.printf(
                """
                laymark runner (protocol v%d)

                Runs one scenario in a disposable world and reports its frame-time distribution.

                  --config <path>         scenario config to run; without it the flags below
                          describe a single resident-render scenario
  --profile <name>        Modrinth App profile directory name (required)
                  --version <id>          version id, e.g. 26.1.2-26.1.2.95 (required)
                  --root <path>           Modrinth App data directory (default: platform location)
                  --out <path>            results root (default: benchmark-results)
                  --seed <n>              world seed (default 1)
                  --duration <seconds>    capture window (default 30)
                  --repetitions <n>       repeats, each in a fresh world (default 1)
                  --render-distance <n>   chunks, also used as simulation distance (default 12)
                  --timeout <seconds>     how long to wait for the run (default 900)
  --selftest              run N identical baselines and check none beats another
  --arms <n>              how many baselines the self-test uses (default 4)
                %n""",
                Laymark.PROTOCOL_VERSION);
    }
}
