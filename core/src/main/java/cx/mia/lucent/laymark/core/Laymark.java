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
     * The scenario config, relative to the instance's game directory.
     *
     * <p><strong>Hand-authored, and the single source of what a run measures.</strong> The runner
     * never writes it. Both sides resolve the same document — the runner for scheduling and the
     * harness for execution — so there is no separate plan file to drift from it; the resolved
     * plan is archived beside the results, never inside the instance.
     */
    public static final String CONFIG_PATH = "config/laymark.json";

    /**
     * Laymark's working directory inside the instance: cache and measurements, never configuration.
     *
     * <p>Dot-prefixed so launchers, pack tooling and Inlay reconciliation all read it as internal
     * state rather than content someone authored.
     */
    public static final String WORK_DIR = ".laymark";

    /**
     * Where scene files are staged inside the instance, relative to the game directory.
     *
     * <p>A config's schematics live wherever the operator keeps them; the harness reads them from
     * inside the game. The runner copies them here so the plan can reference a scene by the name
     * the harness will find, rather than by a path that only existed on the machine that wrote the
     * config.
     */
    public static final String SCENE_DIR = WORK_DIR + "/scenes";

    /** The run id the harness resolves the config under, passed on the launch command line. */
    public static final String PROPERTY_RUN_ID = "laymark.run";

    /** Where the harness writes its result, passed the same way. */
    public static final String PROPERTY_OUTPUT = "laymark.out";

    /** Result document name, written inside the plan's output directory. */
    public static final String RESULT_FILE = "result.json";
}
