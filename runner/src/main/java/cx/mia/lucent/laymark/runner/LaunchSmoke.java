package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.runner.launch.GameProcess;
import cx.mia.lucent.laymark.runner.launch.HostPlatform;
import cx.mia.lucent.laymark.runner.launch.LaunchAssembly;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import cx.mia.lucent.laymark.runner.launch.OfflineIdentity;
import cx.mia.lucent.laymark.runner.transport.HarnessServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Slice 2's acceptance criterion, end to end: start the real instance, wait for the harness to
 * connect, confirm the handshake, then shut it down.
 *
 * <p>This is the first thing in the project that cannot be tier-1 tested. It launches a real
 * game, so it is driven by hand rather than by CI — which is also why the release gate keeps a
 * manual in-game check: a machine running CI is a machine whose thermal and load state makes it
 * unfit for the benchmarking it would also be doing.
 *
 * <p>It measures nothing. It proves the path exists.
 */
public final class LaunchSmoke {

    private LaunchSmoke() {}

    public static void run(ModrinthInstance instance, Path outputDirectory, Duration timeout)
            throws IOException {

        instance.requireUsable();
        Files.createDirectories(outputDirectory);

        Path events = outputDirectory.resolve("events.jsonl");
        Path stdout = outputDirectory.resolve("game-stdout.log");
        Path stderr = outputDirectory.resolve("game-stderr.log");

        // Bind before launching. The port then exists before anything could connect to it, so
        // there is no startup race and nothing to discover.
        try (HarnessServer server = HarnessServer.bind(events)) {

            List<String> argv =
                    LaunchAssembly.assemble(
                            instance.descriptor(),
                            instance.layout(),
                            HostPlatform.current(),
                            OfflineIdentity.of("LaymarkProbe"),
                            server.port(),
                            server.token());

            System.out.printf(
                    "listening on 127.0.0.1:%d%nlaunching %s%n  %d arguments, working dir %s%n",
                    server.port(),
                    instance.javaExecutable(),
                    argv.size(),
                    instance.gameDirectory());

            try (GameProcess game =
                    GameProcess.start(
                            instance.javaExecutable(),
                            argv,
                            instance.gameDirectory(),
                            stdout,
                            stderr)) {

                System.out.printf("game pid %d; waiting for the handshake%n", game.pid());

                HarnessServer.Session session;
                try {
                    session = server.accept((int) timeout.toMillis());
                } catch (IOException e) {
                    // A dead child explains a missing handshake far better than a socket timeout.
                    if (!game.isAlive()) {
                        throw new LaunchException(
                                "the game exited before connecting; see " + stderr, e);
                    }
                    throw new LaunchException(
                            "the game is running but never connected; see " + stdout, e);
                }

                if (session.pid() != game.pid()) {
                    throw new LaunchException(
                            "handshake came from pid " + session.pid()
                                    + " but the launched process is " + game.pid()
                                    + "; something else reached the port first");
                }
                System.out.printf("handshake ok from pid %d%n", session.pid());

                // Nothing else is expected yet. Read whatever the harness sends until it stops,
                // on a separate thread so a silent harness cannot wedge the shutdown.
                Thread pump =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            try {
                                                server.pump(session, LaunchSmoke::describe);
                                            } catch (IOException | RuntimeException ignored) {
                                                // The socket closing is how a run ends.
                                            }
                                        });

                Thread.sleep(Duration.ofSeconds(5));
                System.out.println("shutting the game down");
                game.terminate(Duration.ofSeconds(15));
                pump.join(Duration.ofSeconds(5));

                System.out.printf("events recorded at %s%n", events);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LaunchException("interrupted during the smoke run", e);
            }
        }
    }

    private static void describe(Frame frame) {
        System.out.println("  <- " + frame);
    }
}
