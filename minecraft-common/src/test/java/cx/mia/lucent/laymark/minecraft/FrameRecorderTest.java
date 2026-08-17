package cx.mia.lucent.laymark.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.harness.FrameSample;
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
        recorder.onFramePresented();
        assertFalse(recorder.recording());

        recorder.start();
        recorder.onFramePresented();
        recorder.onFramePresented();
        List<FrameSample> captured = recorder.stop();

        recorder.onFramePresented();
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
        recorder.onFramePresented();
        assertTrue(recorder.stop().isEmpty());
    }

    @Test
    void producesOneSampleFewerThanTheFramesItSaw() {
        recorder.start();
        for (int i = 0; i < 10; i++) {
            recorder.onFramePresented();
        }
        assertEquals(9, recorder.stop().size());
    }

    @Test
    void startingAgainDiscardsThePreviousWindowAndItsPredecessor() {
        recorder.start();
        recorder.onFramePresented();
        recorder.onFramePresented();

        recorder.start();
        recorder.onFramePresented();

        assertTrue(
                recorder.stop().isEmpty(),
                "a restarted window must not pair its first frame with the old one");
    }

    @Test
    void offsetsAreOrderedFromTheStartOfTheWindow() {
        recorder.start();
        for (int i = 0; i < 4; i++) {
            recorder.onFramePresented();
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
                                recorder.onFramePresented();
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
