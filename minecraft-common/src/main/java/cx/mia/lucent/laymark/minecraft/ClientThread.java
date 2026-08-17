package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/**
 * Runs work on the client thread and waits for it.
 *
 * <p>The harness sequence runs on its own thread; almost everything it wants to do must happen on
 * the client thread. Option setters assert it outright — {@code Window#updateVsync} begins with
 * {@code RenderSystem.assertOnRenderThread()} — and world creation is only safe there.
 *
 * <p>Every call takes a timeout and fails rather than blocking forever. A hung client thread is
 * the normal shape of a broken mod stack, and an unattended benchmark that waits indefinitely for
 * it produces no result and no explanation.
 */
public final class ClientThread {

    private ClientThread() {}

    /** Long enough for a resource reload on a heavy pack, short enough to still be a timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public static void run(String what, Runnable work) {
        call(what, DEFAULT_TIMEOUT, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T call(String what, Supplier<T> work) {
        return call(what, DEFAULT_TIMEOUT, work);
    }

    /**
     * @throws HarnessException if the work throws, times out, or the wait is interrupted
     */
    public static <T> T call(String what, Duration timeout, Supplier<T> work) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            // Already there. Going through the queue would deadlock: submit() only completes when
            // the client thread drains its tasks, and the client thread is us, waiting.
            return work.get();
        }
        CompletableFuture<T> future = minecraft.submit(work::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new HarnessException(
                    what + " did not finish within " + timeout.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HarnessException harness) {
                throw harness;
            }
            throw new HarnessException(what + " failed on the client thread", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException(what + " was interrupted", e);
        }
    }

    /**
     * Polls a condition from the harness thread until it holds or the deadline passes.
     *
     * <p>The condition is evaluated on the client thread, because everything worth asking about is
     * only safe to read there.
     *
     * @param stableTicks how many consecutive successful polls are required. Readiness signals
     *     flicker — a renderer reports all sections built for one frame and then receives another
     *     chunk — so a single true is not evidence of a settled state.
     * @return false if the deadline passed first
     */
    public static boolean await(
            String what, Duration timeout, Duration interval, int stableTicks, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int consecutive = 0;
        while (System.nanoTime() < deadline) {
            consecutive = Boolean.TRUE.equals(call(what, condition)) ? consecutive + 1 : 0;
            if (consecutive >= stableTicks) {
                return true;
            }
            sleep(interval);
        }
        return false;
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("wait was interrupted", e);
        }
    }
}
