package cx.mia.lucent.laymark.core.materialize;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Works out which files have to move to put an arm on disk.
 *
 * <p>Pure. Given what is there and what should be, it returns the moves — it does not make them.
 * That split is what lets the awkward cases be tested: an instance left half-materialised by a
 * crash, a mod that vanished between rounds, an arm naming something the instance does not have.
 * Every one of those is a filesystem state that would take a real interrupted run to reproduce.
 *
 * <p>The vocabulary is the spec's: <strong>participants</strong> live in {@code mods/} whether or
 * not this particular arm loads them, and <strong>non-participants</strong> are moved out to
 * {@code laymark/withheld/} for the whole run. Participants toggle by rename between arms;
 * withheld files move once at the start and back once at the end.
 */
public final class Materialization {

    private Materialization() {}

    /**
     * Moves that take {@code current} to an arm where exactly {@code enabled} are loadable.
     *
     * @param participants every mod that takes part in this run, whether this arm loads it or not.
     *     Anything present in the instance and not named here is withheld.
     * @param enabled the subset that must load for this arm
     * @throws HarnessException if the arm names a mod the instance does not have, or enables one
     *     that is not a participant
     */
    public static List<FileOperation> plan(
            InstanceState current, Set<String> participants, Set<String> enabled) {

        Map<String, ModFile> known = current.byName();

        Set<String> missing = new TreeSet<>(participants);
        missing.removeAll(known.keySet());
        if (!missing.isEmpty()) {
            // Named but absent. Continuing would produce a run of a smaller stack than the one the
            // invocation described, and the numbers would look entirely reasonable.
            throw new HarnessException("the instance has no such mod(s): " + missing);
        }

        Set<String> notParticipating = new TreeSet<>(enabled);
        notParticipating.removeAll(participants);
        if (!notParticipating.isEmpty()) {
            throw new HarnessException(
                    "arm enables mod(s) that are not participants: " + notParticipating);
        }

        List<FileOperation> operations = new ArrayList<>();
        Set<String> enabledNow = current.enabledNames();
        Set<String> disabledNow = current.disabledNames();
        Set<String> withheldNow =
                new TreeSet<>(current.withheld().stream().map(ModFile::fileName).toList());

        // Participants first: bring each into mods/ if it was withheld, then set its state. Order
        // matters -- a file cannot be enabled while it is still in another directory.
        for (String name : new LinkedHashSet<>(participants)) {
            if (withheldNow.contains(name)) {
                operations.add(new FileOperation.Restore(name));
            }
            boolean wantEnabled = enabled.contains(name);
            boolean isEnabled = enabledNow.contains(name) && !withheldNow.contains(name);
            if (wantEnabled && !isEnabled) {
                operations.add(new FileOperation.Enable(name));
            } else if (!wantEnabled && (isEnabled || withheldNow.contains(name))) {
                operations.add(new FileOperation.Disable(name));
            }
        }

        // Everything else leaves mods/ entirely. Disabling would be enough for the loader, but a
        // directory holding only participants is one an operator can read at a glance, and the
        // difference matters when diagnosing which stack actually ran.
        for (String name : new TreeSet<>(known.keySet())) {
            if (participants.contains(name) || withheldNow.contains(name)) {
                continue;
            }
            if (disabledNow.contains(name)) {
                operations.add(new FileOperation.Enable(name));
            }
            operations.add(new FileOperation.Withhold(name));
        }
        return operations;
    }

    /**
     * Moves that put everything back where it was found.
     *
     * <p>Derived from the recorded starting state rather than from what was done since, so an
     * interrupted run recovers the same way a clean one finishes.
     */
    public static List<FileOperation> restore(InstanceState current, InstanceState initial) {
        List<FileOperation> operations = new ArrayList<>();

        for (ModFile file : initial.withheld()) {
            if (!current.withheld().contains(file)) {
                operations.add(new FileOperation.Withhold(file.fileName()));
            }
        }
        for (ModFile file : current.withheld()) {
            if (!initial.withheld().contains(file)) {
                operations.add(new FileOperation.Restore(file.fileName()));
            }
        }

        Set<String> shouldBeEnabled = initial.enabledNames();
        for (ModFile file : current.disabled()) {
            if (shouldBeEnabled.contains(file.fileName())) {
                operations.add(new FileOperation.Enable(file.fileName()));
            }
        }
        Set<String> shouldBeDisabled = initial.disabledNames();
        for (ModFile file : current.enabled()) {
            if (shouldBeDisabled.contains(file.fileName())) {
                operations.add(new FileOperation.Disable(file.fileName()));
            }
        }
        return operations;
    }

    /**
     * Confirms the instance is exactly the arm that was asked for, immediately before launching.
     *
     * <p>This is the real guard, not the plan. A rename that silently failed — a file locked by a
     * launcher, an antivirus holding a handle, a permissions problem — leaves a mods folder that
     * boots perfectly well and produces a completely plausible run of the wrong stack. Nothing
     * downstream can detect that, so it is checked here or not at all.
     *
     * @throws HarnessException naming exactly what differs
     */
    public static void verify(InstanceState observed, Set<String> enabled, InstanceState expectedContent) {
        Set<String> actual = observed.enabledNames();
        if (actual.equals(new TreeSet<>(enabled))) {
            // Names match; now the bytes. A jar replaced under the same name between rounds would
            // otherwise pass, and the two arms would be comparing different code.
            Map<String, ModFile> expected = expectedContent.byName();
            for (ModFile file : observed.enabled()) {
                ModFile was = expected.get(file.fileName());
                if (was != null && !was.sha256().equals(file.sha256())) {
                    throw new HarnessException(
                            file.fileName() + " has different contents than when the run started;"
                                    + " the two arms would not be comparing the same code");
                }
            }
            return;
        }
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(enabled);
        Set<String> absent = new TreeSet<>(enabled);
        absent.removeAll(actual);

        throw new HarnessException(
                "mods/ does not match the arm: "
                        + (unexpected.isEmpty() ? "" : "unexpectedly loadable " + unexpected + " ")
                        + (absent.isEmpty() ? "" : "missing " + absent)
                        + "; a rename that silently failed would otherwise have produced a"
                        + " plausible run of the wrong stack");
    }
}
