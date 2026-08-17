package cx.mia.lucent.laymark.core.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Selection is pure: what gets promoted, what a bundle contains, and in what order. */
class SelectionTest {

    private static DependencyGraph graph(Map<String, Set<String>> requires) {
        return DependencyGraph.from(requires, DependencyGraph.Provenance.JAR_METADATA);
    }

    /** A candidate cannot load without what it requires, so that is what gets measured. */
    @Test
    void aBundleCarriesTheCandidatesExclusiveDependencies() {
        Bundle bundle =
                Bundle.of("sodium", graph(Map.of("sodium", Set.of("fabric-api"))), Set.of());

        assertEquals(Set.of("sodium", "fabric-api"), bundle.members());
        assertEquals(Set.of("fabric-api"), bundle.exclusiveDependencies());
        assertFalse(bundle.isAtomic());
    }

    @Test
    void dependenciesAlreadyGuaranteedAreNotPartOfTheBundle() {
        Bundle bundle =
                Bundle.of("sodium", graph(Map.of("sodium", Set.of("fabric-api"))), Set.of("fabric-api"));

        assertEquals(Set.of("sodium"), bundle.members());
        assertTrue(bundle.isAtomic());
    }

    @Test
    void bundlesFollowRequirementsTransitively() {
        Bundle bundle =
                Bundle.of(
                        "a",
                        graph(Map.of("a", Set.of("b"), "b", Set.of("c"))),
                        Set.of());

        assertEquals(Set.of("a", "b", "c"), bundle.members());
    }

    /** A pack the game loads is a pack the benchmark measures, cycles included. */
    @Test
    void toleratesACycleRatherThanRefusingToMeasure() {
        Bundle bundle =
                Bundle.of("a", graph(Map.of("a", Set.of("b"), "b", Set.of("a"))), Set.of());

        assertEquals(Set.of("a", "b"), bundle.members());
    }

    /**
     * The behaviour the report has to be able to explain: a score moving because a bundle shrank
     * is not the same as a score moving because of an interaction.
     */
    @Test
    void aBundleShrinksOnceItsDependencyHasBeenPromoted() {
        DependencyGraph graph = graph(Map.of("x", Set.of("lib"), "y", Set.of("lib")));
        Selection selection = new Selection(graph, Set.of());

        assertEquals(
                Set.of("x", "lib"), selection.bundlesFor(List.of("x", "y")).get(0).members());

        selection.round(List.of("x"), bundle -> Selection.Verdict.PROMOTED);

        Bundle afterwards = selection.bundlesFor(List.of("y")).get(0);
        assertEquals(Set.of("y"), afterwards.members(), "lib is guaranteed now, so y is alone");
        assertTrue(afterwards.isAtomic());
    }

    /** Promotion says so out loud, because part of a bundle's gain may not be the candidate's. */
    @Test
    void promotingABundleRecordsWhatCameWithIt() {
        Selection selection = new Selection(graph(Map.of("x", Set.of("lib"))), Set.of());

        List<Selection.Outcome> outcomes =
                selection.round(List.of("x"), bundle -> Selection.Verdict.PROMOTED);

        assertTrue(outcomes.get(0).detail().contains("lib"), outcomes.get(0).detail());
    }

    /**
     * Applied after the round, not during. Otherwise a candidate judged later would have been
     * measured against a different baseline than one judged earlier, in the same round.
     */
    @Test
    void promotionsTakeEffectOnlyAfterTheWholeRound() {
        DependencyGraph graph = graph(Map.of("x", Set.of("lib"), "y", Set.of("lib")));
        Selection selection = new Selection(graph, Set.of());

        List<Selection.Outcome> outcomes =
                selection.round(List.of("x", "y"), bundle -> Selection.Verdict.PROMOTED);

        assertEquals(Set.of("x", "lib"), outcomes.get(0).bundle().members());
        assertEquals(
                Set.of("y", "lib"),
                outcomes.get(1).bundle().members(),
                "both were judged against the same baseline");
    }

    /** Nothing is eliminated: a mod that does nothing now may do something on a heavier baseline. */
    @Test
    void aRegressionStaysInThePoolAndIsNeverRejected() {
        Selection selection = new Selection(DependencyGraph.empty(), Set.of());

        List<Selection.Outcome> outcomes =
                selection.round(List.of("x"), bundle -> Selection.Verdict.REGRESSED);

        assertEquals(Selection.Verdict.REGRESSED, outcomes.get(0).verdict());
        assertTrue(selection.promoted().isEmpty());
        assertFalse(
                selection.bundlesFor(List.of("x")).isEmpty(),
                "it is still offered next round against a different baseline");
    }

    @Test
    void anInconclusiveCandidateIsAlsoKept() {
        Selection selection = new Selection(DependencyGraph.empty(), Set.of());
        selection.round(List.of("x"), bundle -> Selection.Verdict.INCONCLUSIVE);

        assertTrue(selection.promoted().isEmpty());
        assertEquals(1, selection.bundlesFor(List.of("x")).size());
    }

    @Test
    void aCandidatePromotedAsSomeoneElsesDependencyDropsOutOfThePool() {
        Selection selection = new Selection(graph(Map.of("x", Set.of("lib"))), Set.of());
        selection.round(List.of("x"), bundle -> Selection.Verdict.PROMOTED);

        assertTrue(
                selection.bundlesFor(List.of("lib")).isEmpty(),
                "lib is already loaded, so there is no off-arm to compare against");
    }

    /**
     * If something already present requires the candidate, the off-arm cannot exist — disabling it
     * would break the thing that needs it, so there is nothing to compare against.
     */
    @Test
    void namesCandidatesNothingCanBeMeasuredAgainst() {
        Selection selection =
                new Selection(graph(Map.of("inherited", Set.of("candidate"))), Set.of("inherited"));

        assertEquals(Set.of("candidate"), selection.unmeasurable(List.of("candidate")));
    }

    /** A jar describes the file that will load; a registry describes a project. */
    @Test
    void jarMetadataOutranksARegistryWhichOutranksAnOverride() {
        DependencyGraph merged =
                DependencyGraph.merge(
                        DependencyGraph.from(
                                Map.of("a", Set.of("b")), DependencyGraph.Provenance.OVERRIDE),
                        DependencyGraph.from(
                                Map.of("a", Set.of("b")), DependencyGraph.Provenance.JAR_METADATA),
                        DependencyGraph.from(
                                Map.of("a", Set.of("b")), DependencyGraph.Provenance.REGISTRY));

        assertEquals(
                DependencyGraph.Provenance.JAR_METADATA, merged.provenanceOf("a", "b"));
    }

    @Test
    void mergingUnionsEdgesFromEverySource() {
        DependencyGraph merged =
                DependencyGraph.merge(
                        DependencyGraph.from(
                                Map.of("a", Set.of("b")), DependencyGraph.Provenance.JAR_METADATA),
                        DependencyGraph.from(
                                Map.of("a", Set.of("c")), DependencyGraph.Provenance.OVERRIDE));

        assertEquals(Set.of("b", "c"), merged.directRequirementsOf("a"));
        assertEquals(DependencyGraph.Provenance.OVERRIDE, merged.provenanceOf("a", "c"));
    }
}
