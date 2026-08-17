package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which mods require which, and where that was learned.
 *
 * <p>Neither Minecraft nor Inlay has a native dependency graph, so this is assembled from several
 * sources of differing trustworthiness. The provenance travels with each edge because it changes
 * how much a conclusion is worth: an edge read out of a jar's own metadata is a fact about the
 * file that will load, while one from a registry is a fact about a project, and the two can
 * disagree when a jar has been repackaged.
 *
 * <p>Edges are between <strong>mod ids</strong>, not file names. A jar declares what it needs by
 * id, and the same mod can arrive under different file names.
 */
public record DependencyGraph(Map<String, Set<String>> requires, Map<Edge, Provenance> provenance) {

    /** Where an edge came from, in precedence order — earlier sources win. */
    public enum Provenance {
        /** Read from the jar that will actually load. The only source that describes the file. */
        JAR_METADATA,
        /** Looked up by hash in a registry. Describes the project, not necessarily this build. */
        REGISTRY,
        /** Stated by the operator, for the cases the other two get wrong. */
        OVERRIDE
    }

    /** One directed requirement. */
    public record Edge(String from, String to) {}

    public DependencyGraph {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        requires.forEach((mod, needs) -> copy.put(mod, new TreeSet<>(needs)));
        requires = Map.copyOf(copy);
        provenance = Map.copyOf(provenance);
    }

    public static DependencyGraph empty() {
        return new DependencyGraph(Map.of(), Map.of());
    }

    public Set<String> directRequirementsOf(String mod) {
        return requires.getOrDefault(mod, Set.of());
    }

    /**
     * Everything {@code mod} needs, transitively.
     *
     * <p>Cycles are tolerated rather than rejected. Mods declaring each other is unusual but not
     * illegal, and a benchmark has no business refusing to measure a pack the game will happily
     * load.
     */
    public Set<String> closureOf(String mod) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(directRequirementsOf(mod));
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (next.equals(mod) || !seen.add(next)) {
                continue;
            }
            pending.addAll(directRequirementsOf(next));
        }
        return seen;
    }

    /**
     * Everything that would break if {@code mod} were removed, transitively.
     *
     * <p>The mirror of {@link #closureOf}, and the one that matters when withholding a mod: taking
     * a library out without taking its dependents out does not produce a smaller stack, it produces
     * a stack that does not load.
     */
    public Set<String> dependentsOf(String mod) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(mod);
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            requires.forEach(
                    (candidate, needs) -> {
                        if (needs.contains(next) && !candidate.equals(mod) && seen.add(candidate)) {
                            pending.add(candidate);
                        }
                    });
        }
        return seen;
    }

    /** @return null when the edge is not in the graph */
    public Provenance provenanceOf(String from, String to) {
        return provenance.get(new Edge(from, to));
    }

    /** Merges sources, keeping the most trustworthy claim about each edge. */
    public static DependencyGraph merge(DependencyGraph... sources) {
        Map<String, Set<String>> requires = new LinkedHashMap<>();
        Map<Edge, Provenance> provenance = new LinkedHashMap<>();

        for (DependencyGraph source : sources) {
            source.requires()
                    .forEach(
                            (mod, needs) -> {
                                for (String need : needs) {
                                    Edge edge = new Edge(mod, need);
                                    Provenance incoming = source.provenanceOf(mod, need);
                                    if (incoming == null) {
                                        throw new HarnessException(
                                                "edge " + mod + " -> " + need + " has no provenance");
                                    }
                                    Provenance existing = provenance.get(edge);
                                    // Lower ordinal wins: a jar's own metadata beats a registry
                                    // lookup, which beats an operator's guess.
                                    if (existing == null || incoming.ordinal() < existing.ordinal()) {
                                        provenance.put(edge, incoming);
                                    }
                                    requires.computeIfAbsent(mod, unused -> new TreeSet<>())
                                            .add(need);
                                }
                            });
        }
        return new DependencyGraph(requires, provenance);
    }

    /** A graph from one source, for building each source independently before merging. */
    public static DependencyGraph from(Map<String, Set<String>> requires, Provenance provenance) {
        Map<Edge, Provenance> edges = new LinkedHashMap<>();
        requires.forEach(
                (mod, needs) -> needs.forEach(need -> edges.put(new Edge(mod, need), provenance)));
        return new DependencyGraph(requires, edges);
    }
}
