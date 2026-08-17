package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.FrameSample;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects frame timings while a capture is open.
 *
 * <p>Measures the <strong>interval between presented frames</strong> — the wall-clock gap from one
 * buffer flip to the next. That is the quantity a player experiences and the one an fps counter
 * reports, and it accounts for everything the client does per frame: input polling, the level
 * update, rendering, the swap, and any wait imposed by a framerate cap.
 *
 * <p>Deliberately not {@code Minecraft#getFrameTimeNs()}, despite the inviting name. That field is
 * assigned from a timer started partway through the frame loop and read before {@code flipFrame},
 * so it covers the render call and excludes the tick, the swap and the limiter. Measured against a
 * real run it read about 55% of the true interval — low by enough to look like a fast machine
 * rather than a broken measurement, and biased towards mods that move cost out of the render call
 * without removing it. It remains a useful second channel for attributing a regression to render
 * versus tick; it is not the headline number.
 *
 * <p>The <em>trigger</em> is a loader concern — NeoForge's {@code FlipFrameEvent} — which is why
 * this class only exposes {@link #onFramePresented}, for a loader module to call.
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

    /**
     * Called once per presented frame, on the client thread.
     *
     * <p>Ignores anything outside an open capture rather than buffering it. A benchmark's samples
     * must come from a window someone declared, or the numbers describe a period nobody defined.
     */
    public void onFramePresented() {
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
        synchronized (lock) {
            if (recording.get()) {
                samples.add(new FrameSample(Math.max(0, previous - startedAtNanos), interval));
            }
        }
    }

    public void start() {
        synchronized (lock) {
            samples = new ArrayList<>();
        }
        previousFrameNanos = 0;
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
}
