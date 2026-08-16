package cx.mia.lucent.laymark.core.protocol;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The one loopback address both sides of the channel agree on.
 *
 * <p>Deliberately a literal IPv4 address rather than {@link InetAddress#getLoopbackAddress()}.
 * That method answers whatever the <em>local JVM</em> prefers, and the two JVMs here do not agree:
 * the game is launched with {@code -Djava.net.preferIPv6Addresses=system}, taken straight from the
 * launcher's own descriptor, so it resolves loopback to {@code ::1} while the runner — an ordinary
 * JVM — binds {@code 127.0.0.1}.
 *
 * <p>The failure that produces is genuinely misleading: the runner reports it is listening on a
 * port, the mod reports the connection was refused on that exact port, and both are telling the
 * truth about different address families.
 */
public final class Loopback {

    private Loopback() {}

    public static final InetAddress ADDRESS = ipv4Loopback();

    private static InetAddress ipv4Loopback() {
        try {
            // By address rather than by name: no resolver, no hosts file, no ambiguity.
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException("127.0.0.1 is not a valid address", e);
        }
    }
}

