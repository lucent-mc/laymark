package cx.mia.lucent.laymark.core;

/**
 * Constants shared by both sides of the seam.
 *
 * <p>This lives in {@code core} precisely because both the mod and the runner need it and
 * neither should own it. The mod defines the contract; the runner satisfies it.
 */
public final class Laymark {

    private Laymark() {}

    /** NeoForge mod id, Modrinth slug, and artifact id. */
    public static final String ID = "laymark";

    /**
     * Version of the runner/harness wire protocol, independent of the product version.
     *
     * <p>Exact-matched at handshake. A 0.3.1 runner talks happily to a 0.3.2 mod when the
     * protocol did not change; a genuine protocol change fails immediately and legibly rather
     * than misparsing.
     */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * System property carrying the loopback port the runner is listening on.
     *
     * <p>Its presence is the launch fact: set means runner-launched, absent means a human
     * started the game. A system property cannot be stale the way a leftover run file could.
     */
    public static final String PROPERTY_PORT = "laymark.port";

    /**
     * System property carrying the single-use token the mod must send as its first frame.
     *
     * <p>Loopback is reachable by any local process, so this nonce is the access control.
     */
    public static final String PROPERTY_TOKEN = "laymark.token";

    /**
     * Where the runner leaves the plan, relative to the instance's game directory.
     *
     * <p>A file rather than a socket message: documents on disk, events on the wire. The plan has
     * to be archived alongside the results for a historical run to stay interpretable, and a file
     * can be read by a human diagnosing a launch that never got as far as connecting.
     */
    public static final String PLAN_PATH = "config/laymark/plan.json";

    /**
     * Where scene files are staged inside the instance, relative to the game directory.
     *
     * <p>A config and its schematics live wherever the operator keeps them; the harness reads them
     * from inside the game. The runner copies them here so the plan can reference a scene by the
     * name the harness will find, rather than by a path that only existed on the machine that
     * wrote the config.
     */
    public static final String SCENE_DIR = "config/laymark/scenes";

    /** Result document name, written inside the plan's output directory. */
    public static final String RESULT_FILE = "result.json";
}
