package cx.mia.lucent.laymark.core.result;

import cx.mia.lucent.laymark.core.plan.RunPlan;
import cx.mia.lucent.laymark.core.plan.StopCondition;

/**
 * One scenario's comparable numbers, extracted the same way everywhere.
 *
 * <p>Shared between the mod and the runner deliberately: the mod streams these live as each
 * capture closes, the runner recomputes them from the result file, and the two must agree because
 * a live number that differs from the final one is a bug report nobody can act on.
 *
 * @param scoredMillis the scored metric under the plan's stop condition — time-per-chunk for a
 *     CHUNKS stop where the channel exists, mean frame time otherwise
 * @param mspt server mean tick time, null where Spark could not supply it
 */
public record Channels(double scoredMillis, Double mspt, Double fps, Double msPerChunk) {

    public static Channels of(ScenarioResult scenario, RunPlan plan) {
        var segment = scenario.segments().get(scenario.segments().size() - 1);
        var spark = segment.measurement().spark();
        Double perChunk = segment.summaries().millisPerChunkReceived();

        var spec =
                plan.scenarios().stream()
                        .filter(s -> s.id().equals(scenario.scenarioId()))
                        .findFirst()
                        .orElseThrow();
        double scored =
                spec.stopCondition().kind() == StopCondition.Kind.CHUNKS && perChunk != null
                        ? perChunk
                        : scenario.statistics().meanMillis();

        return new Channels(
                scored,
                spark == null ? null : spark.millisPerTickMean(),
                segment.summaries().interval().meanFramesPerSecond(),
                perChunk);
    }
}
