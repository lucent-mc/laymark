package cx.mia.lucent.laymark.core.harness;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.RunResult;
import cx.mia.lucent.laymark.core.result.PhaseResult;
import cx.mia.lucent.laymark.core.result.ScenarioResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        List<ScenarioResult> results = new ArrayList<>();
        for (ScenarioSpec scenario : plan.executionOrder()) {
            for (int repetition = 1; repetition <= scenario.repetitions(); repetition++) {
                results.add(runRepetition(scenario, repetition));
            }
        }
        return new RunResult(plan.runId(), Laymark.PROTOCOL_VERSION, results, runFlags);
    }

    private ScenarioResult runRepetition(ScenarioSpec scenario, int repetition) {
        events.accept(new Frame.ScenarioStarted(scenario.id(), repetition));
        WorldSpec world =
                WorldSpec.forRepetition(plan.runId(), scenario.id(), repetition, scenario.seed());
        long startedAt = System.nanoTime();
        try {
            ScenarioResult result = measure(scenario, repetition, world, startedAt);
            events.accept(
                    new Frame.ScenarioFinished(
                            scenario.id(),
                            result.outcome() != ScenarioResult.Outcome.FAILED,
                            String.join("; ", result.flags())));
            return result;
        } catch (HarnessException e) {
            events.accept(new Frame.ScenarioFinished(scenario.id(), false, e.getMessage()));
            return ScenarioResult.failed(scenario.id(), repetition, e.getMessage());
        } finally {
            // Unconditional. A save left behind holds a lock that stops the next repetition from
            // creating its own, so a single failure would cascade into every later scenario.
            discard(world);
        }
    }

    private ScenarioResult measure(
            ScenarioSpec scenario, int repetition, WorldSpec world, long startedAt) {
        List<PhaseResult> segments = new ArrayList<>();

        // Settings first. Render distance decides how much work world load itself does, so
        // applying it afterwards would measure a load the preset never governed.
        port.applyPreset(scenario.preset());

        // Spawn generation is bracketed around world creation rather than started after it: the
        // cost mods target is the creation itself, and a window opened once the world exists has
        // already missed it. Its end is the barrier, not a target anyone configures.
        boolean measuringSpawn = scenario.measure().contains(Phase.SPAWN_GENERATION);
        long spawnStartedAt = System.nanoTime();
        if (measuringSpawn) {
            events.accept(
                    new Frame.PhaseEntered(scenario.id(), Phase.SPAWN_GENERATION, spawnStartedAt));
            port.beginCapture();
        }

        port.createWorld(world);
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
                    phase == Phase.UNGENERATED_TRAVERSAL
                            ? traverse(scenario, phaseStartedAt)
                            : residentAt(scenario, phase, phaseStartedAt);

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
        port.awaitStop(scenario.stopCondition());
        return port.endCapture();
    }

    /** The phases that measure a settled state, where positioning is setup rather than the event. */
    private Measurement residentAt(ScenarioSpec scenario, Phase phase, long phaseStartedAt) {
        port.position(scenario.pose());
        requireNegativePrecondition(scenario, phase);
        events.accept(new Frame.PhaseEntered(scenario.id(), phase, phaseStartedAt));
        return port.capture(scenario.stopCondition());
    }

    /**
     * Fails the repetition if the work being measured has already happened.
     *
     * <p>Two of the four phases have this hazard, and it is the most dangerous failure mode in the
     * whole harness: a traversal whose target was already generated, or a streaming phase whose
     * sections are already meshed, <strong>completes normally and reports flatteringly good
     * numbers</strong>. There is nothing in the output to distinguish it from a genuinely fast
     * stack, so it fails the repetition rather than flagging it.
     */
    private void requireNegativePrecondition(ScenarioSpec scenario, Phase phase) {
        switch (phase) {
            // Traversal checks its own, before the player moves -- see traverse().
            case UNGENERATED_TRAVERSAL -> {}
            case GENERATED_STREAMING -> {
                if (!port.targetHasNoBuiltSections(scenario.pose())) {
                    throw new HarnessException(
                            "scenario " + scenario.id()
                                    + " measures client streaming, but the target is already"
                                    + " meshed; the work it exists to time has already happened");
                }
            }
            // Spawn generation is guaranteed fresh by construction -- every repetition creates its
            // own save -- and resident render is the one phase where "already finished" is the
            // point rather than a hazard.
            case SPAWN_GENERATION, RESIDENT_RENDER -> {}
        }
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

    private void discard(WorldSpec world) {
        try {
            port.closeWorld();
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
