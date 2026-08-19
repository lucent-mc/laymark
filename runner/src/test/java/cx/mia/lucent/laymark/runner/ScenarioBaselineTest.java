package cx.mia.lucent.laymark.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.select.Bundle;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A candidate is compared against the baseline <em>for the same scenario</em>.
 *
 * <p>Two scenarios report the same unit for different work — generating a chunk costs about
 * 4ms and loading one about 0.4ms — so a baseline pool holding both would compare a loading time
 * against a generation time and call the tenfold gap a 90% improvement. Every candidate here
 * measures exactly what the baseline measured, so every honest comparison is 0%.
 */
class ScenarioBaselineTest {

    private static final String GENERATE = "chunk-generation";
    private static final String LOAD = "chunk-loading";
    private static final String CANDIDATE = "some-mod.jar";

    /** Loading is an order of magnitude cheaper than generation: the gap the bug turned into a win. */
    private static final double GENERATE_MILLIS = 4.2;

    private static final double LOAD_MILLIS = 0.42;

    @Test
    void aCandidateIsComparedAgainstItsOwnScenarioBaseline() throws Exception {
        List<Object> measured = new ArrayList<>();
        // baseline, candidate, candidate, baseline -- the shape a schedule actually produces, so
        // both candidate runs sit between two baselines and bracketing has something to average.
        measured.add(measured("baseline", 1, GENERATE, GENERATE_MILLIS));
        measured.add(measured("baseline", 1, LOAD, LOAD_MILLIS));
        measured.add(measured(CANDIDATE, 2, GENERATE, GENERATE_MILLIS));
        measured.add(measured(CANDIDATE, 2, LOAD, LOAD_MILLIS));
        measured.add(measured(CANDIDATE, 3, GENERATE, GENERATE_MILLIS));
        measured.add(measured(CANDIDATE, 3, LOAD, LOAD_MILLIS));
        measured.add(measured("baseline", 4, GENERATE, GENERATE_MILLIS));
        measured.add(measured("baseline", 4, LOAD, LOAD_MILLIS));

        Method compare =
                SelectionRun.class.getDeclaredMethod(
                        "compare",
                        List.class,
                        List.class,
                        List.class,
                        cx.mia.lucent.laymark.core.stats.MachineProfile.class);
        compare.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, List<Comparison>> byCandidate =
                (Map<String, List<Comparison>>)
                        compare.invoke(
                                null,
                                List.of(new Bundle(CANDIDATE, Set.of(CANDIDATE))),
                                measured,
                                List.of(),
                                null);

        List<Comparison> comparisons = byCandidate.getOrDefault(CANDIDATE, List.of());
        assertEquals(2, comparisons.size(), "one comparison per scenario");
        for (Comparison comparison : comparisons) {
            assertTrue(
                    Math.abs(comparison.improvementPercent()) < 1.0,
                    () ->
                            comparison.scenarioId()
                                    + " scored "
                                    + comparison.improvementPercent()
                                    + "% against an identical baseline; it is being compared"
                                    + " against another scenario's numbers");
        }
    }

    /** Builds the driver's private per-scenario measurement record. */
    private static Object measured(String armId, int sequence, String scenarioId, double millis)
            throws ReflectiveOperationException {
        Class<?> metricsType =
                Class.forName("cx.mia.lucent.laymark.runner.SelectionRun$Metrics");
        Constructor<?> metrics =
                metricsType.getDeclaredConstructor(Double.class, Double.class, Double.class);
        metrics.setAccessible(true);

        Class<?> measuredType =
                Class.forName("cx.mia.lucent.laymark.runner.SelectionRun$Measured");
        Constructor<?> constructor =
                measuredType.getDeclaredConstructor(
                        String.class, int.class, String.class, double.class, metricsType);
        constructor.setAccessible(true);
        return constructor.newInstance(
                armId, sequence, scenarioId, millis, metrics.newInstance(1.0, 100.0, millis));
    }
}
