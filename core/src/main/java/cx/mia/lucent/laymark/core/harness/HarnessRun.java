package cx.mia.lucent.laymark.core.harness;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.Pass;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.PhaseResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Walks a plan, driving the game through a {@link HarnessPort} and collecting results.
 *
 * <p>This class holds the decisions that are expensive to get wrong and expensive to test through
 * a real game: what order things happen in, what invalidates a repetition, and what still has to
 * happen when one fails. Every one of those is exercised against an in-memory port in
 * milliseconds, which is the reason the port exists.
 *
 * <p>Runs on its own thread, never the client thread. Every operation here is written as a
 * blocking call, and the client thread is the one that has to keep rendering — a capture issued
 * from it would stall the very frames it is trying to measure, and world creation blocks it in a
 * nested render loop of its own. The port is responsible for marshalling each operation onto the
 * client thread and waiting for it. Not thread-safe beyond that: one run, one thread.
 */
public final class HarnessRun {

    /**
     * How long a world may take to become measurable.
     *
     * <p>Generous because it has to cover the worst honest case — a heavy modpack generating spawn
     * chunks on a cold instance — and a benchmark that gives up on a slow stack is a benchmark
     * that reports only fast ones.
     */
    public static final Duration READY_TIMEOUT = Duration.ofMinutes(5);

    private final RunPlan plan;
    private final HarnessPort port;
    private final Consumer<Frame> events;
    private final List<String> runFlags = new ArrayList<>();

    public HarnessRun(RunPlan plan, HarnessPort port, Consumer<Frame> events) {
        this.plan = plan;
        this.port = port;
        this.events = events;
    }

    /**
     * Runs every scenario and returns what they produced.
     *
     * <p>Does not throw for a failed scenario. A repetition that fails is recorded and the run
     * continues, because the alternative — abandoning the run — discards the repetitions that
     * already succeeded and biases whatever is left toward the arms that had no trouble.
     */
    public RunResult execute() {
        List<ScenarioSpec> order = plan.executionOrder();
        List<ScenarioResult> results = new ArrayList<>();

        // Two full traversals of the list in this one launch (§8.2): cold against a fresh JVM,
        // warm behind everything the cold pass compiled, grew and cached. Each pass has its own
        // worlds and its own failure bookkeeping — a warm dependent must not fail because the cold
        // copy of its dependency did, since it does not run in that world.
        for (Pass pass : Pass.values()) {
            WorldLeases leases = WorldLeases.of(order);
            Map<String, Set<Integer>> failedRepetitions = new HashMap<>();

            for (int position = 0; position < order.size(); position++) {
                ScenarioSpec scenario = order.get(position);
                for (int repetition = 1; repetition <= scenario.repetitions(); repetition++) {
                    // A dependency that failed leaves a world that is partly what the config
                    // describes and partly whatever the failure left behind. Measured on a real
                    // run: generation timed out, and the streaming scenario depending on it passed
                    // its pose-local preconditions and spent its capture half loading terrain,
                    // half generating the rest.
                    String failedDependency =
                            failedDependency(scenario, repetition, failedRepetitions);
                    ScenarioResult result;
                    if (failedDependency != null) {
                        String reason =
                                "dependency " + failedDependency + " did not complete, so the"
                                        + " world this scenario measures in is not the one its"
                                        + " config describes";
                        events.accept(new Frame.ScenarioStarted(scenario.id(), repetition));
                        events.accept(new Frame.ScenarioFinished(scenario.id(), false, reason));
                        result =
                                ScenarioResult.failed(
                                        scenario.id(), repetition, pass, position, reason);
                        // The lease is still released; the world is no more useful to anyone else.
                        if (leases.release(scenario.id(), repetition)) {
                            discard(
                                    WorldSpec.forRepetition(
                                            plan.runId(),
                                            leases.ownerOf(scenario.id()),
                                            repetition,
                                            scenario.seed(),
                                            pass));
                        }
                    } else {
                        result = runRepetition(scenario, repetition, pass, position, leases);
                    }
                    if (result.outcome() == ScenarioResult.Outcome.FAILED) {
                        failedRepetitions
                                .computeIfAbsent(scenario.id(), unused -> new HashSet<>())
                                .add(repetition);
                    }
                    results.add(result);
                }
            }
        }
        return new RunResult(
                plan.runId(),
                Laymark.PROTOCOL_VERSION,
                revisionOf(order),
                List.of(),
                results,
                runFlags);
    }

    /**
     * A fingerprint of the resolved scenario list, order included.
     *
     * <p>Array position is part of a scenario's identity — scenario 1 runs against a colder JVM
     * than scenario 5 — so results from different list revisions must never be pooled, and the
     * revision on the result is what makes that refusable later.
     */
    private static String revisionOf(List<ScenarioSpec> order) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (ScenarioSpec scenario : order) {
                digest.update(scenario.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /** The direct dependency whose same-numbered repetition failed, or null when all held. */
    private static String failedDependency(
            ScenarioSpec scenario, int repetition, Map<String, Set<Integer>> failed) {
        for (String dependency : scenario.dependsOn()) {
            if (failed.getOrDefault(dependency, Set.of()).contains(repetition)) {
                return dependency;
            }
        }
        return null;
    }

    private ScenarioResult runRepetition(
            ScenarioSpec scenario, int repetition, Pass pass, int position, WorldLeases leases) {

        events.accept(new Frame.ScenarioStarted(scenario.id(), repetition));
        String owner = leases.ownerOf(scenario.id());
        WorldSpec world =
                WorldSpec.forRepetition(plan.runId(), owner, repetition, scenario.seed(), pass);
        boolean owns = owner.equals(scenario.id());
        long startedAt = System.nanoTime();

        try {
            ScenarioResult result =
                    measure(scenario, repetition, pass, position, world, owns, startedAt);
            events.accept(
                    new Frame.ScenarioFinished(
                            scenario.id(),
                            result.outcome() != ScenarioResult.Outcome.FAILED,
                            String.join("; ", result.flags())));
            return result;
        } catch (HarnessException e) {
            events.accept(new Frame.ScenarioFinished(scenario.id(), false, e.getMessage()));
            return ScenarioResult.failed(scenario.id(), repetition, pass, position, e.getMessage());
        } finally {
            // The world always closes; whether it is deleted depends on who still needs it. A save
            // left open holds a lock that stops the next scenario from opening anything, so a
            // single failure would otherwise cascade through the rest of the run.
            close();
            if (leases.release(scenario.id(), repetition)) {
                discard(world);
            }
        }
    }

    private ScenarioResult measure(
            ScenarioSpec scenario,
            int repetition,
            Pass pass,
            int position,
            WorldSpec world,
            boolean createsWorld,
            long startedAt) {
        List<PhaseResult> segments = new ArrayList<>();

        // Settings first. Render distance decides how much work world load itself does, so
        // applying it afterwards would measure a load the preset never governed.
        port.applyPreset(scenario.preset());

        // Spawn generation is bracketed around world creation rather than started after it: the
        // cost mods target is the creation itself, and a window opened once the world exists has
        // already missed it. Its end is the barrier, not a target anyone configures.
        boolean measuringSpawn = scenario.measure().contains(Phase.SPAWN_GENERATION);
        if (measuringSpawn && !createsWorld) {
            // Spawn generation is world creation. A scenario reusing a world has none to measure,
            // and capturing the reopen instead would report a load as a generation.
            throw new HarnessException(
                    "scenario " + scenario.id() + " measures spawn generation but reuses "
                            + world.levelId() + ", which is already generated");
        }
        long spawnStartedAt = System.nanoTime();
        if (measuringSpawn) {
            events.accept(
                    new Frame.PhaseEntered(scenario.id(), Phase.SPAWN_GENERATION, spawnStartedAt));
            port.beginCapture();
        }

        if (createsWorld) {
            port.createWorld(world);
        } else {
            port.openWorld(world);
        }
        BarrierReport barrier = port.awaitReady(READY_TIMEOUT);
        port.pinGameRules();

        if (measuringSpawn) {
            Measurement spawn = port.endCapture();
            segments.add(
                    new PhaseResult(
                            Phase.SPAWN_GENERATION,
                            spawn,
                            throttleFlags(spawn),
                            Duration.ofNanos(System.nanoTime() - spawnStartedAt).toMillis()));
        }

        if (!scenario.content().isEmpty()) {
            // Before positioning and before the barrier is re-confirmed: placing geometry changes
            // what there is to build, so a barrier satisfied beforehand says nothing about after.
            port.placeContent(scenario.content());
        }

        // After applying and loading, not before: the world load runs arbitrary mod code, and a
        // mod that reverts a setting does it there rather than during the setter call.
        PresetReadback readback = port.readPreset(scenario.preset());
        requireSettingsHeld(scenario, readback, "after the world loaded");
        List<String> flags = new ArrayList<>();

        for (Phase phase : scenario.measure()) {
            if (phase == Phase.SPAWN_GENERATION) {
                continue; // already captured, around world creation
            }
            long phaseStartedAt = System.nanoTime();
            Measurement measurement =
                    switch (phase) {
                        case UNGENERATED_TRAVERSAL -> traverse(scenario, phaseStartedAt);
                        case GENERATED_STREAMING -> stream(scenario, phaseStartedAt);
                        default -> residentAt(scenario, phase, phaseStartedAt);
                    };

            if (!measurement.measured()) {
                throw new HarnessException(
                        "scenario " + scenario.id() + " captured no frames for " + phase);
            }
            // Re-read after the capture, not only before it. Preset verification is a runtime
            // invariant, because the mods being measured are exactly the population that rewrites
            // rendering settings -- a value reverted mid-capture produces a full set of samples
            // taken under settings nobody asked for.
            requireSettingsHeld(scenario, port.readPreset(scenario.preset()), "during " + phase);
            requireNotThrottled(scenario, phase, measurement);

            segments.add(
                    new PhaseResult(
                            phase,
                            measurement,
                            List.of(),
                            Duration.ofNanos(System.nanoTime() - phaseStartedAt).toMillis()));
        }

        return ScenarioResult.completed(
                scenario.id(),
                repetition,
                pass,
                position,
                readback,
                flags,
                segments,
                barrier,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    /**
     * Measures the traversal into ungenerated terrain.
     *
     * <p>The precondition is checked <strong>before the player moves</strong>, because moving is
     * what generates the target. Checking afterwards asks whether the thing just generated was
     * ungenerated, which it never is — the phase could not pass its own precondition.
     *
     * <p>The window opens, then the teleport happens, then the wait. That ordering is the phase:
     * the arrival is the measured event, so a capture that started after the player had already
     * settled would report the cost of standing still.
     */
    private Measurement traverse(ScenarioSpec scenario, long phaseStartedAt) {
        if (!port.targetIsUngenerated(scenario.pose())) {
            throw new HarnessException(
                    "scenario " + scenario.id()
                            + " measures generation, but its target was already generated;"
                            + " measuring it would report a cost that was already paid");
        }
        events.accept(
                new Frame.PhaseEntered(
                        scenario.id(), Phase.UNGENERATED_TRAVERSAL, phaseStartedAt));

        port.beginCapture();
        port.teleport(scenario.pose());
        port.awaitStop(scenario.stopCondition(), scenario.pose(), viewDistance(scenario));
        return port.endCapture();
    }

    /**
     * Measures streaming: the arrival is the event, so the capture brackets the teleport.
     *
     * <p>The same shape as {@link #traverse}, and it has to be — settling at the pose first would
     * stream and mesh the very chunks the capture exists to time, then fail its own negative
     * precondition. Both preconditions are checked <strong>before the player moves</strong>.
     *
     * <p>The positive precondition has two honest answers: terrain from a {@code dependsOn}
     * scenario that generated it, or terrain pre-generated here through Chunky, off the measured
     * path. A scenario with neither fails rather than quietly timing generation and calling it
     * streaming — the larger number under the wrong name.
     */
    private Measurement stream(ScenarioSpec scenario, long phaseStartedAt) {
        if (port.targetIsUngenerated(scenario.pose())) {
            if (!scenario.dependsOn().isEmpty()) {
                throw new HarnessException(
                        "scenario " + scenario.id() + " depends on " + scenario.dependsOn()
                                + " but its target has never been generated; the dependency did"
                                + " not do what the dependency exists to do");
            }
            String unavailable = port.pregenerationUnavailableReason();
            if (unavailable != null) {
                throw new HarnessException(
                        "scenario " + scenario.id() + " measures streaming from disk, but its"
                                + " target has never been generated; it needs dependsOn a scenario"
                                + " that generates it, or Chunky for pre-generation ("
                                + unavailable + ")");
            }
            port.pregenerate(
                    scenario.pose(), scenario.preset().renderDistance(), PREGENERATION_TIMEOUT);
        }
        if (!port.targetHasNoBuiltSections(scenario.pose())) {
            throw new HarnessException(
                    "scenario " + scenario.id() + " measures client streaming, but the target is"
                            + " already meshed; the work it exists to time has already happened");
        }

        events.accept(new Frame.PhaseEntered(scenario.id(), Phase.GENERATED_STREAMING, phaseStartedAt));
        port.beginCapture();
        port.teleport(scenario.pose());
        port.awaitStop(scenario.stopCondition(), scenario.pose(), viewDistance(scenario));
        return port.endCapture();
    }

    /**
     * A bound on broken pre-generation, not a budget for honest work — and generous for the same
     * reason as {@link #READY_TIMEOUT}: a benchmark that gives up on a slow stack reports only
     * fast ones.
     */
    private static final Duration PREGENERATION_TIMEOUT = Duration.ofMinutes(60);

    /**
     * The phases that measure a settled state, where positioning is setup rather than the event.
     *
     * <p>No precondition gate here, and none missing: traversal and streaming check their own
     * before the player moves, spawn generation is fresh by construction, and resident render is
     * the one phase where "already finished" is the point rather than a hazard.
     */
    private Measurement residentAt(ScenarioSpec scenario, Phase phase, long phaseStartedAt) {
        port.position(scenario.pose());
        events.accept(new Frame.PhaseEntered(scenario.id(), phase, phaseStartedAt));
        return port.capture(scenario.stopCondition(), scenario.pose(), viewDistance(scenario));
    }

    /**
     * The view distance handed to the port for chunk counting.
     *
     * <p>Zero when the preset does not pin one, which the plan validation only permits for stop
     * conditions that never count chunks — the port ignores the value on those paths. Chunky's
     * pre-generation footprint goes through the strict accessor instead, because there the radius
     * is the work.
     */
    private static int viewDistance(ScenarioSpec scenario) {
        return scenario.preset().pinnedViewDistance().orElse(0);
    }

    /**
     * Fails when a setting Laymark set has since drifted.
     *
     * <p>Hard, not a flag. This is the tier the spec fails closed on: anything Laymark can both set
     * and verify, that then changes, invalidates the run outright. Recording it and carrying on
     * would produce a comparison between two different configurations wearing one config's name.
     */
    private static void requireSettingsHeld(
            ScenarioSpec scenario, PresetReadback readback, String when) {
        List<String> deviations = readback.deviationsFrom(scenario.preset());
        if (!deviations.isEmpty()) {
            throw new HarnessException(
                    "scenario " + scenario.id() + ": settings drifted " + when + " -- "
                            + String.join("; ", deviations));
        }
    }

    /**
     * Fails when the framerate was held back at any point in a capture.
     *
     * <p>Also hard. A window that begins throttled is refused by the port; this catches the one
     * that starts clean and is capped partway through, which leaves a distribution with an
     * artificial ceiling in the middle of otherwise real samples.
     */
    private static void requireNotThrottled(
            ScenarioSpec scenario, Phase phase, Measurement measurement) {
        List<Throttle> throttles =
                measurement.throttlesObserved().stream()
                        .filter(throttle -> throttle != Throttle.NONE)
                        .toList();
        // Spawn generation is capped by vanilla whenever no level is loaded and a screen is up,
        // which is precisely what world creation is. Failing on it would fail every such capture.
        if (!throttles.isEmpty() && phase != Phase.SPAWN_GENERATION) {
            throw new HarnessException(
                    "scenario " + scenario.id() + ": framerate was throttled during " + phase
                            + " (" + throttles + ")");
        }
    }

    /**
     * Throttling seen during the window, as flags rather than a failure.
     *
     * <p>The port already fails a capture that was throttled throughout. This catches the subtler
     * case — a cap that engaged for part of it — where the samples are real but the distribution
     * has a ceiling in the middle of it, and only the reader can judge what that is worth.
     */
    private static List<String> throttleFlags(Measurement measurement) {
        List<String> flags =
                new ArrayList<>(
                        measurement.throttlesObserved().stream()
                                .filter(throttle -> throttle != Throttle.NONE)
                                .map(t -> "framerate was throttled during the capture: " + t)
                                .toList());

        if (measurement.serverWindowOverrunsCapture()) {
            // Spark's statistics are a rolling window, not a capture-scoped aggregate. A capture
            // shorter than that window reports server numbers that partly describe world creation
            // and the previous scenario's teardown.
            flags.add(
                    "server statistics cover a "
                            + measurement.spark().windowMillis()
                            + "ms window but the capture was only "
                            + measurement.captureMillis()
                            + "ms, so they include time from before it");
        }
        return flags;
    }

    private static StopCondition captureWindow(ScenarioSpec scenario) {
        return scenario.stopCondition();
    }

    private void close() {
        try {
            port.closeWorld();
        } catch (RuntimeException e) {
            runFlags.add("could not close the world: " + e.getMessage());
        }
    }

    private void discard(WorldSpec world) {
        try {
            port.deleteWorld(world.levelId());
        } catch (RuntimeException e) {
            // Cleanup failure must not replace the real outcome -- losing the exception that
            // explains a failed run costs far more than a leaked save. It is still recorded at run
            // level, because a save that would not delete usually means the next one will not
            // either, and the reader deserves to know the run was fighting the filesystem.
            runFlags.add("could not discard " + world.levelId() + ": " + e.getMessage());
        }
    }
}
