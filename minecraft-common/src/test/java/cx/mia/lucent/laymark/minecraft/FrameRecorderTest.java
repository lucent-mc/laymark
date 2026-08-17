package cx.mia.lucent.laymark.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.Throttle;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 despite living beside Minecraft: the recorder is deliberately free of game types, so the
 * rules deciding which frames count are testable without launching anything.
 */
class FrameRecorderTest {

    private final FrameRecorder recorder = new FrameRecorder();

    /**
     * Frames outside a declared window are dropped rather than buffered. Otherwise the samples
     * describe a period nobody defined -- including world load, which is not the measurement.
     */
    @Test
    void ignoresFramesOutsideACapture() {
        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        assertFalse(recorder.recording());

        recorder.start();
        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        List<FrameSample> captured = recorder.stop();

        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        assertEquals(1, captured.size(), "two presents bound one interval");
        assertTrue(recorder.stop().isEmpty(), "a closed window keeps nothing");
    }

    /**
     * The first present of a window has no predecessor inside it. Pairing it with the last frame
     * before the capture would charge the scenario for however long setup took.
     */
    @Test
    void theFirstFrameOfAWindowYieldsNoInterval() {
        recorder.start();
        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        assertTrue(recorder.stop().isEmpty());
    }

    @Test
    void producesOneSampleFewerThanTheFramesItSaw() {
        recorder.start();
        for (int i = 0; i < 10; i++) {
            recorder.onFramePresented(4_000_000L, Throttle.NONE);
        }
        assertEquals(9, recorder.stop().size());
    }

    @Test
    void startingAgainDiscardsThePreviousWindowAndItsPredecessor() {
        recorder.start();
        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        recorder.onFramePresented(4_000_000L, Throttle.NONE);

        recorder.start();
        recorder.onFramePresented(4_000_000L, Throttle.NONE);

        assertTrue(
                recorder.stop().isEmpty(),
                "a restarted window must not pair its first frame with the old one");
    }

    /**
     * The render call is recorded beside the interval rather than instead of it. It is a strict
     * subset -- vanilla starts that timer after the frame's client ticks and reads it before the
     * swap -- so a report that confused the two would understate every frame.
     */
    @Test
    void carriesTheSubChannelsAlongsideTheInterval() {
        recorder.start();
        recorder.onRenderFrameStart();
        recorder.onRenderFrameEnd();
        recorder.onFramePresented(4_000_000L, Throttle.NONE);
        recorder.onFramePresented(4_500_000L, Throttle.NONE);

        FrameSample sample = recorder.stop().get(0);
        assertEquals(4_500_000L, sample.renderCallNanos());
        assertTrue(sample.submitNanos() > 0, "the submit bracket should have been measured");
        assertTrue(
                sample.intervalNanos() > 0,
                "the interval is measured independently of what vanilla reports");
    }

    /**
     * A throttle that engages mid-window is only visible if it is recorded per frame.
     *
     * <p>Spaced in real time on purpose: two back-to-back {@code System.nanoTime()} calls can
     * return the same value on Windows, and the recorder correctly drops a zero-length interval —
     * so presenting frames in a tight loop makes which samples survive a matter of luck.
     */
    @Test
    void recordsTheThrottleStateOfEachFrame() throws InterruptedException {
        recorder.start();
        for (int i = 0; i < 3; i++) {
            recorder.onFramePresented(1, Throttle.NONE);
            Thread.sleep(1);
        }
        recorder.onFramePresented(1, Throttle.SHORT_AFK);

        List<FrameSample> captured = recorder.stop();
        assertEquals(3, captured.size());
        assertEquals(Throttle.NONE, captured.get(0).throttle());
        assertEquals(Throttle.SHORT_AFK, captured.get(captured.size() - 1).throttle());
    }

    @Test
    void offsetsAreOrderedFromTheStartOfTheWindow() {
        recorder.start();
        for (int i = 0; i < 4; i++) {
            recorder.onFramePresented(4_000_000L, Throttle.NONE);
        }
        List<FrameSample> captured = recorder.stop();
        for (int i = 1; i < captured.size(); i++) {
            assertTrue(captured.get(i - 1).offsetNanos() <= captured.get(i).offsetNanos());
        }
    }

    /**
     * Frames arrive on the client thread while the harness thread ends the window. Losing a sample
     * would be tolerable; a {@code ConcurrentModificationException} mid-run would not.
     */
    @Test
    void survivesFramesArrivingWhileTheWindowCloses() throws InterruptedException {
        recorder.start();
        CountDownLatch running = new CountDownLatch(1);
        Thread producer =
                new Thread(
                        () -> {
                            running.countDown();
                            for (int i = 0; i < 200_000; i++) {
                                recorder.onFramePresented(4_000_000L, Throttle.NONE);
                            }
                        });
        producer.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));

        List<FrameSample> captured = recorder.stop();
        producer.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(recorder.recording());
        assertTrue(captured.size() < 200_000);
    }
}
