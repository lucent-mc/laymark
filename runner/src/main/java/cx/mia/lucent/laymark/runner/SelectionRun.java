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

    /** The arm id every lap measures its candidates against. */
    private static final String BASELINE_ARM = "baseline";

    /**
     * The baseline arms' runs for one scenario, in schedule order.
     *
     * <p><strong>Per scenario, always.</strong> Two scenarios measure different work in the same
     * unit -- generating a chunk costs milliseconds and so does loading one, an order of
     * magnitude apart -- so a baseline pool holding both compares a loading time against a
     * generation time and reports the ratio between two unrelated quantities as an improvement.
     * Derived from the same {@code measured} list the candidate side is filtered from, so the two
     * sides of a comparison cannot come from different bookkeeping.
     */
    private static List<Comparison.Run> baselineRunsFor(
            List<Measured> measured, String scenarioId) {
        return measured.stream()
                .filter(m -> m.armId().equals(BASELINE_ARM))
                .filter(m -> m.scenarioId().equals(scenarioId))
                .map(m -> new Comparison.Run(m.armId(), m.sequence(), m.scoredMillis(), true))
                .toList();
    }

    /** Every scenario that produced a measurement this lap, in first-seen order. */
    private static List<String> scenarioIds(List<Measured> measured) {
        return measured.stream().map(Measured::scenarioId).distinct().toList();
    }

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
            Map<String, String> modIdByFile,
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
        List<String> trustFlags = new ArrayList<>();
        // Candidate arms that never produced a result, by file: reported rather than fatal.
        Map<String, Integer> failedArms = new LinkedHashMap<>();
        List<String> stack = new ArrayList<>();
        String scenarioListRevision = null;
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
                        "%n=== lap %d/%d: %d arm(s) against %s%n",
                        round, totalRounds, arms.size(), baselineLabel);

                List<Measured> measured = new ArrayList<>();
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
                            outputDirectory
                                    .resolve("runs")
                                    .resolve(String.format("%03d-%s", sequence, arm.id()));
                    RunPlan armPlan =
                            new RunPlan(
                                    plan.runId(),
                                    plan.protocolVersion(),
                                    plan.window(),
                                    plan.scenarios(),
                                    armOutput.toString());
                    // Candidate arms stream: each warm capture the game finishes becomes a
                    // provisional Measured immediately, and the preliminary aggregate updates
                    // per scenario instead of once per arm. Transient by design -- the arm's
                    // result file re-derives the same numbers authoritatively below.
                    List<Measured> streamed =
                            java.util.Collections.synchronizedList(new ArrayList<>());
                    int armSequence = sequence;
                    ExperimentListener tap =
                            arm.kind() != Arm.Kind.CANDIDATE
                                    ? listener
                                    : new ExperimentListener() {
                                        @Override
                                        public void scenarioStarted(
                                                String scenarioId, int repetition) {
                                            listener.scenarioStarted(scenarioId, repetition);
                                        }

                                        @Override
                                        public void scenarioMeasured(
                                                ExperimentListener.LiveSample sample) {
                                            if (!sample.warm()) {
                                                return; // cold is context, not score
                                            }
                                            streamed.add(
                                                    new Measured(
                                                            arm.id(),
                                                            armSequence,
                                                            sample.scenarioId(),
                                                            sample.scoredMillis(),
                                                            new Metrics(
                                                                    sample.mspt(),
                                                                    sample.fps(),
                                                                    sample.msPerChunk())));
                                            List<Measured> combined = new ArrayList<>(measured);
                                            combined.addAll(streamed);
                                            ExperimentListener.Preliminary live =
                                                    preliminary(arm.id(), combined);
                                            if (live != null) {
                                                listener.preliminaryScore(live);
                                            }
                                        }
                                    };
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
                                        tap);
                    } catch (RuntimeException e) {
                        if (control.stopping()) {
                            listener.runFinished(sequence, arm, 0, true);
                            stopped = true;
                            break;
                        }
                        // A candidate arm that hangs or dies costs its own comparison, not the
                        // experiment. With a hundred arms scheduled, one bad mod must not throw
                        // away every measurement taken before it -- and "this mod cannot even
                        // complete a run" is itself a finding worth reaching the report.
                        //
                        // The baseline and the acclimation arm are not optional in the same way:
                        // every candidate in the lap is measured against the baseline, so losing
                        // it leaves nothing for the rest of the lap to mean.
                        if (arm.kind() != Arm.Kind.CANDIDATE) {
                            throw e;
                        }
                        System.out.printf("  arm failed, continuing: %s%n", e.getMessage());
                        trustFlags.add(
                                "arm " + sequence + " (" + arm.id() + ") failed: " + e.getMessage());
                        failedArms.merge(arm.id(), 1, Integer::sum);
                        listener.runFinished(sequence, arm, 0, true);
                        sequence++;
                        continue;
                    }

                    scenarioListRevision = result.scenarioListRevision();
                    requireInventory(
                            arm,
                            result,
                            participants,
                            modIdByFile,
                            instance.gameDirectory().resolve("mods"));
                    collectTrustFlags(trustFlags, sequence, arm, result);

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
                        var channels =
                                cx.mia.lucent.laymark.core.result.Channels.of(scenario, plan);
                        double scored = channels.scoredMillis();
                        total += scored;
                        counted++;
                        measured.add(
                                new Measured(
                                        arm.id(),
                                        sequence,
                                        scenario.scenarioId(),
                                        scored,
                                        new Metrics(
                                                channels.mspt(),
                                                channels.fps(),
                                                channels.msPerChunk())));
                    }
                    listener.runFinished(
                            sequence, arm, counted == 0 ? 0 : total / counted, counted == 0);
                    if (arm.kind() == Arm.Kind.CANDIDATE) {
                        ExperimentListener.Preliminary preliminary =
                                preliminary(arm.id(), measured);
                        if (preliminary != null) {
                            listener.preliminaryScore(preliminary);
                        }
                    }
                    sequence++;
                }
                if (stopped) {
                    break;
                }

                // Drift is judged within a scenario, for the same reason comparisons are: a
                // baseline series that mixed two scenarios would read every alternation between
                // them as the machine moving. The windows are then applied to every scenario --
                // an arm that ran while the machine wandered is suspect whatever it measured.
                List<Drift.VoidWindow> voids = new ArrayList<>();
                for (String scenarioId : scenarioIds(measured)) {
                    List<Comparison.Run> scenarioBaselines = baselineRunsFor(measured, scenarioId);
                    voids.addAll(Drift.detect(scenarioBaselines));
                    allBaselineRuns.addAll(scenarioBaselines);
                }

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
                        compare(bundles, measured, voids, profile);
                byCandidate.values().forEach(allComparisons::addAll);

                // Score against the ORIGINAL baseline too, from round 2 on: the current round's
                // comparison says what the candidate adds to the grown stack, and only the
                // original says what the whole combination bought.
                if (originalBaseline == null) {
                    originalBaseline =
                            measured.stream().filter(m -> m.armId().equals(BASELINE_ARM)).toList();
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
                        summarise(outcomes, byCandidate, measured, vsOriginal);
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

        if (!failedArms.isEmpty()) {
            // Said once, plainly, where the operator is already looking: a candidate whose arms
            // all died has no comparison in this report, and that absence should not read as
            // "measured and unremarkable".
            System.out.printf(
                    "%n%d candidate arm(s) failed and were skipped: %s%n",
                    failedArms.values().stream().mapToInt(Integer::intValue).sum(), failedArms);
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
                        trustFlags,
                        Map.of(
                                "java", System.getProperty("java.version"),
                                "scenarios", String.valueOf(plan.scenarios().size())));
        listener.finished(report);

        Files.createDirectories(outputDirectory);
        EnvironmentFile.write(outputDirectory, instance, scenarioListRevision);
        // experiment.json is the §5.5 name: the whole experiment's conclusions, beside runs/ and
        // environment.json. report.md is the human rendering of the same data.
        Files.writeString(
                outputDirectory.resolve("experiment.json"),
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

    /**
     * The in-process check that materialisation produced the arm: FML's own inventory.
     *
     * <p>File renames can succeed and still lie — a jar cached elsewhere, a loader that scanned
     * before the rename. The loader's account of what actually loaded is the only evidence from
     * inside the process, so an enabled participant's mod id must be in it and a withheld one's
     * must not.
     */
    private static void requireInventory(
            Arm arm,
            RunResult result,
            Set<String> participants,
            Map<String, String> modIdByFile,
            java.nio.file.Path modsDir) {
        if (result.loadedMods().isEmpty()) {
            return; // an older mod build; nothing to verify against
        }
        for (String file : arm.enabled()) {
            String modId = modIdByFile.get(file);
            if (modId != null && !result.loadedMods().contains(modId)) {
                // Consulted only on the failure path: a jar that ships a loader plugin (an FML
                // language loader or transformation service — Configured Defaults is one) is
                // honoured before mod construction and never becomes a mod-list entry, so its
                // absence there is FML working as designed, not a materialisation failure.
                // Unverifiable is not the same as missing.
                if (cx.mia.lucent.laymark.runner.select.JarProbe.loaderPlugin(
                        modsDir.resolve(file))) {
                    continue;
                }
                throw new LaunchException(
                        "arm " + arm.id() + " enabled " + file + " but the game did not load "
                                + modId + "; materialisation did not produce this arm");
            }
        }
        for (String file : participants) {
            if (arm.enabled().contains(file)) {
                continue;
            }
            String modId = modIdByFile.get(file);
            if (modId != null && result.loadedMods().contains(modId)) {
                throw new LaunchException(
                        "arm " + arm.id() + " withheld " + file + " but the game loaded " + modId
                                + " anyway; materialisation did not produce this arm");
            }
        }
    }

    /** Every annotation any arm produced, prefixed so a reader can find the arm it belongs to. */
    private static void collectTrustFlags(
            List<String> into, int sequence, Arm arm, RunResult result) {
        String prefix = String.format("%03d-%s", sequence, arm.id());
        for (String flag : result.flags()) {
            into.add(prefix + ": " + flag);
        }
        for (ScenarioResult scenario : result.scenarios()) {
            for (String flag : scenario.flags()) {
                into.add(prefix + " " + scenario.scenarioId() + "#" + scenario.repetition() + ": " + flag);
            }
            if (scenario.outcome() == ScenarioResult.Outcome.FAILED) {
                into.add(
                        prefix + " " + scenario.scenarioId() + "#" + scenario.repetition()
                                + " FAILED: " + scenario.failureReason());
            }
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

    /**
     * The candidate's running aggregate versus this round's baseline arms so far — the scored
     * percent per scenario plus every metric channel — from all of the candidate's measured arms,
     * however many have run. Null before any baseline has been measured. Positive percent is
     * faster, matching how improvements read everywhere else.
     */
    private static ExperimentListener.Preliminary preliminary(
            String armId, List<Measured> measured) {
        List<Measured> own = measured.stream().filter(m -> m.armId().equals(armId)).toList();
        List<Measured> baseline =
                measured.stream().filter(m -> m.armId().equals(BASELINE_ARM)).toList();

        Map<String, ExperimentListener.PreliminaryScenario> scenarios = new LinkedHashMap<>();
        for (String scenarioId :
                measured.stream().map(Measured::scenarioId).distinct().sorted().toList()) {
            List<Measured> baselineScenario =
                    baseline.stream().filter(m -> m.scenarioId().equals(scenarioId)).toList();
            List<Measured> ownScenario =
                    own.stream().filter(m -> m.scenarioId().equals(scenarioId)).toList();
            if (baselineScenario.isEmpty() || ownScenario.isEmpty()) {
                continue;
            }
            double baselineMean =
                    baselineScenario.stream()
                            .mapToDouble(Measured::scoredMillis)
                            .average()
                            .orElseThrow();
            double ownMean =
                    ownScenario.stream()
                            .mapToDouble(Measured::scoredMillis)
                            .average()
                            .orElseThrow();
            Metrics ownMetrics = meanMetrics(ownScenario);
            Metrics baselineMetrics = meanMetrics(baselineScenario);
            scenarios.put(
                    scenarioId,
                    new ExperimentListener.PreliminaryScenario(
                            (baselineMean - ownMean) / baselineMean * 100.0,
                            delta(ownMetrics.mspt(), baselineMetrics.mspt()),
                            delta(ownMetrics.fps(), baselineMetrics.fps()),
                            delta(ownMetrics.msPerChunk(), baselineMetrics.msPerChunk()),
                            (int) ownScenario.stream().map(Measured::sequence).distinct().count(),
                            (int)
                                    baselineScenario.stream()
                                            .map(Measured::sequence)
                                            .distinct()
                                            .count()));
        }
        if (scenarios.isEmpty()) {
            return null;
        }

        Metrics ownMetrics = meanMetrics(own);
        Metrics baselineMetrics = meanMetrics(baseline);
        return new ExperimentListener.Preliminary(
                armId,
                scenarios.values().stream()
                        .mapToDouble(ExperimentListener.PreliminaryScenario::improvementPercent)
                        .average()
                        .orElseThrow(),
                delta(ownMetrics.mspt(), baselineMetrics.mspt()),
                delta(ownMetrics.fps(), baselineMetrics.fps()),
                delta(ownMetrics.msPerChunk(), baselineMetrics.msPerChunk()),
                scenarios);
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
            List<Drift.VoidWindow> voids,
            cx.mia.lucent.laymark.core.stats.MachineProfile profile) {

        Map<String, List<Comparison>> byCandidate = new LinkedHashMap<>();
        for (Bundle bundle : bundles) {
            List<Comparison> comparisons = new ArrayList<>();
            for (String scenarioId : scenarioIds(measured)) {
                List<Comparison.Run> baseline =
                        baselineRunsFor(measured, scenarioId).stream()
                                .map(run -> flagged(run, voids))
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

        Map<String, Double> scores = new LinkedHashMap<>();
        for (Bundle bundle : bundles) {
            List<Comparison> comparisons = new ArrayList<>();
            for (String scenarioId : scenarioIds(originalBaseline)) {
                List<Comparison.Run> baseline = baselineRunsFor(originalBaseline, scenarioId);
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
            Map<String, Double> vsOriginal) {

        List<Measured> baselineMeasured =
                measured.stream().filter(m -> m.armId().equals(BASELINE_ARM)).toList();
        Metrics baseline = meanMetrics(baselineMeasured);

        List<ExperimentListener.CandidateScore> scores = new ArrayList<>();
        for (Selection.Outcome outcome : outcomes) {
            String id = outcome.bundle().candidate();
            List<Comparison> comparisons = byCandidate.getOrDefault(id, List.of());
            List<Measured> ownMeasured =
                    measured.stream().filter(m -> m.armId().equals(id)).toList();
            Metrics own = meanMetrics(ownMeasured);

            // The same channels again, per scenario: the card's expandable sections show where
            // an aggregate delta actually came from.
            Map<String, ExperimentListener.ScenarioChannels> channels = new LinkedHashMap<>();
            for (String scenarioId :
                    ownMeasured.stream().map(Measured::scenarioId).distinct().sorted().toList()) {
                Metrics ownScenario =
                        meanMetrics(
                                ownMeasured.stream()
                                        .filter(m -> m.scenarioId().equals(scenarioId))
                                        .toList());
                Metrics baselineScenario =
                        meanMetrics(
                                baselineMeasured.stream()
                                        .filter(m -> m.scenarioId().equals(scenarioId))
                                        .toList());
                channels.put(
                        scenarioId,
                        new ExperimentListener.ScenarioChannels(
                                delta(ownScenario.mspt(), baselineScenario.mspt()),
                                delta(ownScenario.fps(), baselineScenario.fps()),
                                delta(
                                        ownScenario.msPerChunk(),
                                        baselineScenario.msPerChunk())));
            }

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
                            outcome.detail(),
                            channels));
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
