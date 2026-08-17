package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.scenario.ConfigCodec;
import cx.mia.lucent.laymark.core.scenario.ScenarioConfig;
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

    public static void main(String[] args) {
        Map<String, String> options = parse(args);

        if (options.containsKey("help")) {
            usage();
            return;
        }
        if (options.isEmpty()) {
            // Double-clicking the jar lands here. Nothing was said about what to run, so ask.
            plannedRun();
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
            // working directory is the instance -- a relative path would resolve somewhere else.
            Path outputDirectory =
                    (options.containsKey("out")
                                    ? Path.of(options.get("out"))
                                    : instance.gameDirectory().resolve(Laymark.WORK_DIR))
                            .resolve(runId)
                            .toAbsolutePath();

            RunPlan plan = plan(instance, runId, outputDirectory);

            RunControl control = new RunControl();
            ExperimentListener listener = ExperimentListener.none();
            if (options.containsKey("gui")) {
                var window = cx.mia.lucent.laymark.runner.gui.RunnerWindow.openRunning(control);
                listener = window;
                // Tee rather than plumb a log callback through every layer: the runner already
                // says everything worth showing on stdout, and a window that shows less than the
                // console is a window nobody trusts.
                System.setOut(window.tee(System.out));
                System.setErr(window.tee(System.err));
            }

            // With a window attached the exit code has no audience, and taking the process down
            // closes the report someone opened the window to read.
            boolean windowed = options.containsKey("gui");

            if (options.containsKey("selftest")) {
                selfTest(
                        instance,
                        plan,
                        outputDirectory,
                        sceneRoot(instance),
                        options,
                        control,
                        listener,
                        windowed);
                return;
            }

            // A single run is a one-arm schedule as far as the window is concerned.
            var arm =
                    new cx.mia.lucent.laymark.core.experiment.Arm(
                            "run", cx.mia.lucent.laymark.core.experiment.Arm.Kind.BASELINE, java.util.Set.of());
            listener.scheduleBuilt(new ExperimentListener.Slate(List.of(arm), 1, 1, 1));
            listener.runStarted(0, arm);
            RunResult result =
                    BenchmarkRun.execute(
                            instance,
                            plan,
                            outputDirectory,
                            sceneRoot(instance),
                            timeout(options, plan),
                            control,
                            listener);
            listener.runFinished(0, arm, 0, false);
            listener.finished(null);

            BenchmarkRun.print(result);
            System.out.printf("%nresults written to %s%n", outputDirectory);
            if (!result.complete() && !windowed) {
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
     * Opens the planning window and runs whatever it is told to run.
     *
     * <p>The invocation with no arguments, which is what double-clicking the jar produces. Every
     * choice the window offers has a flag equivalent, and both end at the same {@code
     * ExperimentRun.execute} — so a run planned in the window is the run those flags describe.
     */
    private static void plannedRun() {
        RunControl control = new RunControl();
        var window =
                cx.mia.lucent.laymark.runner.gui.RunnerWindow.open(
                        control,
                        (choice, runControl, listener) ->
                                Thread.ofPlatform()
                                        .name("laymark-experiment")
                                        .start(() -> execute(choice, runControl, listener)));
        System.setOut(window.tee(System.out));
        System.setErr(window.tee(System.err));
    }

    /** One planned experiment: the chosen candidates, in the order the schedule asks for. */
    private static void execute(
            cx.mia.lucent.laymark.runner.gui.PlanningView.Choice choice,
            RunControl control,
            ExperimentListener listener) {
        try {
            String runId = LocalDateTime.now().format(RUN_ID);
            Path outputDirectory =
                    choice.instance()
                            .gameDirectory()
                            .resolve(Laymark.WORK_DIR)
                            .resolve(runId)
                            .toAbsolutePath();
            RunPlan plan = plan(choice.instance(), runId, outputDirectory);

            // The roster says it outright: baseline mods load in every arm, candidates load only in
            // their own, and anything installed but named neither is withheld for the whole run.
            var floor = choice.baseline();

            var baseline =
                    new cx.mia.lucent.laymark.core.experiment.Arm(
                            "baseline",
                            cx.mia.lucent.laymark.core.experiment.Arm.Kind.BASELINE,
                            floor);
            var acclimation =
                    new cx.mia.lucent.laymark.core.experiment.Arm(
                            "acclimation",
                            cx.mia.lucent.laymark.core.experiment.Arm.Kind.ACCLIMATION,
                            floor);
            List<cx.mia.lucent.laymark.core.experiment.Arm> candidates =
                    choice.candidates().stream()
                            .map(
                                    candidate -> {
                                        var enabled = new java.util.TreeSet<>(floor);
                                        enabled.add(candidate);
                                        return new cx.mia.lucent.laymark.core.experiment.Arm(
                                                candidate,
                                                cx.mia.lucent.laymark.core.experiment.Arm.Kind
                                                        .CANDIDATE,
                                                enabled);
                                    })
                            .toList();

            var arms = choice.schedule().expand(candidates, baseline, acclimation, false);
            System.out.printf(
                    "schedule %s over %d candidate(s): %d arms%n",
                    choice.schedule().template(), candidates.size(), arms.size());

            ExperimentRun.execute(
                    choice.instance(),
                    plan,
                    arms,
                    // Baseline mods are participants too. Passing only the candidates would make
                    // every arm enable mods it does not own, which materialisation rejects.
                    choice.participants(),
                    outputDirectory,
                    sceneRoot(choice.instance()),
                    // From the plan, not a constant. A fixed ceiling shorter than the captures it
                    // contains kills the game part-way and reports the result as a hang.
                    plan.timeout(),
                    control,
                    listener);
            System.out.printf("%nreport written to %s%n", outputDirectory.resolve("report.md"));
        } catch (Exception e) {
            System.err.println("failed: " + e);
            listener.finished(null);
        }
    }

    /**
     * Resolves the instance's own config into this run's plan.
     *
     * <p>{@code config/laymark.json} <em>is</em> the plan: hand-authored, the single source of
     * what a run measures, and read by runner and harness alike so the two sides cannot drift.
     * There is no other place scenarios come from — no flag-built scenario, no browsed file —
     * because a second source is a second thing the archived result could disagree with.
     *
     * <p>Laymark ships no scenarios of its own. What to measure is the operator's decision.
     */
    private static RunPlan plan(ModrinthInstance instance, String runId, Path outputDirectory) {
        return readConfig(instance.gameDirectory().resolve(Laymark.CONFIG_PATH))
                .resolve(runId, outputDirectory.toString());
    }

    /** Scene paths in the config resolve relative to the config's own directory. */
    private static Path sceneRoot(ModrinthInstance instance) {
        return instance.gameDirectory().resolve(Laymark.CONFIG_PATH).toAbsolutePath().getParent();
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
            Map<String, String> options,
            RunControl control,
            ExperimentListener listener,
            boolean windowed)
            throws java.io.IOException {

        var mods = new cx.mia.lucent.laymark.runner.materialize.ModsDirectory(instance.gameDirectory());
        var installed = mods.read().enabledNames();

        // Acclimation first, then alternating B,C,B,C... where the "candidate" is byte-identical
        // to the baseline. All baselines would compare nothing -- a test that cannot fail -- and
        // skipping acclimation makes session warm-up read as a position effect: verified on a real
        // run, where the control (always immediately after its baseline) measured 1.0% faster with
        // an interval of 0.5% to 1.6%. The discarded warm-up run is what absorbs that.
        int arms = Integer.parseInt(options.getOrDefault("arms", "6"));
        List<cx.mia.lucent.laymark.core.experiment.Arm> runs = new java.util.ArrayList<>();
        runs.add(
                new cx.mia.lucent.laymark.core.experiment.Arm(
                        "acclimation",
                        cx.mia.lucent.laymark.core.experiment.Arm.Kind.ACCLIMATION,
                        installed));
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
                        timeout(options, plan),
                        control,
                        listener);

        System.out.printf("%nself-test: %d runs, %d voided window(s)%n", arms, report.voids().size());
        if (report.comparisons().isEmpty()) {
            System.out.println("FAILED: nothing was compared, so nothing was tested");
            if (!windowed) {
                System.exit(2);
            }
            return;
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
        if (!clean && !windowed) {
            System.exit(2);
        }
        System.out.printf("report written to %s%n", outputDirectory.resolve("report.md"));
    }

    /**
     * How long to wait for one launch: what was asked for, or what the plan says it needs.
     *
     * <p>Derived by default rather than fixed, because a scenario already states its own ceiling
     * and a shorter launch timeout would kill the game part-way and report the run as a hang.
     */
    private static Duration timeout(Map<String, String> options, RunPlan plan) {
        String requested = options.get("timeout");
        return requested == null || requested.isBlank()
                ? plan.timeout()
                : Duration.ofSeconds(Long.parseLong(requested));
    }

    private static ScenarioConfig readConfig(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new LaunchException(
                    "no scenario config at " + path + "; it is hand-authored -- write your"
                            + " scenarios there");
        }
        try {
            return ConfigCodec.read(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
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

                Runs the instance's scenario config and reports what it measured.

                Scenarios come from one place: <instance>/config/laymark.json, hand-authored.
                Results and working state go to <instance>/.laymark/.

                With no arguments at all, opens the planning window.

                  --profile <name>        Modrinth App profile directory name (required)
                  --version <id>          version id, e.g. 26.1.2-26.1.2.95 (required)
                  --root <path>           Modrinth App data directory (default: platform location)
                  --out <path>            results root (default: <instance>/.laymark)
                  --timeout <seconds>     how long to wait for the run; by default derived from
                          the scenarios' own stop timeouts plus a launch allowance
  --gui                   open a window with status, schedule, and pause/stop
  --selftest              run N identical baselines and check none beats another
  --arms <n>              how many baselines the self-test uses (default 4)
                %n""",
                Laymark.PROTOCOL_VERSION);
    }
}
