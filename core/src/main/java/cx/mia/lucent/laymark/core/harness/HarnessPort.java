package cx.mia.lucent.laymark.core.harness;

import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import java.time.Duration;
import java.util.List;

/**
 * Everything the run sequence needs the game to do, expressed as semantic operations.
 *
 * <p>No Minecraft types cross this interface, and that is the point rather than a formality: it
 * lets the whole sequence — ordering, barriers, failure semantics, what invalidates a scenario —
 * be tested against an in-memory implementation in milliseconds. The alternative is discovering
 * an ordering bug by launching a game, which costs minutes per attempt and can only be done on a
 * machine with a GPU.
 *
 * <p>Implementations live in {@code minecraft-common}. Each operation is expected to be
 * <strong>synchronous and settled</strong> when it returns: {@link #applyPreset} returns after the
 * renderer has converged, not after the setter returned. A method that returns early would push
 * the barrier problem back into the sequence, which is exactly where it must not be.
 */
public interface HarnessPort {

    /**
     * Applies every setting and blocks until the game has settled on them.
     *
     * <p>Must apply unconditionally rather than diffing. {@code OptionInstance.set} skips its
     * update callback when the value is unchanged, so re-applying a preset for the second
     * scenario in a launch is otherwise a no-op that silently leaves the first scenario's
     * overrides live.
     */
    void applyPreset(Preset preset);

    /**
     * Reads back what the game <em>actually</em> has, which is not necessarily what was asked for.
     *
     * <p>Separate from {@link #applyPreset} because the difference is the whole point: an invalid
     * value is silently replaced by the option's default, a fullscreen request can be declined,
     * and a mod can revert a setting after it was applied.
     *
     * @param requested needed because not every setting has an effective accessor — VSync has
     *     none at all — and those are echoed back as asked rather than invented
     */
    PresetReadback readPreset(Preset requested);

    /** Creates a fresh disposable save and enters it. */
    void createWorld(WorldSpec spec);

    /**
     * Blocks until the world is genuinely ready to measure.
     *
     * @return what the barrier waited on and for how long, so two arms can be compared on the
     *     state they started from rather than assumed to have matched
     * @throws HarnessException if the barrier does not pass within the timeout. Timing out fails
     *     the run rather than proceeding: measuring an unbuilt world produces plausible numbers,
     *     and the runs that time out are the slow ones, so the error would correlate with the
     *     thing being compared.
     */
    BarrierReport awaitReady(Duration timeout);

    /** Places the player and suppresses input. */
    void position(Pose pose);

    /**
     * Whether the region around the pose has never been generated.
     *
     * <p>The negative precondition for {@link cx.mia.lucent.laymark.core.Phase#UNGENERATED_TRAVERSAL}.
     * Measuring generation destroys the thing being measured, so a repetition that finds its
     * target already generated has to fail rather than proceed — it would complete normally and
     * report flatteringly good numbers.
     */
    boolean targetIsUngenerated(Pose pose);

    /**
     * Whether the client currently holds no built sections for the region around the pose.
     *
     * <p>The negative precondition for {@link cx.mia.lucent.laymark.core.Phase#GENERATED_STREAMING}.
     * Deliberately not a claim about disk: after generation the region files sit in the OS page
     * cache and a mod cannot portably evict them. This is about the client's meshes.
     */
    boolean targetHasNoBuiltSections(Pose pose);

    /** Places scene geometry. Runs before any barrier, since it changes what there is to build. */
    void placeContent(List<ScenePlacement> content);

    /**
     * Records every channel for the given duration and returns the raw series.
     *
     * <p>All channels, always. Deciding per scenario which ones to open would save nothing worth
     * having -- they are field reads on hot paths that already exist -- and would guarantee that
     * the one run nobody thought to instrument is the one that turns out to matter.
     *
     * <p>Takes the whole stop condition rather than a duration because two of the three kinds end
     * on the game making progress, which only the implementation can observe.
     *
     * @throws HarnessException if the target is not reached within the condition's timeout
     */
    Measurement capture(StopCondition stop);

    /**
     * Saves and leaves the world, releasing its lock.
     *
     * <p>Must tolerate being called when no world is open. It runs in the cleanup path of a
     * repetition that may have failed before, during or after {@link #createWorld}, and a cleanup
     * step that throws on the uninteresting case buries the failure that caused it.
     */
    void closeWorld();

    /** Deletes a disposable save and its region files. Silent when there is nothing to delete. */
    void deleteWorld(String levelId);
}
