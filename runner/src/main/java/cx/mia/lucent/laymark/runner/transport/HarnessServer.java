package cx.mia.lucent.laymark.runner.transport;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.protocol.FrameChannel;
import cx.mia.lucent.laymark.core.protocol.FrameCodec;
import cx.mia.lucent.laymark.core.protocol.Loopback;
import cx.mia.lucent.laymark.core.protocol.ProtocolException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * The runner's half of the channel.
 *
 * <p>Binds before the game is launched, which removes the startup race entirely: the port exists
 * before anything could connect to it, and it is handed to the child on the command line the
 * runner is already building. Port discovery is not a problem the harness has to solve.
 *
 * <p>Bound to {@code 127.0.0.1} explicitly rather than to all interfaces — that is what avoids a
 * Windows Firewall prompt, and there is no reason for anything off this machine to reach it.
 * Loopback is still reachable by any local process, so the single-use token is the access control.
 *
 * <p>Every received line is appended to {@code events.jsonl} <strong>verbatim</strong>, before it
 * is decoded. The durable record therefore survives a frame the runner cannot parse, and it
 * survives the game dying mid-run — which is exactly when the record matters most, and exactly
 * when the process that produced it is no longer around to be asked.
 */
public final class HarnessServer implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSocket serverSocket;
    private final String token;
    private final Path eventsFile;

    private HarnessServer(ServerSocket serverSocket, String token, Path eventsFile) {
        this.serverSocket = serverSocket;
        this.token = token;
        this.eventsFile = eventsFile;
    }

    /**
     * Binds an ephemeral loopback port and mints a single-use token.
     *
     * @param eventsFile every received line is appended here verbatim
     */
    public static HarnessServer bind(Path eventsFile) {
        try {
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
            byte[] nonce = new byte[24];
            RANDOM.nextBytes(nonce);
            return new HarnessServer(socket, HexFormat.of().formatHex(nonce), eventsFile);
        } catch (IOException e) {
            throw new UncheckedIOException("could not bind a loopback port for the harness", e);
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** The nonce the child must echo. Passed on its command line, never written to disk. */
    public String token() {
        return token;
    }

    /**
     * Waits for the harness to connect and validates its handshake.
     *
     * @throws ProtocolException if the first frame is not a well-formed {@link Frame.Hello} with
     *     the expected token and an exactly matching protocol version
     */
    public Session accept(int timeoutMillis) throws IOException {
        serverSocket.setSoTimeout(timeoutMillis);
        Socket socket = serverSocket.accept();
        FrameChannel channel = FrameChannel.over(socket.getInputStream(), socket.getOutputStream());

        String line = channel.readLine();
        if (line == null) {
            throw new ProtocolException("harness connected and disconnected without a handshake");
        }
        append(line);
        Frame first = FrameCodec.decode(line);
        if (!(first instanceof Frame.Hello hello)) {
            throw new ProtocolException(
                    "first frame must be hello, got " + first.getClass().getSimpleName());
        }
        if (!constantTimeEquals(token, hello.token())) {
            throw new ProtocolException(
                    "handshake token mismatch; loopback is reachable by any local process,"
                            + " so a wrong token means this is not our harness");
        }
        if (hello.protocolVersion() != Laymark.PROTOCOL_VERSION) {
            throw new ProtocolException(
                    "protocol version mismatch: runner speaks "
                            + Laymark.PROTOCOL_VERSION
                            + ", harness speaks "
                            + hello.protocolVersion());
        }
        return new Session(socket, channel, hello);
    }

    /**
     * Reads frames until the harness closes the connection, persisting each line before
     * decoding it.
     */
    public void pump(Session session, Consumer<Frame> onFrame) throws IOException {
        String line;
        while ((line = session.channel.readLine()) != null) {
            append(line);
            onFrame.accept(FrameCodec.decode(line));
        }
    }

    private void append(String line) throws IOException {
        Path parent = eventsFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter out =
                Files.newBufferedWriter(
                        eventsFile,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
            out.write(line);
            out.write('\n');
        }
    }

    /**
     * Length-independent comparison. The token is single-use and the attacker would need local
     * access already, so this is cheap insurance rather than a load-bearing defence.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        int difference = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            difference |= a[i] ^ b[i];
        }
        return difference == 0;
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    /** A connected harness. */
    public static final class Session implements AutoCloseable {

        private final Socket socket;
        private final FrameChannel channel;
        private final Frame.Hello hello;

        private Session(Socket socket, FrameChannel channel, Frame.Hello hello) {
            this.socket = socket;
            this.channel = channel;
            this.hello = hello;
        }

        /**
         * The game's own process id.
         *
         * <p>Worth having even though the runner spawned the child and already holds its handle:
         * it proves the process that connected is the process that was launched, rather than
         * something else that reached the port first.
         */
        public long pid() {
            return hello.pid();
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                socket.close();
            }
        }
    }
}

