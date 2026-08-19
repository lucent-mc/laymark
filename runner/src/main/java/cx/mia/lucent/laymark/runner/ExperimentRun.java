package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.experiment.Arm;
import cx.mia.lucent.laymark.core.experiment.Schedule;
import cx.mia.lucent.laymark.core.materialize.InstanceState;
import cx.mia.lucent.laymark.core.materialize.Materialization;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.report.MarkdownReport;
import cx.mia.lucent.laymark.core.report.ReportCodec;
import cx.mia.lucent.laymark.core.report.SelectionReport;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import cx.mia.lucent.laymark.core.stats.Comparison;
import cx.mia.lucent.laymark.core.stats.Drift;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import cx.mia.lucent.laymark.runner.materialize.ModsDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole experiment: many arms, many launches, one report.
 *
 * <p>Where {@code BenchmarkRun} is one launch, this is the loop around it — materialise an arm,
 * verify it, launch, collect, repeat, then compare. The instance is restored to how it was found
 * whatever happens, because a benchmark that leaves someone's modpack rearranged is not a tool
 * anyone runs twice.
 */
public final class ExperimentRun {

    private ExperimentRun() {}

    /** One arm's numbers for one scenario, kept flat so comparisons can pair across arms. */
    private record Measured(String armId, int sequence, String scenarioId, double scoredMillis) {}

    public static SelectionReport execute(
            ModrinthInstance instance,
            RunPlan plan,
            List<Arm> runs,
            java.util.Set<String> participants,
            Path outputDirectory,
            Path sceneRoot,
            Duration timeoutPerRun,
            RunControl control,
            ExperimentListener listener)
            throws IOException {

        ModsDirectory mods = new ModsDirectory(instance.gameDirectory());
        InstanceState initial = mods.read();
        Boolean sparkProfiler = SparkConfig.disableBackgroundProfiler(instance.gameDirectory());
        long startedAt = System.nanoTime();

        List<Measured> measured = new ArrayList<>();
        List<Comparison.Run> baselineRuns = new ArrayList<>();
        String[] scenarioListRevision = {null};
        Map<String, Map.Entry<String, cx.mia.lucent.laymark.core.harness.PresetReadback>>
                stimulusReference = new LinkedHashMap<>();
        // One round for now: the greedy driver that makes several is slice 9, and until it exists
        // the honest total is the arms actually scheduled.
        listener.scheduleBuilt(new ExperimentListener.Slate(runs, 1, 1, runs.size()));

        try {
            for (int sequence = 0; sequence < runs.size(); sequence++) {
                Arm arm = runs.get(sequence);
                if (control.pauseRequested()) {
                    listener.stateChanged("paused");
                }
                if (!control.awaitClearance()) {
                    // Stopped at a boundary: everything measured so far still becomes a report.
                    listener.stateChanged("stopping");
                    break;
                }
                listener.stateChanged("running");
                listener.runStarted(sequence, arm);
                System.out.printf("%n=== run %d/%d: %s%n", sequence + 1, runs.size(), arm.id());

                mods.apply(Materialization.plan(mods.read(), participants, arm.enabled()));
                // Immediately before launching, not after planning. A rename that silently failed
                // leaves a folder that boots perfectly and runs the wrong stack.
                Materialization.verify(mods.read(), arm.enabled(), initial);

                Path armOutput =
                        outputDirectory
                                .resolve("runs")
                                .resolve(String.format("%03d-%s", sequence, arm.id()));
                // Per-arm output directory, carried on the plan itself. The harness writes its
                // result to plan.outputDirectory(), so a plan reused across arms would have every
                // run overwrite the last and leave only the final one archived.
                RunPlan armPlan =
                        new RunPlan(
                                plan.runId(),
                                plan.protocolVersion(),
                                plan.window(),
                                plan.scenarios(),
                                armOutput.toString());
                RunResult result;
                try {
                    result =
                            BenchmarkRun.execute(
                                    instance,
                                    armPlan,
                                    armOutput,
                                    sceneRoot,
                                    timeoutPerRun,
                                    control,
                                    listener);
                } catch (RuntimeException e) {
                    if (control.stopping()) {
                        // The stop killed the game mid-run; that is the stop working, not a defect.
                        listener.runFinished(sequence, arm, 0, true);
                        break;
                    }
                    throw e;
                }

                scenarioListRevision[0] = result.scenarioListRevision();

                if (!arm.scored()) {
                    System.out.println("  (acclimation, discarded)");
                    // Discarded is not unfinished: the schedule row has to turn green, or the
                    // window shows a run forever in flight.
                    listener.runFinished(sequence, arm, 0, false);
                    continue;
                }
                double total = 0;
                int counted = 0;
                for (ScenarioResult scenario : result.scenarios()) {
                    if (!scenario.measured()) {
                        continue;
                    }
                    SelectionRun.requireParity(stimulusReference, arm.id(), scenario);
                    // Warm pass only, for the same reason SelectionRun scores warm only: cold and
                    // warm are different populations, and pooling them inflates every interval.
                    if (scenario.pass() == cx.mia.lucent.laymark.core.result.Pass.COLD) {
                        continue;
                    }
                    // Scored on what the stop condition selected. A completion target produces a
                    // variable-length window, so its frame distribution is not comparable across
                    // arms -- time per unit of work is, which is the point of the target.
                    double scored = scored(scenario, plan);
                    total += scored;
                    counted++;
                    measured.add(new Measured(arm.id(), sequence, scenario.scenarioId(), scored));
                    if (arm.kind() == Arm.Kind.BASELINE) {
                        baselineRuns.add(new Comparison.Run(arm.id(), sequence, scored, true));
                    }
                }
                listener.runFinished(
                        sequence, arm, counted == 0 ? 0 : total / counted, counted == 0);
            }
        } finally {
            // Unconditional, including after a crash mid-schedule. Restoring to the recorded start
            // is the same operation whether the run finished or died.
            SparkConfig.restore(instance.gameDirectory(), sparkProfiler);
            mods.apply(Materialization.restore(mods.read(), initial));
            if (!mods.read().matches(initial)) {
                System.err.println("WARNING: the instance was not fully restored; check mods/");
            }
        }

        List<Drift.VoidWindow> voids = Drift.detect(baselineRuns);
        SelectionReport report =
                report(plan, runs, measured, baselineRuns, voids, startedAt);
        listener.roundCompleted(1, "baseline", report.comparisons(), java.util.List.of(), null);
        listener.finished(report);

        Files.createDirectories(outputDirectory);
        EnvironmentFile.write(outputDirectory, instance, scenarioListRevision[0]);
        Files.writeString(
                outputDirectory.resolve("experiment.json"),
                ReportCodec.write(report),
                StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve("report.md"), MarkdownReport.render(report), StandardCharsets.UTF_8);
        return report;
    }

    private static SelectionReport report(
            RunPlan plan,
            List<Arm> runs,
            List<Measured> measured,
            List<Comparison.Run> baselineRuns,
            List<Drift.VoidWindow> voids,
            long startedAt) {

        List<Comparison> comparisons = new ArrayList<>();
        Map<String, List<String>> perCandidate = new LinkedHashMap<>();

        for (Arm arm : runs) {
            if (arm.kind() != Arm.Kind.CANDIDATE || perCandidate.containsKey(arm.id())) {
                continue;
            }
            perCandidate.put(arm.id(), List.of());
            for (String scenarioId :
                    measured.stream().map(Measured::scenarioId).distinct().toList()) {

                List<Comparison.Run> baseline =
                        baselineRuns.stream()
                                .filter(run -> hasScenario(measured, run.sequence(), scenarioId))
                                .map(run -> withDriftFlag(run, voids))
                                .toList();
                List<Comparison.Run> candidate =
                        measured.stream()
                                .filter(m -> m.armId().equals(arm.id()))
                                .filter(m -> m.scenarioId().equals(scenarioId))
                                .map(m -> withDriftFlag(
                                        new Comparison.Run(m.armId(), m.sequence(), m.scoredMillis(), true),
                                        voids))
                                .toList();

                if (baseline.size() < 1 || candidate.size() < 2) {
                    continue; // reported as absent rather than as a number nobody can stand behind
                }
                comparisons.add(
                        Comparison.of(
                                arm.id(),
                                scenarioId,
                                baseline,
                                candidate,
                                Comparison.DEFAULT_FLOOR_PERCENT));
            }
        }

        return new SelectionReport(
                plan.runId(),
                System.getProperty("os.name") + " / " + Runtime.getRuntime().availableProcessors() + " cpus",
                "baseline arm",
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                List.of(),
                List.of(),
                comparisons,
                voids,
                List.of(),
                List.of(),
                Map.of(
                        "java", System.getProperty("java.version"),
                        "scenarios", String.valueOf(plan.scenarios().size())));
    }

    /**
     * The scored metric for one scenario, chosen by its stop condition.
     *
     * <p>A duration or frame target gives both arms the same window, so the frame distribution is
     * comparable. A chunk target does not: the faster stack finishes sooner, so the two arms
     * observe windows of different length and only cost-per-unit-of-work compares.
     */
    static double scored(ScenarioResult scenario, RunPlan plan) {
        // One extraction, shared with the mod's live stream: the number that arrives mid-arm and
        // the number read from the result file must be the same number.
        return cx.mia.lucent.laymark.core.result.Channels.of(scenario, plan).scoredMillis();
    }

    private static boolean hasScenario(List<Measured> measured, int sequence, String scenarioId) {
        return measured.stream()
                .anyMatch(m -> m.sequence() == sequence && m.scenarioId().equals(scenarioId));
    }

    /** A run inside a voided window is kept but marked incomparable, never silently dropped. */
    private static Comparison.Run withDriftFlag(Comparison.Run run, List<Drift.VoidWindow> voids) {
        return Drift.voided(run.sequence(), voids)
                ? new Comparison.Run(run.armId(), run.sequence(), run.scoredMillis(), false)
                : run;
    }
}
