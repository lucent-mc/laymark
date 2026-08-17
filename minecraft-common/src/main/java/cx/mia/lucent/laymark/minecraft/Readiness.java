package cx.mia.lucent.laymark.minecraft;

import java.util.List;
import java.util.function.BooleanSupplier;
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
 * actually resolved to.
 *
 * <p>The conditions are <strong>named and enumerated</strong> rather than folded into one
 * predicate, because the barrier is part of the apparatus. {@code hasRenderedAllSections()} is a
 * {@code LevelRenderer} question and renderer-replacing mods reimplement that subsystem, so which
 * condition gated a run — and for how long — is itself a comparison worth being able to make.
 */
public final class Readiness {

    private Readiness() {}

    /** One named thing the barrier waits on. Evaluated on the client thread. */
    public record Condition(String name, BooleanSupplier satisfied) {}

    /**
     * The composite barrier, in the order a world normally satisfies it.
     *
     * <p>Ordered deliberately: the cheap structural checks come first so that a run which is
     * nowhere near ready does not pay for a renderer query on every poll.
     */
    public static List<Condition> worldConditions() {
        return List.of(
                new Condition("player exists", () -> Minecraft.getInstance().player != null),
                new Condition("level exists", () -> Minecraft.getInstance().level != null),
                new Condition("client reported loaded", Readiness::clientLoaded),
                new Condition(
                        "loading screen dismissed",
                        () -> !(Minecraft.getInstance().screen instanceof LevelLoadingScreen)),
                new Condition("all sections built", Readiness::allSectionsBuilt),
                new Condition("renderer built to view distance", Readiness::viewDistanceConverged));
    }

    /** Must be evaluated on the client thread. */
    public static boolean worldIsMeasurable() {
        return worldConditions().stream().allMatch(condition -> condition.satisfied().getAsBoolean());
    }

    private static boolean clientLoaded() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasClientLoaded();
    }

    private static boolean allSectionsBuilt() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.levelRenderer.hasRenderedAllSections();
    }

    private static boolean viewDistanceConverged() {
        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderer renderer = minecraft.levelRenderer;
        return renderer.getLastViewDistance() >= minecraft.options.getEffectiveRenderDistance();
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
