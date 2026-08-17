package cx.mia.lucent.laymark.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * Decides when a world is actually ready to be measured.
 *
 * <p>Vanilla has its own answer — {@code LevelLoadTracker} — and it is not usable here. It gives
 * up after thirty seconds of waiting for the client to load chunks and lets the player in anyway,
 * logging that it did so. For a benchmark that escape hatch inverts the result: the runs that hit
 * it are the slow ones, so the stacks that struggle most are exactly the ones that would start
 * measuring on an unbuilt world and post good numbers. Its live instance is private in any case.
 *
 * <p>What replaces it is a conjunction of public signals, each of which vanilla's own tracker uses
 * internally, plus one it does not: that the renderer has built out to the distance the options
 * actually resolved to. Held stable across several polls, because these flicker — the renderer
 * reports everything built, then another chunk arrives.
 */
public final class Readiness {

    private Readiness() {}

    /** Must be evaluated on the client thread. */
    public static boolean worldIsMeasurable() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || !connection.hasClientLoaded()) {
            return false;
        }
        if (minecraft.screen instanceof LevelLoadingScreen) {
            return false;
        }
        LevelRenderer renderer = minecraft.levelRenderer;
        return renderer.hasRenderedAllSections()
                && renderer.getLastViewDistance() >= minecraft.options.getEffectiveRenderDistance();
    }

    /**
     * Whether the client has finished booting and is sitting idle with no world.
     *
     * <p>The starting state a run has to begin from, and worth asserting rather than assuming: a
     * resource reload still in flight would otherwise overlap the first scenario.
     */
    public static boolean idleAtMenu() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.isGameLoadFinished()
                && minecraft.getOverlay() == null
                && minecraft.level == null;
    }
}
