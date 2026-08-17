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

            if (options.containsKey("candidates")) {
                selection(instance, options, control, listener);
                return;
            }

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

    /** One planned experiment: the selection driver over the chosen candidates. */
    private static void execute(
            cx.mia.lucent.laymark.runner.gui.PlanningView.Choice choice,
            RunControl control,
            ExperimentListener listener) {
        try {
            listener.named(choice.displayNames());
            String runId = LocalDateTime.now().format(RUN_ID);
            Path outputDirectory =
                    choice.instance()
                            .gameDirectory()
                            .resolve(Laymark.WORK_DIR)
                            .resolve(runId)
                            .toAbsolutePath();
            RunPlan plan = plan(choice.instance(), runId, outputDirectory);

            SelectionRun.execute(
                    choice.instance(),
                    plan,
                    choice.baseline(),
                    List.copyOf(choice.candidates()),
                    choice.requires(),
                    choice.conflicts(),
                    choice.modIdByFile(),
                    choice.schedule(),
                    outputDirectory,
                    sceneRoot(choice.instance()),
                    // From the plan, not a constant. A fixed ceiling shorter than the captures it
                    // contains kills the game part-way and reports the result as a hang.
                    plan.timeout(),
                    control,
                    listener);
            System.out.printf("%nreport written to %s%n", outputDirectory.resolve("report.md"));
        } catch (Exception e) {
            // On stdout with the trace, not a one-line stderr aside: this is the only account of
            // why an experiment died, and it has to land in the window's log where the operator
            // is actually looking.
            System.out.println("\nthe experiment failed: " + e.getMessage());
            e.printStackTrace(System.out);
            listener.finished(null);
        }
    }

    /**
     * A selection from the command line: the planner's choices, as flags.
     *
     * <p>Everything the window offers has a flag equivalent and both reach the same driver, so a
     * run planned in the window is the run these flags describe. Candidates name installed jars,
     * with or without {@code .jar}; the baseline mode mirrors the planner's presets.
     */
    private static void selection(
            ModrinthInstance instance,
            Map<String, String> options,
            RunControl control,
            ExperimentListener listener)
            throws IOException {

        Path modsDir = instance.gameDirectory().resolve("mods");
        List<Path> jars;
        try (var entries = Files.list(modsDir)) {
            jars =
                    entries.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".jar"))
                            .toList();
        }
        var cache = cx.mia.lucent.laymark.runner.select.ProbeCache.open();
        var probed = cx.mia.lucent.laymark.runner.select.JarProbe.inspect(jars, cache);
        cache.persist();
        var overrides =
                cx.mia.lucent.laymark.runner.select.DependencyOverrides.load(
                        instance.gameDirectory());
        var graph =
                cx.mia.lucent.laymark.core.select.DependencyGraph.merge(
                        probed.graph(), overrides.graph());

        Map<String, String> fileByModId = probed.providerByModId();
        Map<String, java.util.Set<String>> requires = new java.util.LinkedHashMap<>();
        probed.modIdByFile()
                .forEach(
                        (file, modId) -> {
                            java.util.Set<String> required = new java.util.TreeSet<>();
                            for (String neededId : graph.directRequirementsOf(modId)) {
                                String neededFile = fileByModId.get(neededId);
                                if (neededFile != null) {
                                    required.add(neededFile);
                                }
                            }
                            if (!required.isEmpty()) {
                                requires.put(file, required);
                            }
                        });

        List<cx.mia.lucent.laymark.core.select.Branching.Conflict> conflicts =
                new java.util.ArrayList<>(probed.conflicts());
        for (var conflict : overrides.conflicts()) {
            String a = fileByModId.get(conflict.a());
            String b = fileByModId.get(conflict.b());
            if (a != null && b != null) {
                conflicts.add(new cx.mia.lucent.laymark.core.select.Branching.Conflict(a, b));
            }
        }

        java.util.Set<String> installed =
                new cx.mia.lucent.laymark.runner.materialize.ModsDirectory(
                                instance.gameDirectory())
                        .read()
                        .enabledNames();
        java.util.Set<String> instrumentation = new java.util.TreeSet<>();
        for (String file : installed) {
            String modId = probed.modIdByFile().get(file);
            if (modId != null && Laymark.INSTRUMENTATION_MOD_IDS.contains(modId)) {
                instrumentation.add(file);
            }
        }

        List<String> candidates = new java.util.ArrayList<>();
        for (String raw : required(options, "candidates").split(",")) {
            String name = raw.trim();
            String resolved =
                    installed.stream()
                            .filter(f -> f.equals(name) || f.equals(name + ".jar"))
                            .findFirst()
                            .orElseGet(
                                    () ->
                                            probed.modIdByFile().entrySet().stream()
                                                    .filter(e -> e.getValue().equals(name))
                                                    .map(Map.Entry::getKey)
                                                    .findFirst()
                                                    .orElse(null));
            if (resolved == null) {
                throw new LaunchException(
                        "no installed mod matches candidate '" + name
                                + "'; name the jar file or the mod id");
            }
            candidates.add(resolved);
        }

        String mode = options.getOrDefault("baseline", "pack");
        java.util.Set<String> floor =
                switch (mode) {
                    case "pack" -> new java.util.TreeSet<>(installed);
                    case "blank" -> new java.util.TreeSet<>();
                    case "parent" -> {
                        var added =
                                cx.mia.lucent.laymark.runner.materialize.InlayIndex.addedMods(
                                        instance.gameDirectory());
                        var kept = new java.util.TreeSet<>(installed);
                        // No index means no recorded ancestor: the pack underneath is vanilla.
                        kept.removeAll(added == null ? installed : added);
                        yield kept;
                    }
                    default ->
                            throw new LaunchException(
                                    "--baseline must be pack, blank or parent, got " + mode);
                };
        candidates.forEach(floor::remove);
        floor.addAll(instrumentation);

        var schedule =
                new cx.mia.lucent.laymark.core.experiment.Schedule(
                        cx.mia.lucent.laymark.core.experiment.RoundTemplate.parse(
                                options.getOrDefault("schedule", "A,B,C,B,C")),
                        Integer.parseInt(options.getOrDefault("baseline-every", "5")));

        execute(
                new cx.mia.lucent.laymark.runner.gui.PlanningView.Choice(
                        instance,
                        floor,
                        new java.util.TreeSet<>(candidates),
                        requires,
                        List.copyOf(conflicts),
                        probed.modIdByFile(),
                        schedule,
                        probed.displayNameByFile()),
                control,
                listener);
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
  --candidates <a,b,..>   run a selection over these mods (jar names or mod ids);
                          rounds promote the single best into the baseline and rerun
  --baseline <mode>       what candidates are measured against: pack (default),
                          blank, or parent (the Inlay layer underneath)
  --schedule <template>   round template (default A,B,C,B,C)
  --baseline-every <n>    max candidate arms between drift-check baselines (default 5)
  --gui                   open a window with status, schedule, and pause/stop
  --selftest              run N identical baselines and check none beats another
  --arms <n>              how many baselines the self-test uses (default 4)
                %n""",
                Laymark.PROTOCOL_VERSION);
    }
}
