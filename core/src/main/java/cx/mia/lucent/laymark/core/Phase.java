package cx.mia.lucent.laymark.core;

/**
 * The measured phases of a scenario.
 *
 * <p>Each has a positive precondition — we are set up — and two of them have a <em>negative</em>
 * one: the work being measured must not already have happened. A phase whose negative
 * precondition is unmet completes normally and reports flatteringly good numbers, which is why
 * those preconditions fail the run rather than degrade it.
 */
public enum Phase {

    /**
     * World creation through to the join barrier. A phase in its own right rather than
     * overhead, because mods target spawn-chunk generation specifically.
     *
     * <p><strong>Its frame channel sits under a vanilla cap.</strong> While no level is loaded and
     * a screen is up — which is precisely world creation — the framerate limiter reports
     * {@code OUT_OF_LEVEL_MENU} and holds 60 fps. Every capture of this phase is therefore flagged
     * as throttled, correctly. The signal here is the <em>duration</em> and the work counters:
     * measured on a real run, 393 server chunks and 163 rendered sections came into existence
     * during a 1.8s window that no other phase can see.
     */
    SPAWN_GENERATION,

    /**
     * Generation and everything downstream of it.
     *
     * <p>Negative precondition: the target region must <strong>never have been generated</strong>.
     * Measuring it mutates it, so every repetition needs a fresh save.
     */
    UNGENERATED_TRAVERSAL,

    /**
     * Client-side streaming: deserialization, server-to-client delivery, mesh building, upload.
     *
     * <p>Negative precondition: the client holds <strong>no built sections</strong> for the
     * target. Deliberately not called a cold-disk test — after generation the region files sit
     * in the OS page cache, which a mod cannot portably evict.
     */
    GENERATED_STREAMING,

    /**
     * The steady render path. The only phase where readiness means everything finished.
     */
    RESIDENT_RENDER,
}
