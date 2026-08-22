package cx.mia.lucent.laymark.core.plan;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
import java.util.List;

/**
 * One entry in the {@code scenarios[]} array, fully resolved.
 *
 * <p>A resolved plan contains expanded values, never references to mutable named presets, so a
 * historical result stays interpretable after the config that produced it has changed. That is why
 * the preset is embedded here in full rather than named: the config file it came from is a living
 * document, and a result that points at a name is a result whose meaning can change after the fact.
 *
 * @param id stable identifier; {@code dependsOn} refers to it and results are keyed by it
 * @param dependsOn scenarios that must run first. Dependency implies world reuse: a scenario
 *     that reuses another's world cannot run standalone, so a subset must be dependency-closed.
 * @param stopCondition how the scenario ends, and therefore its scored metric
 * @param repetitions how many times the scenario repeats within a single arm run. Distinct from
 *     the schedule template, which governs how arm runs are ordered.
 * @param weight operator relevance multiplier in both speed and memory objectives
 * @param preset the graphics settings to pin before the world loads
 * @param pose where the player is placed and what it looks at
 * @param seed the world seed; identical across arms, or the comparison is between landscapes
 * @param measure which phases this scenario captures, in order. Nothing is measured implicitly:
 *     a scenario that did not ask for spawn generation gets no spawn-generation number, even
 *     though the world still had to be created. Each named phase decides its own preconditions
 *     and, for two of them, a negative precondition that can fail the repetition.
 * @param content scene geometry placed before measuring, in declaration order
 */
public record ScenarioSpec(
        String id,
        List<String> dependsOn,
        StopCondition stopCondition,
        int repetitions,
        double weight,
        Preset preset,
        Pose pose,
        long seed,
        List<Phase> measure,
        boolean generateStructures,
        List<ScenePlacement> content) {

    public ScenarioSpec {
        if (id == null || id.isBlank()) {
            throw new PlanException("scenario id must not be blank");
        }
        if (stopCondition == null) {
            throw new PlanException("scenario " + id + " needs a stop condition");
        }
        if (repetitions < 1) {
            throw new PlanException(
                    "scenario " + id + " needs at least one repetition, got " + repetitions);
        }
        if (!Double.isFinite(weight) || weight < 0) {
            throw new PlanException(
                    "scenario " + id + " weight must be a finite non-negative number");
        }
        if (preset == null) {
            throw new PlanException("scenario " + id + " needs a preset");
        }
        if (pose == null) {
            throw new PlanException("scenario " + id + " needs a pose");
        }
        if (measure == null || measure.isEmpty()) {
            throw new PlanException("scenario " + id + " does not say what to measure");
        }
        measure = List.copyOf(measure);
        if (measure.stream().distinct().count() != measure.size()) {
            // Two segments of the same phase in one world would differ only by what the first did
            // to the second, which is a different experiment than the config appears to describe.
            throw new PlanException(
                    "scenario " + id + " measures the same phase twice: " + measure);
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        content = content == null ? List.of() : List.copyOf(content);
        if (dependsOn.contains(id)) {
            throw new PlanException("scenario " + id + " depends on itself");
        }
        if (measure.contains(Phase.RESIDENT_RENDER)
                && stopCondition.kind() == StopCondition.Kind.CHUNKS) {
            // Resident render's barrier waits for the world to be fully loaded before the capture
            // opens, so no chunk ever arrives during it. Left unchecked this burns a launch and
            // the whole timeout before failing -- verified the hard way at 60s a repetition.
            throw new PlanException(
                    "scenario " + id + " measures the resident render path, where the world is"
                            + " already loaded, so a chunk target can never be reached");
        }
        if (measure.contains(Phase.SPAWN_GENERATION) && !dependsOn.isEmpty()) {
            // Spawn generation measures world creation itself, so it cannot begin in a world some
            // earlier scenario already created. The config is asking for two incompatible things.
            throw new PlanException(
                    "scenario " + id + " measures spawn generation, so it cannot reuse a world");
        }
        boolean derivesChunkRadius =
                stopCondition.kind() == StopCondition.Kind.CHUNKS
                        || (measure.contains(Phase.GENERATED_STREAMING) && dependsOn.isEmpty());
        if (derivesChunkRadius && preset.pinnedViewDistance().isEmpty()) {
            // A chunk stop counts arrivals within the send radius, and Chunky's pre-generation
            // footprint covers it. Unpinned, that radius would be whatever the instance happens
            // to have -- two machines quietly doing different amounts of work under one config.
            throw new PlanException(
                    "scenario " + id + " judges completion by chunks around the player, so its"
                            + " settings must pin minecraft.renderDistance — the chunk target and"
                            + " the send radius are derived from it");
        }
    }

    /**
     * A scenario at the default preset and pose.
     *
     * <p>Defaults rather than absent values: a plan is resolved by definition, so there is no such
     * thing as a scenario without a preset — only one that did not choose.
     */
    public ScenarioSpec(
            String id, List<String> dependsOn, StopCondition stopCondition, int repetitions) {
        this(
                id,
                dependsOn,
                stopCondition,
                repetitions,
                1.0,
                Preset.empty(),
                DEFAULT_POSE,
                0L,
                List.of(Phase.RESIDENT_RENDER),
                false,
                List.of());
    }

    /** Source-compatible shape from before per-scenario relevance weights were configurable. */
    public ScenarioSpec(
            String id,
            List<String> dependsOn,
            StopCondition stopCondition,
            int repetitions,
            Preset preset,
            Pose pose,
            long seed,
            List<Phase> measure,
            boolean generateStructures,
            List<ScenePlacement> content) {
        this(
                id,
                dependsOn,
                stopCondition,
                repetitions,
                1.0,
                preset,
                pose,
                seed,
                measure,
                generateStructures,
                content);
    }

    /** High enough to be clear of terrain at any elevation vanilla generates. */
    private static final Pose DEFAULT_POSE = Pose.lookingDown(0.5, 200, 0.5);

    public static ScenarioSpec of(String id, StopCondition stopCondition) {
        return new ScenarioSpec(id, List.of(), stopCondition, 1);
    }
}
