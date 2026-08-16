package cx.mia.lucent.laymark.core.plan;

import java.util.List;

/**
 * One entry in the {@code scenarios[]} array, fully resolved.
 *
 * <p>A resolved plan contains expanded values, never references to mutable named presets, so a
 * historical result stays interpretable after the config that produced it has changed.
 *
 * @param id stable identifier; {@code dependsOn} refers to it and results are keyed by it
 * @param dependsOn scenarios that must run first. Dependency implies world reuse: a scenario
 *     that reuses another's world cannot run standalone, so a subset must be dependency-closed.
 * @param stopCondition how the scenario ends, and therefore its scored metric
 * @param repetitions how many times the scenario repeats within a single arm run. Distinct from
 *     the schedule template, which governs how arm runs are ordered.
 */
public record ScenarioSpec(
        String id, List<String> dependsOn, StopCondition stopCondition, int repetitions) {

    public ScenarioSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("scenario id must not be blank");
        }
        if (stopCondition == null) {
            throw new IllegalArgumentException("scenario " + id + " needs a stop condition");
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException(
                    "scenario " + id + " needs at least one repetition, got " + repetitions);
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        if (dependsOn.contains(id)) {
            throw new IllegalArgumentException("scenario " + id + " depends on itself");
        }
    }

    public static ScenarioSpec of(String id, StopCondition stopCondition) {
        return new ScenarioSpec(id, List.of(), stopCondition, 1);
    }
}
