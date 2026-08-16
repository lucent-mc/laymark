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
        if (protocolVersion < 1) {
            // Not an equality test against the current constant: an archived plan from an older
            // protocol must stay readable for auditing. This catches the absent field, which
            // deserializes to 0 and would otherwise fail only at the handshake, after the launch.
            throw new PlanException(
                    "run " + runId + " has no protocol version, got " + protocolVersion);
        }
        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new PlanException("run " + runId + " has no output directory");
        }
        scenarios = List.copyOf(scenarios);
        // Resolved means resolvable. A plan is archived with the results and launched from, so
        // leaving the graph unchecked until someone calls executionOrder() makes validation
        // optional on the one type whose job is to be already valid.
        ScenarioOrder.resolve(scenarios);
    }

    public static RunPlan of(String runId, String outputDirectory, List<ScenarioSpec> scenarios) {
        return new RunPlan(runId, Laymark.PROTOCOL_VERSION, scenarios, outputDirectory);
    }

    /**
     * The deterministic execution order. Cannot fail: the graph was resolved at construction.
     */
    public List<ScenarioSpec> executionOrder() {
        return ScenarioOrder.resolve(scenarios);
    }
}
