package cx.mia.lucent.laymark.core.harness;

/**
 * A disposable save to create and measure in.
 *
 * @param levelId the save directory name. Derived from the run id so two runs can never collide,
 *     and so an abandoned save is traceable back to the run that leaked it.
 * @param seed fixed rather than random: two arms comparing generation must generate the same
 *     terrain, or the comparison is between two landscapes rather than two mod stacks.
 */
public record WorldSpec(String levelId, long seed, boolean generateStructures) {

    /** Kept short: the save name becomes a directory, and Windows paths are finite. */
    private static final int MAX_LEVEL_ID = 60;

    public WorldSpec {
        if (levelId == null || levelId.isBlank()) {
            throw new HarnessException("world needs a level id");
        }
        if (levelId.length() > MAX_LEVEL_ID) {
            throw new HarnessException("level id is too long: " + levelId);
        }
        if (!levelId.matches("[A-Za-z0-9._-]+") || levelId.contains("..")) {
            // The level id reaches the filesystem, and the harness deletes what it names. A
            // portable alphabet is cheaper than discovering on one platform that a run wrote
            // somewhere unintended; excluding ".." separately because it is spelled entirely in
            // characters the alphabet allows.
            throw new HarnessException("level id has unsafe characters: " + levelId);
        }
    }

    /**
     * The save for one repetition, named after the scenario that <em>creates</em> it.
     *
     * <p>Not after the scenario using it. A scenario that declares {@code dependsOn} runs in the
     * world its dependency generated — that is what the dependency means — so the name has to
     * belong to the one that made it, and every user of that world resolves to the same name.
     *
     * <p>Independent scenarios still get a save each per repetition, because two phases have a
     * negative precondition that measuring destroys.
     */
    public static WorldSpec forRepetition(
            String runId,
            String ownerId,
            int repetition,
            long seed,
            cx.mia.lucent.laymark.core.result.Pass pass) {
        // The pass is part of the name because both passes have the same negative preconditions:
        // the warm pass's traversal needs terrain the cold pass never touched.
        return new WorldSpec(
                sanitize(
                        "laymark-" + runId + "-" + ownerId + "-" + repetition
                                + (pass == cx.mia.lucent.laymark.core.result.Pass.WARM ? "w" : "c")),
                seed,
                false);
    }

    /** Dots are dropped rather than kept: a generated id never needs one, and "." and ".." do. */
    private static String sanitize(String raw) {
        String safe = raw.replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.length() <= MAX_LEVEL_ID ? safe : safe.substring(0, MAX_LEVEL_ID);
    }

    /** Disposable saves are recognisable by name, so a crashed run's leftovers can be swept. */
    public static boolean isDisposable(String levelId) {
        return levelId != null && levelId.startsWith("laymark-");
    }
}
