package cx.mia.lucent.laymark.core.harness;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.result.RunResult;
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
        // Settings first. Render distance decides how much work world load itself does, so
        // applying it afterwards would measure a load the preset never governed.
        port.applyPreset(scenario.preset());
        port.createWorld(world);

        events.accept(
                new Frame.PhaseEntered(scenario.id(), Phase.SPAWN_GENERATION, System.nanoTime()));
        port.awaitReady(READY_TIMEOUT);
        port.position(scenario.pose());

        // After applying and loading, not before: the world load runs arbitrary mod code, and a
        // mod that reverts a setting does it there rather than during the setter call.
        PresetReadback readback = port.readPreset(scenario.preset());
        List<String> flags = new ArrayList<>(readback.deviationsFrom(scenario.preset()));

        events.accept(
                new Frame.PhaseEntered(scenario.id(), Phase.RESIDENT_RENDER, System.nanoTime()));
        List<FrameSample> samples = port.capture(captureWindow(scenario));
        if (samples.isEmpty()) {
            throw new HarnessException("scenario " + scenario.id() + " captured no frames");
        }

        return ScenarioResult.completed(
                scenario.id(),
                repetition,
                readback,
                flags,
                samples,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private static Duration captureWindow(ScenarioSpec scenario) {
        if (scenario.stopCondition() instanceof StopCondition.FixedDuration fixed) {
            return fixed.duration();
        }
        // Completion-targeted scenarios need a channel that reports progress, which arrives with
        // the measurement channels. Failing the scenario says so; silently capturing for some
        // default window would produce a number that looks like an answer to a different question.
        throw new HarnessException(
                "scenario "
                        + scenario.id()
                        + " uses a completion target, which this version cannot yet measure");
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
