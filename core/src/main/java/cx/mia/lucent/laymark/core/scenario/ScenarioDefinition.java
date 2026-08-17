package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.Pose;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import java.util.List;
import java.util.function.Function;

/**
 * One scenario as authored, before defaults are filled and names are expanded.
 *
 * <p>Everything optional here has a default, and the defaults are chosen so that a scenario can be
 * written in three lines and still be valid. What it cannot do is be ambiguous: a field that is
 * present is honoured exactly, and a field that would change the meaning of a result — the phase,
 * the seed, the pose — is either stated or takes a value recorded in the resolved plan.
 *
 * @param id stable; results are keyed by it and {@code dependsOn} refers to it
 * @param dependsOn scenarios that must run first. Dependency implies world reuse, so a scenario
 *     that depends on another cannot run standalone.
 * @param phase which of the four measured phases this scenario is; decides which preconditions
 *     apply and, for two of them, which negative precondition can fail the run
 * @param presetName names an entry in the config's preset map; mutually exclusive with
 *     {@link #preset}
 * @param preset an inline settings bundle; mutually exclusive with {@link #presetName}
 * @param content scene geometry to place before measuring, in declaration order
 */
public record ScenarioDefinition(
        String id,
        List<String> dependsOn,
        Phase phase,
        StopCondition stop,
        Integer repetitions,
        String presetName,
        Preset preset,
        Pose pose,
        Long seed,
        Boolean generateStructures,
        List<ScenePlacement> content) {

    /** One repetition unless asked otherwise; the operator decides how much confidence to buy. */
    private static final int DEFAULT_REPETITIONS = 1;

    /** Fixed rather than random: two arms must generate the same terrain to be comparable. */
    private static final long DEFAULT_SEED = 1L;

    public ScenarioDefinition {
        if (id == null || id.isBlank()) {
            throw new PlanException("a scenario has no id");
        }
        if (presetName != null && preset != null) {
            // Silently preferring one would make the other's presence a lie, and the loser would
            // be whichever the implementation happened to check second.
            throw new PlanException(
                    "scenario " + id + " sets both 'presetName' and an inline 'preset'; pick one");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * Fills defaults and expands the preset reference.
     *
     * @param presets resolves a preset name; called only when the scenario named one
     */
    public ScenarioSpec resolve(Function<String, Preset> presets) {
        Preset effective =
                preset != null
                        ? preset
                        : presetName != null ? presets.apply(presetName) : Preset.defaults();

        return new ScenarioSpec(
                id,
                dependsOn,
                stop == null ? defaultStop() : stop,
                repetitions == null ? DEFAULT_REPETITIONS : repetitions,
                effective,
                pose == null ? defaultPose() : pose,
                seed == null ? DEFAULT_SEED : seed,
                phase == null ? Phase.RESIDENT_RENDER : phase,
                generateStructures != null && generateStructures,
                content);
    }

    /**
     * A duration for phases that run until told to stop, and a completion target for the one that
     * has an intrinsic end.
     *
     * <p>Defaulting rather than requiring it, because the right stop condition follows from the
     * phase closely enough that stating it every time would be noise — but the resolved plan
     * records the choice, so nothing is left implicit in the archive.
     */
    private StopCondition defaultStop() {
        return new StopCondition.FixedDuration(30_000);
    }

    /** High enough to be clear of terrain at any elevation vanilla generates, looking down. */
    private static Pose defaultPose() {
        return Pose.lookingDown(0.5, 200, 0.5);
    }

    /** The shortest valid scenario: an id and nothing else. */
    public static ScenarioDefinition of(String id) {
        return new ScenarioDefinition(
                id, List.of(), null, null, null, null, null, null, null, null, List.of());
    }
}
