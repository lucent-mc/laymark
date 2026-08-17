package cx.mia.lucent.laymark.core.materialize;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the instance's mod directories actually contain, right now.
 *
 * <p>Recorded once at the start of a run and kept, because <strong>recovery is a restore to a
 * recorded state, not the replay of an operation journal</strong>. A journal has to be correct
 * about every step and correct about which steps completed; a snapshot only has to be correct
 * once, and a crash halfway through leaves the same instruction as a crash at the end — put it
 * back the way it was.
 *
 * @param enabled files in {@code mods/} that the loader will load
 * @param disabled files in {@code mods/} carrying the {@code .disabled} suffix
 * @param withheld files moved aside into Laymark's own directory for the duration of the run
 */
public record InstanceState(
        List<ModFile> enabled, List<ModFile> disabled, List<ModFile> withheld) {

    public InstanceState {
        enabled = List.copyOf(enabled);
        disabled = List.copyOf(disabled);
        withheld = List.copyOf(withheld);

        Set<String> seen = new TreeSet<>();
        for (ModFile file : all(enabled, disabled, withheld)) {
            if (!seen.add(file.fileName())) {
                // The same jar enabled and disabled at once, or present in two directories, is a
                // state no arm can be materialised from -- and the likeliest cause is a previous
                // run that was interrupted partway through.
                throw new HarnessException(
                        "mod " + file.fileName() + " appears more than once across mods/ and"
                                + " withheld/; the instance is in a partly-materialised state");
            }
        }
    }

    private static List<ModFile> all(
            List<ModFile> enabled, List<ModFile> disabled, List<ModFile> withheld) {
        List<ModFile> everything = new java.util.ArrayList<>(enabled);
        everything.addAll(disabled);
        everything.addAll(withheld);
        return everything;
    }

    public static InstanceState empty() {
        return new InstanceState(List.of(), List.of(), List.of());
    }

    /** Every mod the instance knows about, wherever it currently sits. */
    public Map<String, ModFile> byName() {
        Map<String, ModFile> index = new LinkedHashMap<>();
        all(enabled, disabled, withheld).forEach(file -> index.put(file.fileName(), file));
        return index;
    }

    public Set<String> enabledNames() {
        return new TreeSet<>(enabled.stream().map(ModFile::fileName).toList());
    }

    /**
     * Whether this is the same content in the same places as another reading.
     *
     * <p>Compared by name <em>and</em> hash. A rename that silently failed leaves the right names
     * in the right places, and a jar swapped underneath leaves the right names with the wrong
     * bytes; only checking both catches each.
     */
    public boolean matches(InstanceState other) {
        return enabledNames().equals(other.enabledNames())
                && byName().equals(other.byName())
                && disabledNames().equals(other.disabledNames());
    }

    public Set<String> disabledNames() {
        return new TreeSet<>(disabled.stream().map(ModFile::fileName).toList());
    }
}
