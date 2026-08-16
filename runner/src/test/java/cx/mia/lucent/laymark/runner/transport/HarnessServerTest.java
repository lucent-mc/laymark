package cx.mia.lucent.laymark.runner.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.protocol.FrameCodec;
import cx.mia.lucent.laymark.core.protocol.HarnessClient;
import cx.mia.lucent.laymark.core.protocol.ProtocolException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Still tier 1: a real loopback socket, but no Minecraft and no launcher. Runs in milliseconds.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HarnessServerTest {

    @TempDir Path temp;

    private Path events() {
        return temp.resolve("runs/r1/events.jsonl");
    }

    @Test
    void handshakeSucceedsAndFramesArrive() throws Exception {
        try (HarnessServer server = HarnessServer.bind(events())) {
            var accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept(10_000);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            List<Frame> received = new ArrayList<>();
            try (HarnessClient client = HarnessClient.connect(server.port(), server.token(), 5_000)) {
                HarnessServer.Session session = accepted.get();
                assertEquals(ProcessHandle.current().pid(), session.pid(),
                        "the handshake proves which process connected");

                var pumped = CompletableFuture.runAsync(() -> {
                    try {
                        server.pump(session, received::add);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                client.send(new Frame.ScenarioStarted("chunkgen", 1));
                client.send(new Frame.PhaseEntered("chunkgen", Phase.UNGENERATED_TRAVERSAL, 42L));
                client.send(new Frame.RunFinished("runs/r1/result.json"));
                client.close();
                pumped.get();
            }

            assertEquals(3, received.size(), received.toString());
            assertEquals(new Frame.ScenarioStarted("chunkgen", 1), received.get(0));
            assertEquals(new Frame.RunFinished("runs/r1/result.json"), received.get(2));
        }
    }

    /**
     * The archived line must be the line that crossed the wire, not a re-encoding of it. That is
     * what makes the wire format and the durable format the same format rather than two formats
     * that happen to agree today.
     */
    @Test
    void persistsEveryLineVerbatimIncludingTheHandshake() throws Exception {
        try (HarnessServer server = HarnessServer.bind(events())) {
            var accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept(10_000);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Frame frame = new Frame.RunFailed("preset-drift", "renderDistance=12, expected 32");
            try (HarnessClient client = HarnessClient.connect(server.port(), server.token(), 5_000)) {
                HarnessServer.Session session = accepted.get();
                var pumped = CompletableFuture.runAsync(() -> {
                    try {
                        server.pump(session, f -> {});
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                client.send(frame);
                client.close();
                pumped.get();
            }

            List<String> lines = Files.readAllLines(events(), StandardCharsets.UTF_8);
            assertEquals(2, lines.size(), "the hello is part of the record too");
            assertTrue(lines.get(0).contains("\"type\":\"hello\""), lines.get(0));
            assertEquals(FrameCodec.encode(frame), lines.get(1),
                    "persisted bytes must equal the encoded frame exactly");
        }
    }

    @Test
    void rejectsAWrongToken() throws Exception {
        try (HarnessServer server = HarnessServer.bind(events())) {
            var accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept(10_000);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try (HarnessClient ignored =
                    HarnessClient.connect(server.port(), "not-the-token", 5_000)) {
                ExecutionException e = assertThrows(ExecutionException.class, accepted::get);
                assertTrue(e.getCause() instanceof ProtocolException, String.valueOf(e.getCause()));
                assertTrue(e.getCause().getMessage().contains("token"), e.getCause().getMessage());
            }
        }
    }

    @Test
    void rejectsAMismatchedProtocolVersion() throws Exception {
        try (HarnessServer server = HarnessServer.bind(events())) {
            var accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept(10_000);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            // Hand-rolled hello: a real client cannot claim the wrong version.
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                socket.getOutputStream().write(
                        (FrameCodec.encode(
                                        new Frame.Hello(
                                                server.token(),
                                                Laymark.PROTOCOL_VERSION + 1,
                                                123L))
                                + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                ExecutionException e = assertThrows(ExecutionException.class, accepted::get);
                assertTrue(e.getCause().getMessage().contains("protocol version"),
                        e.getCause().getMessage());
            }
        }
    }

    @Test
    void rejectsAFirstFrameThatIsNotHello() throws Exception {
        try (HarnessServer server = HarnessServer.bind(events())) {
            var accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept(10_000);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                socket.getOutputStream().write(
                        (FrameCodec.encode(new Frame.Heartbeat(1L)) + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                ExecutionException e = assertThrows(ExecutionException.class, accepted::get);
                assertTrue(e.getCause().getMessage().contains("hello"), e.getCause().getMessage());
            }
        }
    }

    @Test
    void bindsLoopbackOnly() throws Exception {
        try (HarnessServer server = HarnessServer.bind(events())) {
            assertTrue(server.port() > 0);
            assertTrue(server.token().length() >= 32, "the nonce is the access control");
        }
    }

    /**
     * The mod fails closed when the runner told it to expect a connection and there is none.
     * Running unmonitored would burn a whole scenario list with no heartbeat and no result.
     */
    @Test
    void clientFailsRatherThanContinuingUnmonitored() throws Exception {
        int deadPort;
        try (HarnessServer server = HarnessServer.bind(events())) {
            deadPort = server.port();
        }
        assertThrows(ProtocolException.class, () -> HarnessClient.connect(deadPort, "t", 500));
    }

    @Test
    void refusesToConnectWithoutAToken() {
        String previousPort = System.getProperty(Laymark.PROPERTY_PORT);
        String previousToken = System.getProperty(Laymark.PROPERTY_TOKEN);
        try {
            System.setProperty(Laymark.PROPERTY_PORT, "1");
            System.clearProperty(Laymark.PROPERTY_TOKEN);
            ProtocolException e = assertThrows(
                    ProtocolException.class,
                    () -> HarnessClient.connectFromSystemProperties(200));
            assertTrue(e.getMessage().contains(Laymark.PROPERTY_TOKEN), e.getMessage());

            System.setProperty(Laymark.PROPERTY_TOKEN, "t");
            System.setProperty(Laymark.PROPERTY_PORT, "not-a-number");
            assertTrue(assertThrows(
                            ProtocolException.class,
                            () -> HarnessClient.connectFromSystemProperties(200))
                    .getMessage()
                    .contains("malformed"));
        } finally {
            restore(Laymark.PROPERTY_PORT, previousPort);
            restore(Laymark.PROPERTY_TOKEN, previousToken);
        }
    }

    @Test
    void launchedByRunnerReflectsTheProperty() {
        String previous = System.getProperty(Laymark.PROPERTY_PORT);
        try {
            System.clearProperty(Laymark.PROPERTY_PORT);
            assertFalse(HarnessClient.launchedByRunner());
            System.setProperty(Laymark.PROPERTY_PORT, "1234");
            assertTrue(HarnessClient.launchedByRunner());
        } finally {
            restore(Laymark.PROPERTY_PORT, previous);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
