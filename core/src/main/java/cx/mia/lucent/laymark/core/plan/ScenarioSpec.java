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
 * @param preset the graphics settings to pin before the world loads
 * @param pose where the player is placed and what it looks at
 * @param seed the world seed; identical across arms, or the comparison is between landscapes
 * @param phase which of the four measured phases this is. Decides which preconditions are checked
 *     and, for two of them, which negative precondition can fail the repetition.
 * @param content scene geometry placed before measuring, in declaration order
 */
public record ScenarioSpec(
        String id,
        List<String> dependsOn,
        StopCondition stopCondition,
        int repetitions,
        Preset preset,
        Pose pose,
        long seed,
        Phase phase,
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
        if (preset == null) {
            throw new PlanException("scenario " + id + " needs a preset");
        }
        if (pose == null) {
            throw new PlanException("scenario " + id + " needs a pose");
        }
        if (phase == null) {
            throw new PlanException("scenario " + id + " needs a phase");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        content = content == null ? List.of() : List.copyOf(content);
        if (dependsOn.contains(id)) {
            throw new PlanException("scenario " + id + " depends on itself");
        }
        if (phase == Phase.SPAWN_GENERATION && !dependsOn.isEmpty()) {
            // Spawn generation measures world creation itself, so it cannot begin in a world some
            // earlier scenario already created. The config is asking for two incompatible things.
            throw new PlanException(
                    "scenario " + id + " measures spawn generation, so it cannot reuse a world");
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
                Preset.defaults(),
                DEFAULT_POSE,
                0L,
                Phase.RESIDENT_RENDER,
                false,
                List.of());
    }

    /** High enough to be clear of terrain at any elevation vanilla generates. */
    private static final Pose DEFAULT_POSE = Pose.lookingDown(0.5, 200, 0.5);

    public static ScenarioSpec of(String id, StopCondition stopCondition) {
        return new ScenarioSpec(id, List.of(), stopCondition, 1);
    }
}
