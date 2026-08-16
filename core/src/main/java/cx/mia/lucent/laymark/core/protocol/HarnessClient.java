package cx.mia.lucent.laymark.core.protocol;

import cx.mia.lucent.laymark.core.Laymark;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The harness side of the channel.
 *
 * <p>Lives in {@code core} rather than a loader module because it is the mod's half of a contract
 * {@code core} already owns, and keeping it here means the whole round trip is testable without
 * launching a game.
 *
 * <p>The mod learns it was runner-launched from a system property, not from a file: a property
 * exists only for the life of the process, so unlike a leftover run file it cannot be stale. If
 * the property is set and the connection then fails, the run <strong>fails closed</strong>.
 * Proceeding unmonitored would burn an entire scenario list that nobody is watching, with no
 * heartbeat and no terminal result.
 */
public final class HarnessClient implements AutoCloseable {

    private final Socket socket;
    private final FrameChannel channel;

    private HarnessClient(Socket socket, FrameChannel channel) {
        this.socket = socket;
        this.channel = channel;
    }

    /** @return true when the runner launched this process and expects a connection */
    public static boolean launchedByRunner() {
        return System.getProperty(Laymark.PROPERTY_PORT) != null;
    }

    /**
     * Connects using the port and token the runner put on the command line, and completes the
     * handshake.
     *
     * @throws ProtocolException if the properties are missing or malformed, or the connection
     *     cannot be established. Never returns a client that is not connected.
     */
    public static HarnessClient connectFromSystemProperties(int timeoutMillis) {
        String rawPort = System.getProperty(Laymark.PROPERTY_PORT);
        String token = System.getProperty(Laymark.PROPERTY_TOKEN);
        if (rawPort == null) {
            throw new ProtocolException(
                    "not runner-launched: " + Laymark.PROPERTY_PORT + " is not set");
        }
        if (token == null || token.isBlank()) {
            throw new ProtocolException(
                    Laymark.PROPERTY_PORT
                            + " is set but "
                            + Laymark.PROPERTY_TOKEN
                            + " is not; refusing to connect without the handshake token");
        }
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException e) {
            throw new ProtocolException(
                    "malformed " + Laymark.PROPERTY_PORT + ": '" + rawPort + "'", e);
        }
        return connect(port, token, timeoutMillis);
    }

    public static HarnessClient connect(int port, String token, int timeoutMillis) {
        Socket socket = new Socket();
        try {
            // Literal 127.0.0.1 on both sides, deliberately, rather than getLoopbackAddress().
            // The game is launched with -Djava.net.preferIPv6Addresses=system straight out of the
            // launcher's descriptor, so that call answers ::1 inside the game while the runner --
            // an ordinary JVM -- binds IPv4. Two processes, same API, different address family,
            // connection refused on a port that is definitely listening.
            socket.connect(new InetSocketAddress(Loopback.ADDRESS, port), timeoutMillis);
            FrameChannel channel = FrameChannel.over(socket.getInputStream(), socket.getOutputStream());
            channel.send(
                    new Frame.Hello(token, Laymark.PROTOCOL_VERSION, ProcessHandle.current().pid()));
            return new HarnessClient(socket, channel);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new ProtocolException(
                    "could not reach the runner on loopback port " + port
                            + "; the run fails rather than continuing unmonitored",
                    e);
        }
    }

    public void send(Frame frame) {
        try {
            channel.send(frame);
        } catch (IOException e) {
            throw new ProtocolException("could not send " + frame.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing is best-effort: the terminal frame has already been sent, and the runner
            // treats socket close as the end of the run regardless.
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the process is on its way out.
        }
    }
}

