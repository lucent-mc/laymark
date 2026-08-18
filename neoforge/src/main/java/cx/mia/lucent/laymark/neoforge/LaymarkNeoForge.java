package cx.mia.lucent.laymark.neoforge;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.protocol.HarnessClient;
import cx.mia.lucent.laymark.core.protocol.ProtocolException;
import cx.mia.lucent.laymark.core.scenario.ScenarioConfigFile;
import cx.mia.lucent.laymark.minecraft.ClientChannels;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge entry point.
 *
 * <p>Bootstrap, lifecycle wiring and the frame trigger only. No benchmark policy lives here --
 * that is {@code core}'s job, and the vanilla implementation is {@code minecraft-common}'s.
 */
@Mod(Laymark.ID)
public final class LaymarkNeoForge {

    private static final Logger LOG = LoggerFactory.getLogger("laymark");

    /** How long to wait for the runner that just launched us. It is already listening. */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private static volatile HarnessClient client;

    /**
     * Shared by every trigger and the run sequence.
     *
     * <p>Held here rather than created per run because the trigger is registered once for the
     * life of the process, and channels swapped underneath them would drop the samples in flight.
     */
    private static final ClientChannels CHANNELS = new ClientChannels();

    public LaymarkNeoForge(IEventBus modBus, ModContainer container) {
        String version = container.getModInfo().getVersion().toString();
        ensureConfig();

        if (!HarnessClient.launchedByRunner()) {
            // A human started the game. Laymark measures nothing on its own, but it does the one
            // thing that makes it usable: put the runner where the human is already looking.
            extractRunner(version);
            return;
        }

        try {
            client = HarnessClient.connectFromSystemProperties(CONNECT_TIMEOUT_MILLIS);
        } catch (ProtocolException e) {
            // Fail closed. The runner set the port property, so it is waiting for us; carrying on
            // would burn an entire scenario list with no heartbeat and no terminal result, and
            // the operator would find out only when the timeout expired.
            LOG.error("Laymark {} could not reach its runner; failing the run", version, e);
            throw e;
        }

        LOG.info("Laymark {} connected to its runner (protocol v{})", version, Laymark.PROTOCOL_VERSION);

        // The game bus, not the mod bus: frame and resource events are dispatched there.
        NeoForge.EVENT_BUS.register(new ClientHooks(CHANNELS));

        // Closing the socket is how the runner learns the game is gone, so make sure it happens
        // even on a path that skips an orderly shutdown.
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    HarnessClient open = client;
                                    if (open != null) {
                                        open.close();
                                    }
                                },
                                "laymark-shutdown"));
    }

    /** Creates the same commented config the runner creates, without ever replacing user edits. */
    private static void ensureConfig() {
        java.nio.file.Path gameDirectory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        try {
            java.nio.file.Path config = ScenarioConfigFile.ensureExists(gameDirectory);
            LOG.info("Laymark scenario config is at {}", config);
        } catch (java.io.IOException e) {
            // A human launch should still reach the title screen. A runner launch will fail with
            // the same path when it attempts to read the required plan, but the early log retains
            // the filesystem cause that made creation fail.
            LOG.warn("Laymark could not create {}", ScenarioConfigFile.path(gameDirectory), e);
        }
    }

    /**
     * Puts the embedded runner at the instance root, once.
     *
     * <p>Someone who found Laymark on a mod site has the mod and nothing else; the runner they
     * need is riding inside it as an inert resource (§3). Extracted to the instance root because
     * that is the folder they already have open, and <strong>never deleted or overwritten</strong>
     * — an existing jar is theirs, whatever version it is.
     */
    private static void extractRunner(String version) {
        java.nio.file.Path target =
                net.neoforged.fml.loading.FMLPaths.GAMEDIR
                        .get()
                        .resolve("laymark-runner-" + version + ".jar");
        if (java.nio.file.Files.exists(target)) {
            LOG.info("Laymark {} loaded; runner already at {}", version, target.getFileName());
            return;
        }
        try (var embedded = LaymarkNeoForge.class.getResourceAsStream("/laymark/runner.jar")) {
            if (embedded == null) {
                LOG.warn("Laymark {} has no embedded runner; this is a dev build", version);
                return;
            }
            java.nio.file.Files.copy(embedded, target);
            LOG.info(
                    "Laymark {} extracted its runner to {} -- close the game and run it with"
                            + " java -jar, or double-click it",
                    version,
                    target.getFileName());
        } catch (java.io.IOException e) {
            LOG.warn("Laymark {} could not extract its runner", version, e);
        }
    }

    /** @return the live channel, or null when the game was not runner-launched */
    public static HarnessClient channel() {
        return client;
    }

    /** Convenience for the phases that will report progress once they exist. */
    public static void report(Frame frame) {
        HarnessClient open = client;
        if (open != null) {
            open.send(frame);
        }
    }
}
