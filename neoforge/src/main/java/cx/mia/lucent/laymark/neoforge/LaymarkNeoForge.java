package cx.mia.lucent.laymark.neoforge;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.protocol.Frame;
import cx.mia.lucent.laymark.core.protocol.HarnessClient;
import cx.mia.lucent.laymark.core.protocol.ProtocolException;
import net.neoforged.bus.api.IEventBus;
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

    public LaymarkNeoForge(IEventBus modBus, ModContainer container) {
        String version = container.getModInfo().getVersion().toString();

        if (!HarnessClient.launchedByRunner()) {
            // A human started the game. Laymark does nothing on its own, and 0.x will extract
            // the runner here and say so rather than sitting silent.
            LOG.info("Laymark {} loaded; not runner-launched, so nothing to do", version);
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
