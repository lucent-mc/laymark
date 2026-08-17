package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.api.ChunkyAPI;

/**
 * Pre-generates terrain through Chunky, so a streaming scenario can stand alone.
 *
 * <p>Everything here follows the verified control-surface notes
 * (docs/spark-chunky-control-surfaces-research.md), and the traps are Chunky's, not hypotheses:
 *
 * <ul>
 *   <li><strong>Gate on {@code version() == 0}</strong> — the API's whole stability contract.
 *   <li><strong>Subscribe before {@code startTask}, never during</strong> — the event bus is an
 *       unsynchronised map written from the main thread and read from task threads.
 *   <li><strong>The canonical world id, always</strong> — a task started as {@code "overworld"}
 *       leaks in Chunky's task map forever and blocks every later start this session.
 *   <li><strong>{@code GenerationCompleteEvent} alone means nothing.</strong> It fires on every
 *       exit path — cancel, pause, interrupt, empty selection — with up to 50 chunk operations
 *       still in flight. Genuine completion is {@code GenerationProgressEvent.complete() == true}
 *       <em>followed by</em> the complete event.
 *   <li><strong>Chunky does not save the world.</strong> Completion means Chunky stopped working,
 *       not that region files are written; the forced save afterwards is ours.
 * </ul>
 *
 * <p>Loaded only behind {@link #unavailableReason()}: Chunky is compileOnly, and this class is
 * never touched when the mod is absent.
 */
final class ChunkyBridge {

    private ChunkyBridge() {}

    private static final String OVERWORLD = "minecraft:overworld";

    /** The one API version whose behaviour the wiki guarantees. */
    private static final int SUPPORTED_API_VERSION = 0;

    /** Why pre-generation is unavailable, or null when it can be used. */
    static String unavailableReason() {
        try {
            Class.forName("org.popcraft.chunky.api.ChunkyAPI");
        } catch (ClassNotFoundException e) {
            return "Chunky is not installed";
        }
        try {
            ChunkyAPI api = ChunkyProvider.get().getApi();
            if (api.version() != SUPPORTED_API_VERSION) {
                return "Chunky API version " + api.version() + " is not the supported "
                        + SUPPORTED_API_VERSION;
            }
            return null;
        } catch (RuntimeException | LinkageError e) {
            return "Chunky is present but unreachable: " + e.getMessage();
        }
    }

    /**
     * Generates a square of terrain around a block position and waits for it to be on disk.
     *
     * <p>A square, deliberately larger than the send disc it exists to cover: generation cost off
     * the measured path is cheap, and a footprint that under-covers turns the streaming capture
     * into part-generation — the exact contamination this bridge exists to prevent.
     */
    static void pregenerate(double centerX, double centerZ, int radiusBlocks, Duration timeout) {
        ChunkyAPI api = ChunkyProvider.get().getApi();

        AtomicBoolean genuinelyComplete = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        // Before startTask, per the bus's threading rules. There is no unsubscribe on the API;
        // stale listeners survive per session, so they must tolerate later tasks -- both check
        // the latch they belong to via capture.
        api.onGenerationProgress(
                progress -> {
                    if (progress.complete()) {
                        genuinelyComplete.set(true);
                    }
                });
        api.onGenerationComplete(unused -> finished.countDown());

        if (api.isRunning(OVERWORLD)) {
            throw new HarnessException("a Chunky task is already running; refusing to share it");
        }
        // "square" and "concentric" are canonical names -- an unknown shape silently becomes a
        // square and an unknown pattern a region iterator, so only canonical spellings are safe.
        if (!api.startTask(OVERWORLD, "square", centerX, centerZ, radiusBlocks, radiusBlocks, "concentric")) {
            throw new HarnessException("Chunky refused to start the pre-generation task");
        }

        try {
            if (!finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                api.cancelTask(OVERWORLD);
                throw new HarnessException(
                        "pre-generation did not finish within " + timeout.toSeconds() + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            api.cancelTask(OVERWORLD);
            throw new HarnessException("interrupted while pre-generating");
        }

        // The complete event fires on every exit path, including a zero-chunk selection where no
        // progress event ever ran. Only complete()==true beforehand means the work was done.
        if (!genuinelyComplete.get()) {
            throw new HarnessException(
                    "Chunky reported completion without finishing -- cancelled, interrupted, or"
                            + " an empty selection");
        }

        // Chunky stopped working; the region files are not necessarily written. Force the save,
        // on the server thread, and wait for it.
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw new HarnessException("no integrated server to save the pre-generated terrain");
        }
        server.submit(() -> server.saveAllChunks(true, true, true)).join();
    }
}
