package cx.mia.lucent.laymark.minecraft;

import com.mojang.blaze3d.systems.TimerQuery;
import cx.mia.lucent.laymark.core.harness.GpuSample;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Times each frame on the GPU using vanilla's own timer queries.
 *
 * <p>A GPU-bound stack and a CPU-bound one can post identical frame intervals. Only this channel
 * says which, and it is the number a shader or culling mod is actually competing on.
 *
 * <p><strong>Never blocks.</strong> A timer query resolves some frames after the work it timed,
 * and asking for the result early means waiting for the GPU to catch up — which would stall the
 * pipeline and change the thing being measured. Outstanding queries sit in a queue and are
 * harvested on later frames, once {@code isDone()} says the answer is already there.
 *
 * <p>Entirely optional. Timer queries are a driver and backend capability, and if anything about
 * them fails the channel switches itself off for the rest of the run and records why. A missing
 * GPU series is a gap in a diagnostic; a run aborted over one would be a benchmark that refuses to
 * measure a machine because it could not measure it twice.
 *
 * <p>Client thread only — every {@code TimerQuery} entry point asserts it.
 */
final class GpuTimer {

    /**
     * How many unresolved queries to allow before dropping the oldest.
     *
     * <p>Bounded because the queue only drains when the GPU finishes, and a GPU that has fallen
     * far behind is exactly the situation where an unbounded queue would grow without limit.
     */
    private static final int MAX_OUTSTANDING = 16;

    private record Pending(long offsetNanos, TimerQuery.FrameProfile profile) {}

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final List<GpuSample> samples = new ArrayList<>();

    private boolean open;
    private long openedAtOffset;
    private String unavailable;

    /** Why the channel is off, or null while it is working. */
    String unavailableReason() {
        return unavailable;
    }

    /**
     * Closes the previous frame's query, harvests whatever has resolved, and opens the next.
     *
     * <p>Called at the buffer flip, so a query brackets exactly the interval the frame channel
     * reports.
     *
     * @param offsetNanos of the frame being closed, on the frame recorder's clock
     */
    void onFramePresented(long offsetNanos) {
        if (unavailable != null) {
            return;
        }
        try {
            if (open) {
                pending.addLast(new Pending(openedAtOffset, TimerQuery.getInstance().endProfile()));
                open = false;
            }
            harvest();
            while (pending.size() > MAX_OUTSTANDING) {
                pending.removeFirst().profile().cancel();
            }
            // Vanilla drives the same singleton when its GPU debug readout is open. It checks
            // isRecording() before starting, so yielding here keeps the two from colliding rather
            // than racing to see who calls endProfile on the other's query.
            if (!TimerQuery.getInstance().isRecording()) {
                TimerQuery.getInstance().beginProfile();
                open = true;
                openedAtOffset = offsetNanos;
            }
        } catch (RuntimeException | LinkageError e) {
            disable(e.toString());
        }
    }

    private void harvest() {
        while (!pending.isEmpty() && pending.peekFirst().profile().isDone()) {
            Pending done = pending.removeFirst();
            long duration = done.profile().get();
            // A cancelled query reports -1 and an unresolved one 0; neither is a measurement.
            if (duration > 0) {
                samples.add(new GpuSample(done.offsetNanos(), duration));
            }
        }
    }

    void start() {
        cancelOutstanding();
        samples.clear();
        unavailable = null;
    }

    /**
     * Ends the window and returns what resolved in time.
     *
     * <p>The last few frames' queries are still in flight and are cancelled rather than waited
     * for. Losing them costs a handful of samples out of thousands; waiting would mean the capture
     * ends on a GPU stall.
     */
    List<GpuSample> stop() {
        if (unavailable == null) {
            try {
                if (open) {
                    TimerQuery.getInstance().endProfile().cancel();
                    open = false;
                }
                harvest();
            } catch (RuntimeException | LinkageError e) {
                disable(e.toString());
            }
        }
        cancelOutstanding();
        List<GpuSample> captured = List.copyOf(samples);
        samples.clear();
        return captured;
    }

    private void cancelOutstanding() {
        while (!pending.isEmpty()) {
            try {
                pending.removeFirst().profile().cancel();
            } catch (RuntimeException | LinkageError ignored) {
                // Already closing down; a failure to release a query changes nothing here.
            }
        }
        open = false;
    }

    private void disable(String reason) {
        unavailable = reason;
        open = false;
        pending.clear();
    }
}
