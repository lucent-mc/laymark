package cx.mia.lucent.laymark.core.harness;

import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.ScenarioSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which scenario's world each scenario runs in, and when that world may be deleted.
 *
 * <p>{@code dependsOn} means world reuse: a scenario that depends on another measures in the world
 * that other one generated. So a save cannot be discarded when its scenario finishes — it has to
 * survive until every scenario downstream of it has run, or the dependency was for nothing.
 *
 * <p>Counted rather than reference-tracked, because the count is known before anything launches:
 * the execution order is fixed, so the number of repetitions that will use a world is arithmetic.
 * A lease released by a scenario that failed still counts, since the world is no more useful to the
 * next scenario for having been left behind.
 */
final class WorldLeases {

    private final Map<String, String> ownerByScenario;
    private final Map<String, Integer> remaining;

    private WorldLeases(Map<String, String> ownerByScenario, Map<String, Integer> remaining) {
        this.ownerByScenario = ownerByScenario;
        this.remaining = remaining;
    }

    /**
     * @throws PlanException if a scenario depends on more than one other, which does not name a
     *     single world to run in
     */
    static WorldLeases of(List<ScenarioSpec> order) {
        Map<String, ScenarioSpec> byId = new LinkedHashMap<>();
        order.forEach(scenario -> byId.put(scenario.id(), scenario));

        Map<String, String> owners = new LinkedHashMap<>();
        for (ScenarioSpec scenario : order) {
            owners.put(scenario.id(), owner(scenario, byId));
        }

        requireMatchingRepetitions(order, owners, byId);

        // One world per owner per repetition, used once by each scenario in that chain. Keying on
        // the owner alone would leave every repetition but the last undeleted, since the count
        // would not reach zero until the final one released.
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (ScenarioSpec scenario : order) {
            String owner = owners.get(scenario.id());
            for (int repetition = 1; repetition <= scenario.repetitions(); repetition++) {
                remaining.merge(key(owner, repetition), 1, Integer::sum);
            }
        }
        return new WorldLeases(owners, remaining);
    }

    private static String key(String owner, int repetition) {
        return owner + "#" + repetition;
    }

    /**
     * Follows {@code dependsOn} to the scenario that generates the world.
     *
     * <p>A chain, not a tree. Two dependencies would name two worlds and no rule says which one a
     * scenario runs in — and silently picking the first would produce a run measuring against
     * terrain the config never described.
     */
    private static String owner(ScenarioSpec scenario, Map<String, ScenarioSpec> byId) {
        ScenarioSpec at = scenario;
        while (!at.dependsOn().isEmpty()) {
            if (at.dependsOn().size() > 1) {
                throw new PlanException(
                        "scenario " + at.id() + " depends on " + at.dependsOn()
                                + "; a dependency means running in that scenario's world, and two"
                                + " of them name two worlds");
            }
            at = byId.get(at.dependsOn().getFirst());
        }
        return at.id();
    }

    /**
     * A dependent scenario pairs repetition for repetition with the world's owner.
     *
     * <p>Repetition 2 of a scenario runs in repetition 2 of the world it depends on, so the counts
     * have to agree. Anything else silently pairs some repetitions with a world generated for a
     * different one, or generates worlds nothing ever measures.
     */
    private static void requireMatchingRepetitions(
            List<ScenarioSpec> order, Map<String, String> owners, Map<String, ScenarioSpec> byId) {
        for (ScenarioSpec scenario : order) {
            String owner = owners.get(scenario.id());
            int ownerRepetitions = byId.get(owner).repetitions();
            if (!owner.equals(scenario.id()) && scenario.repetitions() != ownerRepetitions) {
                throw new PlanException(
                        "scenario " + scenario.id() + " runs " + scenario.repetitions()
                                + " repetitions in " + owner + "'s world, which is generated "
                                + ownerRepetitions + " times; they have to match");
            }
        }
    }

    /** The scenario whose world this one measures in — itself, when it generates its own. */
    String ownerOf(String scenarioId) {
        return ownerByScenario.get(scenarioId);
    }

    /** @return whether that was the last use of that repetition's world, so the save can be deleted */
    boolean release(String scenarioId, int repetition) {
        return remaining.merge(key(ownerByScenario.get(scenarioId), repetition), -1, Integer::sum)
                <= 0;
    }
}
