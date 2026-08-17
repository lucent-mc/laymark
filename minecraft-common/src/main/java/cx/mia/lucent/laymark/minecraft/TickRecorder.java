package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.TickSample;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Times integrated-server ticks while a capture is open.
 *
 * <p>The channel that catches a mod costing nothing to draw and a great deal to simulate. It also
 * catches the indirect case: client and integrated server share a machine, so a server that runs
 * long steals time from frames without the frame channel being able to say why.
 *
 * <p>Timed by bracketing the tick rather than by reading {@code MinecraftServer#getTickTimesNanos()},
 * which is a rolling window of the last hundred ticks — five seconds at full rate. Sampling that
 * at the end of a thirty-second capture would describe the last sixth of it and quietly present
 * the result as the whole.
 *
 * <p>Runs on the server thread while the harness thread reads, so the same locking discipline as
 * {@link FrameRecorder} applies.
 */
public final class TickRecorder {

    private final Object lock = new Object();
    private final AtomicBoolean recording = new AtomicBoolean();

    private List<TickSample> samples = new ArrayList<>();
    private volatile long startedAtNanos;
    private long tickStartedNanos;

    /** Server thread. */
    public void onTickStart() {
        if (recording.get()) {
            tickStartedNanos = System.nanoTime();
        }
    }

    /** Server thread. */
    public void onTickEnd() {
        if (!recording.get() || tickStartedNanos == 0) {
            return;
        }
        long now = System.nanoTime();
        long duration = now - tickStartedNanos;
        tickStartedNanos = 0;
        if (duration <= 0) {
            return;
        }
        TickSample sample = new TickSample(Math.max(0, now - duration - startedAtNanos), duration);
        synchronized (lock) {
            if (recording.get()) {
                samples.add(sample);
            }
        }
    }

    /** @param originNanos the frame recorder's clock origin, so both series share offsets */
    public void start(long originNanos) {
        synchronized (lock) {
            samples = new ArrayList<>();
        }
        tickStartedNanos = 0;
        startedAtNanos = originNanos;
        recording.set(true);
    }

    public List<TickSample> stop() {
        recording.set(false);
        synchronized (lock) {
            List<TickSample> captured = List.copyOf(samples);
            samples = new ArrayList<>();
            return captured;
        }
    }

    public boolean recording() {
        return recording.get();
    }
}
