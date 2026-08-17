package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A benchmark configuration as a human wrote it.
 *
 * <p>Distinct from {@link RunPlan}, and the distinction is the point. This is a living document:
 * it names presets so they can be shared, leaves fields out so defaults apply, and gets edited
 * between runs. A plan is the opposite — every value expanded, nothing named, archived beside the
 * results it produced. A result that pointed at a preset name would change meaning the next time
 * someone edited the config, which makes historical comparison impossible.
 *
 * <p>{@link #resolve} is the one-way door between them.
 *
 * <p>Laymark supplies no scenarios of its own here. What to measure is the operator's decision;
 * this type only says what a scenario is allowed to declare.
 *
 * @param version schema version of this document, checked on read
 * @param presets reusable settings bundles, referenced by name from a scenario
 * @param scenarios in declaration order
 */
public record ScenarioConfig(
        int version, Map<String, Preset> presets, List<ScenarioDefinition> scenarios) {

    /** Bumped only when an existing document would be read wrongly rather than merely partially. */
    public static final int SCHEMA_VERSION = 1;

    public ScenarioConfig {
        if (version != SCHEMA_VERSION) {
            throw new PlanException(
                    "config schema version " + version + " is not supported; this build reads "
                            + SCHEMA_VERSION);
        }
        presets = presets == null ? Map.of() : Map.copyOf(presets);
        if (scenarios == null || scenarios.isEmpty()) {
            throw new PlanException("config declares no scenarios");
        }
        scenarios = List.copyOf(scenarios);

        List<String> ids = scenarios.stream().map(ScenarioDefinition::id).toList();
        for (String id : ids) {
            if (ids.indexOf(id) != ids.lastIndexOf(id)) {
                // Duplicates would silently shadow one another and produce a plan with fewer
                // scenarios than the document appears to declare.
                throw new PlanException("scenario id '" + id + "' is declared more than once");
            }
        }
    }

    /**
     * Expands this document into a plan for one launch.
     *
     * <p>Every named preset is inlined, every omitted field is filled, and the dependency graph is
     * validated — after which the result is a plan and can no longer be traced back to the
     * document that produced it, which is why the runner archives both.
     *
     * @throws PlanException if a scenario names a preset that does not exist, or the resulting
     *     plan is not valid
     */
    public RunPlan resolve(String runId, String outputDirectory) {
        List<ScenarioSpec> resolved = new ArrayList<>(scenarios.size());
        for (ScenarioDefinition definition : scenarios) {
            resolved.add(definition.resolve(this::preset));
        }
        return RunPlan.of(runId, outputDirectory, resolved);
    }

    /**
     * @throws PlanException naming what is available, because the alternative is an operator
     *     guessing at a typo in a file they cannot see the parsed form of
     */
    private Preset preset(String name) {
        Preset preset = presets.get(name);
        if (preset == null) {
            throw new PlanException(
                    "no preset named '" + name + "'; this config defines " + presets.keySet());
        }
        return preset;
    }

    /** A config with one scenario and no named presets, for tests and for a minimal invocation. */
    public static ScenarioConfig of(ScenarioDefinition... scenarios) {
        return new ScenarioConfig(SCHEMA_VERSION, new LinkedHashMap<>(), List.of(scenarios));
    }
}
