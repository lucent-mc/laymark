package cx.mia.lucent.laymark.neoforge;

import cx.mia.lucent.laymark.core.Laymark;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge entry point.
 *
 * <p>This module holds only bootstrap, lifecycle wiring, mod inventory and the frame trigger.
 * No benchmark policy lives here -- that is {@code core}'s job, and the vanilla implementation
 * is {@code minecraft-common}'s.
 */
@Mod(Laymark.ID)
public final class LaymarkNeoForge {

    private static final Logger LOG = LoggerFactory.getLogger("laymark");

    public LaymarkNeoForge(IEventBus modBus, ModContainer container) {
        // The launch fact: a system property set by the runner on the command line it builds.
        // Absent means a human started the game, and 0.x will extract the runner and warn.
        boolean runnerLaunched = System.getProperty(Laymark.PROPERTY_PORT) != null;
        LOG.info(
                "Laymark {} loaded (protocol v{}); runner-launched: {}",
                container.getModInfo().getVersion(),
                Laymark.PROTOCOL_VERSION,
                runnerLaunched);
    }
}
