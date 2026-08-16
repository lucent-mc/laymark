package cx.mia.lucent.laymark.core.plan;

import cx.mia.lucent.laymark.core.Laymark;
import java.util.List;

/**
 * A fully resolved plan for one launch.
 *
 * <p>Written by the runner, read by the harness. It is a <strong>file on disk</strong> in
 * {@code config/laymark/} rather than something sent over the socket: documents on disk, events
 * on the wire. It has to be archived with the results for auditability regardless, and a file is
 * inspectable when diagnosing a run.
 *
 * @param runId unique per launch; every disposable save embeds it, so collisions are impossible
 * @param protocolVersion exact-matched at handshake, independent of the product version
 * @param scenarios in declaration order; call {@link #executionOrder()} for dependency order
 * @param outputDirectory where results are written, outside the instance
 */
public record RunPlan(
        String runId, int protocolVersion, List<ScenarioSpec> scenarios, String outputDirectory) {

    public RunPlan {
        if (runId == null || runId.isBlank()) {
            throw new PlanException("run id must not be blank");
        }
        if (scenarios == null || scenarios.isEmpty()) {
            throw new PlanException("run " + runId + " has no scenarios");
        }
        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new PlanException("run " + runId + " has no output directory");
        }
        scenarios = List.copyOf(scenarios);
    }

    public static RunPlan of(String runId, String outputDirectory, List<ScenarioSpec> scenarios) {
        return new RunPlan(runId, Laymark.PROTOCOL_VERSION, scenarios, outputDirectory);
    }

    /** Validates the dependency graph and returns the deterministic execution order. */
    public List<ScenarioSpec> executionOrder() {
        return ScenarioOrder.resolve(scenarios);
    }
}
