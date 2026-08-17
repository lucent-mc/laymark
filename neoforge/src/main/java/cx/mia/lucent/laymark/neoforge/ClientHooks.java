package cx.mia.lucent.laymark.neoforge;

import cx.mia.lucent.laymark.minecraft.ClientChannels;
import cx.mia.lucent.laymark.minecraft.Harness;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.FlipFrameEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * The trigger points a loader has to provide, and nothing else.
 *
 * <p>What to measure lives in {@code core} and how to make the game do it lives in
 * {@code minecraft-common}; this file exists only because neither of those may name NeoForge. It
 * forwards to {@link ClientChannels} rather than to individual recorders so that adding a channel
 * never means editing this file — which is how a second loader port drifts from the first, and how
 * a channel ends up silently missing on one of them.
 */
final class ClientHooks {

    private final ClientChannels channels;
    private boolean started;

    ClientHooks(ClientChannels channels) {
        this.channels = channels;
    }

    /**
     * The frame boundary.
     *
     * <p>Fired at the buffer flip, which is the moment a frame becomes visible and therefore the
     * only honest place to timestamp one. Everything derived from it — the interval, the render
     * call, the throttle state, the GPU query bracket — is settled here.
     */
    @SubscribeEvent
    public void onFlipFrame(FlipFrameEvent event) {
        channels.onFramePresented();
    }

    @SubscribeEvent
    public void onRenderFramePre(RenderFrameEvent.Pre event) {
        channels.onRenderFrameStart();
    }

    @SubscribeEvent
    public void onRenderFramePost(RenderFrameEvent.Post event) {
        channels.onRenderFrameEnd();
    }

    /**
     * Starts the run once resources are loaded.
     *
     * <p>This fires on every reload, including the ones a run causes, so it is latched. It also
     * fires <em>before</em> {@code isGameLoadFinished()} flips and before the title screen exists,
     * which is why the run's first act is to wait for the client to actually settle rather than
     * trusting the event as a readiness signal.
     */
    @SubscribeEvent
    public void onResourcesLoaded(ClientResourceLoadFinishedEvent event) {
        if (started || !event.isInitial()) {
            return;
        }
        started = true;
        Harness.start(channels, LaymarkNeoForge::report);
    }
}
