package cx.mia.lucent.laymark.core.experiment;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.Set;
import java.util.TreeSet;

/**
 * One side of a comparison: a named mod stack, run as its own process.
 *
 * <p>Its own process because mods load at startup, so switching arms forces a relaunch whatever
 * else happens. That constraint is what makes contamination symmetric — both arms pay the same
 * launch cost — and it is why array position is part of a scenario's identity: scenario 1 runs
 * against a colder JVM than scenario 5 in every arm alike.
 *
 * @param kind what this arm is for, which decides how its measurements are used
 * @param enabled the mods loadable in this arm, by file name
 */
public record Arm(String id, Kind kind, Set<String> enabled) {

    public enum Kind {
        /**
         * A full baseline run whose measurements are <strong>discarded</strong>.
         *
         * <p>Measured rather than skipped: the instrumentation itself needs warming — frame
         * sampling, GPU queries and socket emission are code that JITs and buffers that allocate —
         * and it warms state that survives process death, like the OS page cache and the GPU
         * driver's shader cache.
         */
        ACCLIMATION,

        /** The reference stack. These runs are also the drift checks. */
        BASELINE,

        /** The baseline plus one candidate bundle. */
        CANDIDATE
    }

    public Arm {
        if (id == null || id.isBlank()) {
            throw new HarnessException("an arm has no id");
        }
        if (kind == null) {
            throw new HarnessException("arm " + id + " has no kind");
        }
        enabled = new TreeSet<>(enabled == null ? Set.of() : enabled);
    }

    /** Whether this arm's measurements reach a report at all. */
    public boolean scored() {
        return kind != Kind.ACCLIMATION;
    }
}
