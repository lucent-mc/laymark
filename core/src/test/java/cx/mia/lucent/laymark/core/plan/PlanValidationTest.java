package cx.mia.lucent.laymark.core.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every way a plan can be invalid arrives as {@link PlanException}.
 *
 * <p>One type, because the runner catches this at the boundary where it loads a plan and cannot
 * prompt its way out of a bad one. A rejection that arrives as some other unchecked type escapes
 * that catch and crashes the launch instead of reporting it.
 */
class PlanValidationTest {

    private static final StopCondition ANY = new StopCondition(StopCondition.Kind.TIME, 1_000, 0);

    @Test
    void scenarioSpecRejectionsArePlanExceptions() {
        assertAll(
                () -> assertThrows(PlanException.class, () -> new ScenarioSpec("", List.of(), ANY, 1)),
                () -> assertThrows(PlanException.class, () -> new ScenarioSpec("a", List.of(), null, 1)),
                () -> assertThrows(PlanException.class, () -> new ScenarioSpec("a", List.of(), ANY, 0)),
                () ->
                        assertThrows(
                                PlanException.class,
                                () -> new ScenarioSpec("a", List.of("a"), ANY, 1)));
    }

    @Test
    void stopConditionRejectionsArePlanExceptions() {
        assertAll(
                () -> assertThrows(PlanException.class, () -> new StopCondition(StopCondition.Kind.TIME, 0, 0)),
                () ->
                        assertThrows(
                                PlanException.class,
                                () -> new StopCondition(null, 1_000, 1_000)),
                // A time target that cannot finish inside its own ceiling is a contradiction.
                () ->
                        assertThrows(
                                PlanException.class,
                                () -> new StopCondition(StopCondition.Kind.TIME, 60_000, 1_000)),
                // A variable stop condition with no ceiling hangs the run instead of failing it.
                () ->
                        assertThrows(
                                PlanException.class,
                                () -> new StopCondition(StopCondition.Kind.CHUNKS, 512, 0)));
    }

    /**
     * The protocol version is the one component the constructor used to let through. A plan
     * deserialized without it reads as version 0, which would launch Minecraft and only fail at
     * the handshake — after paying the launch cost the whole validation policy exists to avoid.
     */
    @Test
    void runPlanRejectsAnImpossibleProtocolVersion() {
        var scenarios = List.of(ScenarioSpec.of("a", ANY));
        assertAll(
                () ->
                        assertThrows(
                                PlanException.class,
                                () -> new RunPlan("r", 0, null, scenarios, "out")),
                () ->
                        assertThrows(
                                PlanException.class,
                                () -> new RunPlan("r", -5, null, scenarios, "out")));
    }
}
