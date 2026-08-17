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
import cx.mia.lucent.laymark.core.select.BandGate;
import cx.mia.lucent.laymark.core.select.Bundle;
import cx.mia.lucent.laymark.core.select.DependencyGraph;
import cx.mia.lucent.laymark.core.select.Selection;
import cx.mia.lucent.laymark.core.stats.Comparison;
import cx.mia.lucent.laymark.core.stats.Drift;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
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
import java.util.Set;
import java.util.TreeSet;

/**
 * Greedy forward selection, run for real: rounds of arms, one promotion per round.
 *
 * <p>Where {@code ExperimentRun} runs one fixed schedule and compares, this is the loop the spec
 * calls the selection driver (§8.4): bundle the candidates against the current baseline, run a
 * round, rank what qualified, promote the single best into the baseline, and run the remaining
 * candidates against the stack that now includes the winner. The answer to "is B worth adding" is
 * a different question once A is in, which is why every round re-measures instead of reusing.
 *
 * <p>Stops when a round promotes nothing — every remaining candidate regressed or was blocked —
 * or when the pool is empty. Nothing is ever eliminated: a candidate that lost a round is
 * re-measured in the next, because its bundle may have shrunk or its benefit may only exist in
 * combination.
 */
public final class SelectionRun {

    private SelectionRun() {}

    /** The channels a candidate card summarises; null where the machine could not measure one. */
    private record Metrics(Double mspt, Double fps, Double msPerChunk) {}

    private record Measured(
            String armId, int sequence, String scenarioId, double scoredMillis, Metrics metrics) {}

    /**
     * @param floor what loads in every arm before any promotion
     * @param pool the candidate files, in the order the operator chose them
     * @param requires direct requirements between installed files, from the jars' own manifests
     */
    public static SelectionReport execute(
            ModrinthInstance instance,
            RunPlan plan,
            Set<String> floor,
            List<String> pool,
            Map<String, Set<String>> requires,
            List<cx.mia.lucent.laymark.core.select.Branching.Conflict> conflicts,
            Schedule schedule,
            Path outputDirectory,
            Path sceneRoot,
            Duration timeoutPerRun,
            RunControl control,
            ExperimentListener listener)
            throws IOException {

        DependencyGraph graph =
                DependencyGraph.from(requires, DependencyGraph.Provenance.JAR_METADATA);
        Selection selection = new Selection(graph, floor);

        // Conflicts split a selection into branches, and branch exploration is not built yet.
        // Said before anything launches, because the single greedy path this run follows drops a
        // conflicted candidate the moment its rival is promoted -- an operator has to know the
        // other stack exists and was not measured.
        var clusters =
                cx.mia.lucent.laymark.core.select.Branching.clusters(
                        conflicts.stream()
                                .filter(c -> pool.contains(c.a()) && pool.contains(c.b()))
                                .toList());
        if (!clusters.isEmpty()) {
            System.out.printf(
                    "conflicts among candidates: %s -- this run follows the single greedy path;"
                            + " a promoted candidate's rivals are dropped, not measured%n",
                    clusters);
        }

        Set<String> blocked = selection.unmeasurable(pool);
        if (!blocked.isEmpty()) {
            // The off-arm cannot exist: something in the baseline requires the candidate, so there
            // is nothing to compare against and running anyway answers a different question.
            throw new LaunchException(
                    "candidate(s) " + blocked + " are required by baseline mods, so the arm"
                            + " without them cannot be built");
        }

        // Participants are fixed for the whole experiment from the widest bundles -- round 1's.
        // Later rounds' bundles only shrink, and a stable participant set is what keeps mods/
        // meaning one thing for the entire run.
        Set<String> participants = new TreeSet<>(floor);
        for (Bundle bundle : selection.bundlesFor(pool)) {
            participants.addAll(bundle.members());
        }

        ModsDirectory mods = new ModsDirectory(instance.gameDirectory());
        InstanceState initial = mods.read();
        Boolean sparkProfiler = SparkConfig.disableBackgroundProfiler(instance.gameDirectory());
        long startedAt = System.nanoTime();

        // The machine's remembered wobble widens every interval it applies to, and this run's
        // baselines feed it for the next run. Widen-only, so staleness costs caution, never a
        // confident wrong answer.
        cx.mia.lucent.laymark.core.stats.MachineProfile profile = MachineProfiles.load();

        int projectedArms = projectArms(schedule, pool.size());
        int totalRounds = pool.size();

        List<String> remaining = new ArrayList<>(pool);
        // Stimulus parity is checked across the WHOLE experiment, not per round: a round-3
        // candidate is compared, through the promoted stack, against numbers from round 1.
        Map<String, Map.Entry<String, cx.mia.lucent.laymark.core.harness.PresetReadback>>
                stimulusReference = new LinkedHashMap<>();
        List<Comparison> allComparisons = new ArrayList<>();
        List<Comparison.Run> allBaselineRuns = new ArrayList<>();
        List<SelectionReport.Round> roundHistory = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        List<Measured> originalBaseline = null;
        String baselineLabel = "baseline";
        int sequence = 0;
        boolean acclimated = false;
        boolean stopped = false;

        try {
            for (int round = 1; !remaining.isEmpty() && !stopped; round++) {
                Set<String> guaranteed = selection.guaranteed();
                List<Bundle> bundles = selection.bundlesFor(remaining);

                Arm baseline = new Arm("baseline", Arm.Kind.BASELINE, guaranteed);
                Arm acclimation = new Arm("acclimation", Arm.Kind.ACCLIMATION, guaranteed);
                List<Arm> candidates = new ArrayList<>();
                for (Bundle bundle : bundles) {
                    Set<String> enabled = new TreeSet<>(guaranteed);
                    enabled.addAll(bundle.members());
                    candidates.add(new Arm(bundle.candidate(), Arm.Kind.CANDIDATE, enabled));
                }

                List<Arm> arms = schedule.expand(candidates, baseline, acclimation, acclimated);
                acclimated = true;
                listener.scheduleBuilt(
                        new ExperimentListener.Slate(arms, round, totalRounds, projectedArms));
                System.out.printf(
                        "%n=== round %d/%d: %d arm(s) against %s%n",
                        round, totalRounds, arms.size(), baselineLabel);

                List<Measured> measured = new ArrayList<>();
                List<Comparison.Run> baselineRuns = new ArrayList<>();
                for (Arm arm : arms) {
                    if (control.pauseRequested()) {
                        listener.stateChanged("paused");
                    }
                    if (!control.awaitClearance()) {
                        listener.stateChanged("stopping");
                        stopped = true;
                        break;
                    }
                    listener.stateChanged("running");
                    listener.runStarted(sequence, arm);
                    System.out.printf(
                            "%n=== arm %d/%d: %s%n", sequence + 1, projectedArms, arm.id());

                    mods.apply(Materialization.plan(mods.read(), participants, arm.enabled()));
                    Materialization.verify(mods.read(), arm.enabled(), initial);

                    Path armOutput =
                            outputDirectory.resolve(String.format("%03d-%s", sequence, arm.id()));
                    RunPlan armPlan =
                            new RunPlan(
                                    plan.runId(),
                                    plan.protocolVersion(),
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
                            listener.runFinished(sequence, arm, 0, true);
                            stopped = true;
                            break;
                        }
                        throw e;
                    }

                    if (!arm.scored()) {
                        System.out.println("  (acclimation, discarded)");
                        listener.runFinished(sequence, arm, 0, false);
                        sequence++;
                        continue;
                    }
                    double total = 0;
                    int counted = 0;
                    for (ScenarioResult scenario : result.scenarios()) {
                        if (!scenario.measured()) {
                            continue;
                        }
                        requireParity(stimulusReference, arm.id(), scenario);
                        // Scored on the warm pass only. Cold and warm are different populations --
                        // one measures a fresh JVM, the other the steady state -- and pooling them
                        // charges every candidate with a JVM-warmth difference that is not its
                        // own. The cold pass stays in the result as data.
                        if (scenario.pass() == cx.mia.lucent.laymark.core.result.Pass.COLD) {
                            continue;
                        }
                        double scored = ExperimentRun.scored(scenario, plan);
                        total += scored;
                        counted++;
                        measured.add(
                                new Measured(
                                        arm.id(),
                                        sequence,
                                        scenario.scenarioId(),
                                        scored,
                                        metricsOf(scenario)));
                        if (arm.kind() == Arm.Kind.BASELINE) {
                            baselineRuns.add(
                                    new Comparison.Run(arm.id(), sequence, scored, true));
                        }
                    }
                    listener.runFinished(
                            sequence, arm, counted == 0 ? 0 : total / counted, counted == 0);
                    sequence++;
                }
                if (stopped) {
                    break;
                }

                List<Drift.VoidWindow> voids = Drift.detect(baselineRuns);
                allBaselineRuns.addAll(baselineRuns);

                // This round's baseline spread per scenario is the next comparison's floor
                // evidence: fold it in before comparing, so even round 1 benefits from what its
                // own drift checks observed.
                for (String scenarioId :
                        measured.stream().map(Measured::scenarioId).distinct().toList()) {
                    Double spread = baselineSpreadPercent(measured, scenarioId);
                    if (spread != null) {
                        profile = profile.observe(scenarioId, spread);
                    }
                }
                MachineProfiles.store(profile);

                Map<String, List<Comparison>> byCandidate =
                        compare(bundles, measured, baselineRuns, voids, profile);
                byCandidate.values().forEach(allComparisons::addAll);

                // Score against the ORIGINAL baseline too, from round 2 on: the current round's
                // comparison says what the candidate adds to the grown stack, and only the
                // original says what the whole combination bought.
                if (originalBaseline == null) {
                    originalBaseline =
                            measured.stream().filter(m -> m.armId().equals("baseline")).toList();
                }
                Map<String, Double> vsOriginal =
                        round == 1
                                ? Map.of()
                                : scoreAgainst(bundles, measured, originalBaseline, voids);

                BandGate gate = new BandGate(byCandidate);
                Selection.Ranking ranking =
                        bundle ->
                                byCandidate.getOrDefault(bundle.candidate(), List.of()).isEmpty()
                                        ? Double.NEGATIVE_INFINITY
                                        : BandGate.weightedScore(
                                                byCandidate.get(bundle.candidate()), Map.of());
                List<Selection.Outcome> outcomes = selection.round(remaining, gate, ranking);

                String promoted =
                        outcomes.stream()
                                .filter(o -> o.verdict() == Selection.Verdict.PROMOTED)
                                .map(o -> o.bundle().candidate())
                                .findFirst()
                                .orElse(null);

                List<ExperimentListener.CandidateScore> scores =
                        summarise(outcomes, byCandidate, measured, baselineRuns, vsOriginal);
                List<Comparison> roundComparisons =
                        byCandidate.values().stream().flatMap(List::stream).toList();
                listener.roundCompleted(round, baselineLabel, roundComparisons, scores, promoted);
                roundHistory.add(historyOf(round, outcomes, byCandidate));

                for (var score : scores) {
                    System.out.printf("  %s%n", score.describe());
                }
                if (promoted == null) {
                    System.out.println(
                            "no candidate qualified this round; the selection is finished");
                    break;
                }
                System.out.printf("promoted: %s%n", promoted);
                stack.add(promoted);
                remaining.remove(promoted);
                baselineLabel = baselineLabel + " + " + promoted;

                // A rival of anything now guaranteed cannot be loaded alongside it, so its arm
                // cannot exist. Dropped loudly: the branch where the rival won was not measured.
                Set<String> guaranteedNow = selection.guaranteed();
                for (var conflict : conflicts) {
                    String rival =
                            guaranteedNow.contains(conflict.a()) && remaining.contains(conflict.b())
                                    ? conflict.b()
                                    : guaranteedNow.contains(conflict.b())
                                                    && remaining.contains(conflict.a())
                                            ? conflict.a()
                                            : null;
                    if (rival != null) {
                        remaining.remove(rival);
                        System.out.printf(
                                "dropped %s: incompatible with the promoted stack; the branch"
                                        + " where it won was not measured%n",
                                rival);
                    }
                }
            }
        } finally {
            SparkConfig.restore(instance.gameDirectory(), sparkProfiler);
            mods.apply(Materialization.restore(mods.read(), initial));
            if (!mods.read().matches(initial)) {
                System.err.println("WARNING: the instance was not fully restored; check mods/");
            }
        }

        SelectionReport report =
                new SelectionReport(
                        plan.runId(),
                        System.getProperty("os.name")
                                + " / "
                                + Runtime.getRuntime().availableProcessors()
                                + " cpus",
                        "selection from " + pool.size() + " candidate(s)",
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                        stack,
                        roundHistory,
                        allComparisons,
                        Drift.detect(allBaselineRuns),
                        List.of(),
                        Map.of(
                                "java", System.getProperty("java.version"),
                                "scenarios", String.valueOf(plan.scenarios().size())));
        listener.finished(report);

        Files.createDirectories(outputDirectory);
        Files.writeString(
                outputDirectory.resolve("report.json"),
                ReportCodec.write(report),
                StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve("report.md"),
                MarkdownReport.render(report),
                StandardCharsets.UTF_8);
        return report;
    }

    /**
     * The cross-arm gate: this scenario's stimulus must match the first arm that ran it.
     *
     * <p>Hard, and checked at collection time so the failure names the arm while the arm is still
     * the story. Per-arm verification catches drift from the request; only this catches two arms
     * that drifted differently — the spec's §9 parity gate.
     */
    static void requireParity(
            Map<String, Map.Entry<String, cx.mia.lucent.laymark.core.harness.PresetReadback>>
                    reference,
            String armId,
            ScenarioResult scenario) {
        if (scenario.readback() == null) {
            return;
        }
        var existing = reference.get(scenario.scenarioId());
        if (existing == null) {
            reference.put(scenario.scenarioId(), Map.entry(armId, scenario.readback()));
            return;
        }
        List<String> mismatches =
                cx.mia.lucent.laymark.core.experiment.Parity.compare(
                        scenario.scenarioId(),
                        existing.getKey(),
                        existing.getValue(),
                        armId,
                        scenario.readback());
        if (!mismatches.isEmpty()) {
            throw new LaunchException(
                    "stimulus parity failed -- the arms were not shown the same work: "
                            + String.join("; ", mismatches));
        }
    }

    /** What the whole selection costs in launches, so progress and the ETA span every round. */
    private static int projectArms(Schedule schedule, int candidates) {
        int total = 0;
        Arm baseline = new Arm("b", Arm.Kind.BASELINE, Set.of());
        Arm acclimation = new Arm("a", Arm.Kind.ACCLIMATION, Set.of());
        for (int inRound = candidates; inRound >= 1; inRound--) {
            List<Arm> dummies = new ArrayList<>();
            for (int i = 0; i < inRound; i++) {
                dummies.add(new Arm("c" + i, Arm.Kind.CANDIDATE, Set.of()));
            }
            total += schedule.expand(dummies, baseline, acclimation, inRound != candidates).size();
        }
        return total;
    }

    private static Metrics metricsOf(ScenarioResult scenario) {
        var measurement = scenario.segments().get(scenario.segments().size() - 1).measurement();
        var spark = measurement.spark();
        return new Metrics(
                spark == null ? null : spark.millisPerTickMean(),
                measurement.frameStatistics().meanFramesPerSecond(),
                measurement.millisPerChunkReceived());
    }

    /** Relative spread of this round's baseline runs on one scenario, as a percent; null below n=2. */
    private static Double baselineSpreadPercent(List<Measured> measured, String scenarioId) {
        double[] values =
                measured.stream()
                        .filter(m -> m.armId().equals("baseline"))
                        .filter(m -> m.scenarioId().equals(scenarioId))
                        .mapToDouble(Measured::scoredMillis)
                        .toArray();
        if (values.length < 2) {
            return null;
        }
        double mean = java.util.Arrays.stream(values).average().orElseThrow();
        double variance =
                java.util.Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum()
                        / (values.length - 1);
        return Math.sqrt(variance) / mean * 100.0;
    }

    private static Map<String, List<Comparison>> compare(
            List<Bundle> bundles,
            List<Measured> measured,
            List<Comparison.Run> baselineRuns,
            List<Drift.VoidWindow> voids,
            cx.mia.lucent.laymark.core.stats.MachineProfile profile) {

        Map<String, List<Comparison>> byCandidate = new LinkedHashMap<>();
        for (Bundle bundle : bundles) {
            List<Comparison> comparisons = new ArrayList<>();
            for (String scenarioId :
                    measured.stream().map(Measured::scenarioId).distinct().toList()) {
                List<Comparison.Run> baseline =
                        baselineRuns.stream().map(run -> flagged(run, voids)).toList();
                List<Comparison.Run> candidate =
                        measured.stream()
                                .filter(m -> m.armId().equals(bundle.candidate()))
                                .filter(m -> m.scenarioId().equals(scenarioId))
                                .map(
                                        m ->
                                                flagged(
                                                        new Comparison.Run(
                                                                m.armId(),
                                                                m.sequence(),
                                                                m.scoredMillis(),
                                                                true),
                                                        voids))
                                .toList();
                if (baseline.isEmpty() || candidate.size() < 2) {
                    continue; // absent rather than a number nobody can stand behind
                }
                comparisons.add(
                        Comparison.of(
                                bundle.candidate(),
                                scenarioId,
                                baseline,
                                candidate,
                                Comparison.DEFAULT_FLOOR_PERCENT,
                                profile));
            }
            if (!comparisons.isEmpty()) {
                byCandidate.put(bundle.candidate(), comparisons);
            }
        }
        return byCandidate;
    }

    /** Weighted score of each candidate against the run's first baseline, for the vs-original row. */
    private static Map<String, Double> scoreAgainst(
            List<Bundle> bundles,
            List<Measured> measured,
            List<Measured> originalBaseline,
            List<Drift.VoidWindow> voids) {

        List<Comparison.Run> original =
                originalBaseline.stream()
                        .map(
                                m ->
                                        new Comparison.Run(
                                                m.armId(), m.sequence(), m.scoredMillis(), true))
                        .toList();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Bundle bundle : bundles) {
            List<Comparison> comparisons = new ArrayList<>();
            for (String scenarioId :
                    originalBaseline.stream().map(Measured::scenarioId).distinct().toList()) {
                List<Comparison.Run> baseline =
                        original.stream()
                                .filter(run -> run.armId().equals("baseline"))
                                .toList();
                List<Comparison.Run> candidate =
                        measured.stream()
                                .filter(m -> m.armId().equals(bundle.candidate()))
                                .filter(m -> m.scenarioId().equals(scenarioId))
                                .map(
                                        m ->
                                                flagged(
                                                        new Comparison.Run(
                                                                m.armId(),
                                                                m.sequence(),
                                                                m.scoredMillis(),
                                                                true),
                                                        voids))
                                .toList();
                if (baseline.isEmpty() || candidate.size() < 2) {
                    continue;
                }
                comparisons.add(
                        Comparison.of(
                                bundle.candidate(),
                                scenarioId,
                                baseline,
                                candidate,
                                Comparison.DEFAULT_FLOOR_PERCENT));
            }
            if (!comparisons.isEmpty()) {
                scores.put(
                        bundle.candidate(), BandGate.weightedScore(comparisons, Map.of()));
            }
        }
        return scores;
    }

    private static List<ExperimentListener.CandidateScore> summarise(
            List<Selection.Outcome> outcomes,
            Map<String, List<Comparison>> byCandidate,
            List<Measured> measured,
            List<Comparison.Run> baselineRuns,
            Map<String, Double> vsOriginal) {

        Metrics baseline =
                meanMetrics(
                        measured.stream()
                                .filter(
                                        m ->
                                                baselineRuns.stream()
                                                        .anyMatch(
                                                                run ->
                                                                        run.sequence()
                                                                                == m.sequence()))
                                .toList());

        List<ExperimentListener.CandidateScore> scores = new ArrayList<>();
        for (Selection.Outcome outcome : outcomes) {
            String id = outcome.bundle().candidate();
            List<Comparison> comparisons = byCandidate.getOrDefault(id, List.of());
            Metrics own =
                    meanMetrics(
                            measured.stream().filter(m -> m.armId().equals(id)).toList());
            scores.add(
                    new ExperimentListener.CandidateScore(
                            id,
                            comparisons.isEmpty()
                                    ? 0
                                    : BandGate.weightedScore(comparisons, Map.of()),
                            delta(own.mspt(), baseline.mspt()),
                            delta(own.fps(), baseline.fps()),
                            delta(own.msPerChunk(), baseline.msPerChunk()),
                            vsOriginal.get(id),
                            outcome.verdict().toString().toLowerCase(java.util.Locale.ROOT),
                            outcome.detail()));
        }
        return scores;
    }

    private static Metrics meanMetrics(List<Measured> runs) {
        return new Metrics(
                mean(runs, m -> m.metrics().mspt()),
                mean(runs, m -> m.metrics().fps()),
                mean(runs, m -> m.metrics().msPerChunk()));
    }

    private static Double mean(
            List<Measured> runs, java.util.function.Function<Measured, Double> metric) {
        var values = runs.stream().map(metric).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty()
                ? null
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static Double delta(Double candidate, Double baseline) {
        return candidate == null || baseline == null ? null : candidate - baseline;
    }

    private static SelectionReport.Round historyOf(
            int round,
            List<Selection.Outcome> outcomes,
            Map<String, List<Comparison>> byCandidate) {
        List<SelectionReport.Round.Entry> entries = new ArrayList<>();
        for (Selection.Outcome outcome : outcomes) {
            List<Comparison> comparisons =
                    byCandidate.getOrDefault(outcome.bundle().candidate(), List.of());
            entries.add(
                    new SelectionReport.Round.Entry(
                            outcome.bundle().candidate(),
                            comparisons.isEmpty()
                                    ? 0
                                    : BandGate.weightedScore(comparisons, Map.of()),
                            outcome.verdict().toString(),
                            List.copyOf(outcome.bundle().members()),
                            outcome.detail()));
        }
        return new SelectionReport.Round(round, entries);
    }

    private static Comparison.Run flagged(Comparison.Run run, List<Drift.VoidWindow> voids) {
        return Drift.voided(run.sequence(), voids)
                ? new Comparison.Run(run.armId(), run.sequence(), run.scoredMillis(), false)
                : run;
    }
}
