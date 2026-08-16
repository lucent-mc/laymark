package cx.mia.lucent.laymark.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunPlanTest {

    private static ScenarioSpec spec(String id, String... dependsOn) {
        return new ScenarioSpec(id, List.of(dependsOn), new StopCondition.FixedDuration(1_000), 1);
    }

    private static RunPlan planOf(ScenarioSpec... scenarios) {
        return RunPlan.of("run-1", "out", List.of(scenarios));
    }

    /**
     * A resolved plan is archived with the results and launched from, so an unresolvable graph
     * must not survive construction. Deferring the check to {@link RunPlan#executionOrder()}
     * would make it optional, and the runner cannot prompt its way out of a bad plan mid-run.
     */
    @Test
    void rejectsAnUnresolvableGraphAtConstruction() {
        assertThrows(PlanException.class, () -> planOf(spec("a", "b"), spec("b", "a")));
        assertThrows(PlanException.class, () -> planOf(spec("render", "generate")));
        assertThrows(PlanException.class, () -> planOf(spec("a"), spec("a")));
    }

    @Test
    void executionOrderResolvesDependencies() {
        var plan = planOf(spec("render", "generate"), spec("generate"));
        assertEquals(
                List.of("generate", "render"),
                plan.executionOrder().stream().map(ScenarioSpec::id).toList());
    }

    @Test
    void rejectsAPlanWithNoScenarios() {
        var e = assertThrows(PlanException.class, () -> RunPlan.of("run-1", "out", List.of()));
        assertTrue(e.getMessage().contains("no scenarios"), e.getMessage());
    }
}
