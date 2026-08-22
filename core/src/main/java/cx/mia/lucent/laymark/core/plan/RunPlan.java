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
 * @param scoreWeights relative importance of independently normalized speed and memory results
 * @param scenarios in declaration order; call {@link #executionOrder()} for dependency order
 * @param outputDirectory where results are written, outside the instance
 */
 /* @param window the run's window size — a stratum, one value for the whole launch */
public record RunPlan(
        String runId,
        int protocolVersion,
        WindowSize window,
        ScoreWeights scoreWeights,
        List<ScenarioSpec> scenarios,
        String outputDirectory) {

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
        window = window == null ? WindowSize.DEFAULT : window;
        scoreWeights = scoreWeights == null ? ScoreWeights.DEFAULT : scoreWeights;
        scenarios = List.copyOf(scenarios);
        if (scenarios.stream().noneMatch(scenario -> scenario.weight() > 0)) {
            throw new PlanException("at least one scenario must have a positive score weight");
        }
        // Resolved means resolvable. A plan is archived with the results and launched from, so
        // leaving the graph unchecked until someone calls executionOrder() makes validation
        // optional on the one type whose job is to be already valid.
        ScenarioOrder.resolve(scenarios);
    }

    public static RunPlan of(String runId, String outputDirectory, List<ScenarioSpec> scenarios) {
        return of(runId, outputDirectory, WindowSize.DEFAULT, scenarios);
    }

    public static RunPlan of(
            String runId, String outputDirectory, WindowSize window, List<ScenarioSpec> scenarios) {
        return of(runId, outputDirectory, window, ScoreWeights.DEFAULT, scenarios);
    }

    public static RunPlan of(
            String runId,
            String outputDirectory,
            WindowSize window,
            ScoreWeights scoreWeights,
            List<ScenarioSpec> scenarios) {
        return new RunPlan(
                runId, Laymark.PROTOCOL_VERSION, window, scoreWeights, scenarios, outputDirectory);
    }

    /** Source-compatible shape from before score weights were archived in the plan. */
    public RunPlan(
            String runId,
            int protocolVersion,
            WindowSize window,
            List<ScenarioSpec> scenarios,
            String outputDirectory) {
        this(runId, protocolVersion, window, ScoreWeights.DEFAULT, scenarios, outputDirectory);
    }

    /**
     * The deterministic execution order. Cannot fail: the graph was resolved at construction.
     */
    public List<ScenarioSpec> executionOrder() {
        return ScenarioOrder.resolve(scenarios);
    }

    /**
     * How long this plan is allowed to take, from every scenario's own ceiling.
     *
     * <p>A launch timeout shorter than the captures it contains kills the run part-way and reports
     * it as a hang — so it is derived rather than configured. Each scenario already carries a stop
     * condition with a wall-clock ceiling, which is the same question asked one level down; adding
     * them up and allowing for the launch is the whole calculation.
     *
     * <p>Generous by construction: it bounds a broken run, and every scenario stops on its own
     * target long before this matters. A plan that generates 4225 chunks twice is legitimately
     * allowed most of an hour.
     */
    public java.time.Duration timeout() {
        long millis = LAUNCH_ALLOWANCE.toMillis();
        for (ScenarioSpec scenario : scenarios) {
            millis += scenario.stopCondition().timeoutMillis() * Math.max(1, scenario.repetitions());
        }
        return java.time.Duration.ofMillis(millis);
    }

    /** World creation, mod loading and shutdown, none of which a stop condition covers. */
    private static final java.time.Duration LAUNCH_ALLOWANCE = java.time.Duration.ofMinutes(10);
}
