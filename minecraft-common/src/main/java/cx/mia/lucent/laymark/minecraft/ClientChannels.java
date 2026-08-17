package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.Throttle;
import net.minecraft.client.Minecraft;

/**
 * The measurement channels, behind the handful of callbacks a loader has to drive.
 *
 * <p>Exists so a loader module needs to know about four events and nothing else. Without it every
 * new channel would mean editing the NeoForge adapter and, later, the Fabric one — which is how
 * two ports drift apart, and how a channel ends up silently missing on one of them.
 *
 * <p>The split is deliberate: this class reads vanilla state at the trigger points, and the
 * recorders it delegates to hold no Minecraft types at all, so the rules deciding which samples
 * count stay testable without a game.
 */
public final class ClientChannels {

    private final FrameRecorder frames = new FrameRecorder();
    private final GpuTimer gpu = new GpuTimer();
    private final SparkChannel spark = new SparkChannel();

    /** Buffer flip: the frame just presented is complete and its GPU query can be closed. */
    public void onFramePresented() {
        Minecraft minecraft = Minecraft.getInstance();
        long offset = frames.currentOffsetNanos();
        frames.onFramePresented(minecraft.getFrameTimeNs(), throttle());
        if (frames.recording()) {
            gpu.onFramePresented(offset);
        }
    }

    public void onRenderFrameStart() {
        frames.onRenderFrameStart();
    }

    public void onRenderFrameEnd() {
        frames.onRenderFrameEnd();
    }



    /**
     * Vanilla's throttle reason, translated.
     *
     * <p>Read per frame because the throttles that matter are time-based: an input-free session
     * drops to 30 fps after a minute and 10 after ten, which lands inside a long capture and
     * outside a short one. A check at either end of the window would miss it.
     */
    private static Throttle throttle() {
        return PresetOptions.throttleOf(Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason());
    }

    FrameRecorder frames() {
        return frames;
    }

    SparkChannel spark() {
        return spark;
    }

    GpuTimer gpu() {
        return gpu;
    }
}
