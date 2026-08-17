package cx.mia.lucent.laymark.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioOrderTest {

    private static ScenarioSpec spec(String id, String... dependsOn) {
        return new ScenarioSpec(
                id, List.of(dependsOn), new StopCondition(StopCondition.Kind.TIME, 45_000, 0), 1);
    }

    private static List<String> idsOf(List<ScenarioSpec> specs) {
        return specs.stream().map(ScenarioSpec::id).toList();
    }

    @Test
    void independentScenariosKeepDeclarationOrder() {
        var ordered = ScenarioOrder.resolve(List.of(spec("a"), spec("b"), spec("c")));
        assertEquals(List.of("a", "b", "c"), idsOf(ordered));
    }

    @Test
    void dependenciesRunFirst() {
        // Declared out of order on purpose: "render" reuses the world "generate" produced.
        var ordered = ScenarioOrder.resolve(List.of(spec("render", "generate"), spec("generate")));
        assertEquals(List.of("generate", "render"), idsOf(ordered));
    }

    @Test
    void handlesDiamonds() {
        var ordered =
                ScenarioOrder.resolve(
                        List.of(
                                spec("bottom", "left", "right"),
                                spec("left", "top"),
                                spec("right", "top"),
                                spec("top")));
        var ids = idsOf(ordered);
        assertEquals(4, ids.size());
        assertTrue(ids.indexOf("top") < ids.indexOf("left"));
        assertTrue(ids.indexOf("top") < ids.indexOf("right"));
        assertTrue(ids.indexOf("left") < ids.indexOf("bottom"));
        assertTrue(ids.indexOf("right") < ids.indexOf("bottom"));
    }

    /**
     * Declaration order is the tie-break, not traversal order. Here {@code c} and {@code b} are
     * both runnable from the start and {@code c} is declared first, so {@code c} runs first even
     * though {@code b} unblocks the scenario declared above it.
     */
    @Test
    void readyScenariosKeepDeclarationOrder() {
        var ordered = ScenarioOrder.resolve(List.of(spec("a", "b"), spec("c"), spec("b")));
        assertEquals(List.of("c", "b", "a"), idsOf(ordered));
    }

    /**
     * Order must be stable across runs. Array position is part of a scenario's identity --
     * scenario 1 runs against a colder JVM than scenario 5 -- so an ordering that varied
     * between runs would silently make two arms incomparable.
     */
    @Test
    void orderIsDeterministic() {
        var input = List.of(spec("d", "b"), spec("c"), spec("b", "a"), spec("a"), spec("e"));
        var first = idsOf(ScenarioOrder.resolve(input));
        for (int i = 0; i < 50; i++) {
            assertEquals(first, idsOf(ScenarioOrder.resolve(input)));
        }
    }

    @Test
    void rejectsCycles() {
        var e =
                assertThrows(
                        PlanException.class,
                        () -> ScenarioOrder.resolve(List.of(spec("a", "b"), spec("b", "a"))));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    /** The path must name the cycle, not the acyclic run-up that reached it. */
    @Test
    void cycleDiagnosticNamesOnlyTheCycle() {
        var e =
                assertThrows(
                        PlanException.class,
                        () ->
                                ScenarioOrder.resolve(
                                        List.of(spec("x", "a"), spec("a", "b"), spec("b", "a"))));
        assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
        assertFalse(e.getMessage().contains("x"), e.getMessage());
    }

    @Test
    void rejectsMissingDependencies() {
        var e =
                assertThrows(
                        PlanException.class,
                        () -> ScenarioOrder.resolve(List.of(spec("render", "generate"))));
        assertTrue(e.getMessage().contains("generate"), e.getMessage());
        assertTrue(e.getMessage().contains("dependency-closed"), "the error should say why");
    }

    @Test
    void rejectsDuplicateIds() {
        var e =
                assertThrows(
                        PlanException.class,
                        () -> ScenarioOrder.resolve(List.of(spec("a"), spec("a"))));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void rejectsSelfDependency() {
        assertThrows(PlanException.class, () -> spec("a", "a"));
    }
}
