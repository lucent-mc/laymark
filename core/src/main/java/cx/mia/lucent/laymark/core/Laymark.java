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
}
