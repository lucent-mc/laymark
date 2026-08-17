package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.Throttle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects per-frame timings while a capture is open.
 *
 * <p>The headline quantity is the <strong>interval between presented frames</strong> — the
 * wall-clock gap from one buffer flip to the next. That is what a player experiences and what an
 * fps counter reports, and it accounts for everything the client does per frame: input polling,
 * the client ticks executed that frame, rendering, the swap, and any wait imposed by a cap.
 *
 * <p>Deliberately not {@code Minecraft#getFrameTimeNs()} as the headline, despite the inviting
 * name. That field is assigned from a timer started after the frame's client ticks have already
 * run and read before {@code flipFrame}, so it excludes the tick, the swap and the limiter.
 * Measured against a real run it read about 55% of the true interval — low by enough to look like
 * a fast machine rather than a broken measurement, and blind to any mod whose cost sits in the
 * client tick. It is recorded as its own channel, where that narrowness is the point.
 *
 * <p>The <em>triggers</em> are loader concerns, which is why this class only exposes the three
 * callbacks a loader module drives.
 *
 * <p>Written from the client thread and read from the harness thread, so state that crosses is
 * either atomic or copied under the lock.
 */
public final class FrameRecorder {

    /** Guards the sample list. Held only for an add, which is why a plain lock is affordable. */
    private final Object lock = new Object();

    private final AtomicBoolean recording = new AtomicBoolean();
    private List<FrameSample> samples = new ArrayList<>();
    private volatile long startedAtNanos;

    /** Timestamp of the previous presented frame, or 0 when the window has not seen one yet. */
    private long previousFrameNanos;

    /** Set at {@code RenderFrameEvent.Pre} and consumed at {@code .Post}. */
    private long submitStartedNanos;

    private long lastSubmitNanos;

    /** Opens the render-submission bracket. Client thread. */
    public void onRenderFrameStart() {
        submitStartedNanos = System.nanoTime();
    }

    /** Closes the render-submission bracket. Client thread. */
    public void onRenderFrameEnd() {
        if (submitStartedNanos != 0) {
            lastSubmitNanos = System.nanoTime() - submitStartedNanos;
            submitStartedNanos = 0;
        }
    }

    /**
     * Called once per presented frame, on the client thread.
     *
     * <p>Ignores anything outside an open capture rather than buffering it. A benchmark's samples
     * must come from a window someone declared, or the numbers describe a period nobody defined.
     *
     * @param renderCallNanos vanilla's own render-section timing for the frame just presented
     * @param throttle read here and judged later, so the hot path performs only a field read
     */
    public void onFramePresented(long renderCallNanos, Throttle throttle) {
        if (!recording.get()) {
            return;
        }
        long now = System.nanoTime();
        long previous = previousFrameNanos;
        previousFrameNanos = now;
        if (previous == 0) {
            // The first frame of a window has no predecessor inside it. Pairing it with whatever
            // came before the capture would charge the scenario for the gap since the last frame
            // of setup, which is arbitrarily long.
            return;
        }
        long interval = now - previous;
        if (interval <= 0) {
            return;
        }
        FrameSample sample =
                new FrameSample(
                        Math.max(0, previous - startedAtNanos),
                        interval,
                        Math.max(0, renderCallNanos),
                        Math.max(0, lastSubmitNanos),
                        throttle == null ? Throttle.NONE : throttle);
        synchronized (lock) {
            if (recording.get()) {
                samples.add(sample);
            }
        }
    }

    public void start() {
        synchronized (lock) {
            samples = new ArrayList<>();
        }
        previousFrameNanos = 0;
        submitStartedNanos = 0;
        lastSubmitNanos = 0;
        startedAtNanos = System.nanoTime();
        recording.set(true);
    }

    /** Closes the window and returns everything it caught. */
    public List<FrameSample> stop() {
        recording.set(false);
        synchronized (lock) {
            List<FrameSample> captured = List.copyOf(samples);
            samples = new ArrayList<>();
            return captured;
        }
    }

    public boolean recording() {
        return recording.get();
    }

    /** The clock origin of the open window, so other channels can share its offsets. */
    long startedAtNanos() {
        return startedAtNanos;
    }

    /** Where the frame about to be recorded sits in the window, for channels resolved later. */
    long currentOffsetNanos() {
        long previous = previousFrameNanos;
        return previous == 0 ? 0 : Math.max(0, previous - startedAtNanos);
    }
}
