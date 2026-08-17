package cx.mia.lucent.laymark.neoforge;

import cx.mia.lucent.laymark.minecraft.FrameRecorder;
import cx.mia.lucent.laymark.minecraft.Harness;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.FlipFrameEvent;

/**
 * The two things a loader has to provide: when the run may start, and when a frame happened.
 *
 * <p>Nothing else belongs here. What to measure lives in {@code core} and how to make the game do
 * it lives in {@code minecraft-common}; this file exists because neither of those may name
 * NeoForge.
 */
final class ClientHooks {

    private final FrameRecorder recorder;
    private boolean started;

    ClientHooks(FrameRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * The frame trigger.
     *
     * <p>Fired at the buffer flip, which is the moment a frame becomes visible and therefore the
     * only honest place to timestamp one. The recorder derives the interval between consecutive
     * flips; this hook exists solely because {@code core} and {@code minecraft-common} may not
     * name NeoForge.
     */
    @SubscribeEvent
    public void onFlipFrame(FlipFrameEvent event) {
        recorder.onFramePresented();
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
        Harness.start(recorder, LaymarkNeoForge::report);
    }
}
