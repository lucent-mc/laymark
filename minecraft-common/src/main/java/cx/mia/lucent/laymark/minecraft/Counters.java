package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.MemorySnapshot;
import cx.mia.lucent.laymark.core.harness.WorkCounters;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Point-in-time readings of how much work has been done and what the heap looks like.
 *
 * <p>Read at each end of a capture and reported as both endpoints, so a reader can see the
 * absolute state as well as the change. Timings alone cannot tell a fast stack from a lazy one: a
 * mod that draws fewer sections posts better frame times honestly, and whether that is an
 * optimisation or a downgrade is a judgement only a reader with the work counts can make.
 *
 * <p>Heap and GC come straight from the JVM rather than from a profiling mod. They are properties
 * of the process, not of Minecraft, so nothing has to be installed to read them and nothing extra
 * runs inside the process being measured.
 */
final class Counters {

    private Counters() {}

    /** Client thread — {@code LevelRenderer} and the chunk sources are not safe to read off it. */
    static WorkCounters work() {
        Minecraft minecraft = Minecraft.getInstance();

        int renderedSections = minecraft.levelRenderer.countRenderedSections();
        int clientChunks =
                minecraft.level == null ? 0 : minecraft.level.getChunkSource().getLoadedChunksCount();

        MinecraftServer server = minecraft.getSingleplayerServer();
        int serverChunks = 0;
        if (server != null) {
            for (var level : server.getAllLevels()) {
                serverChunks += level.getChunkSource().getLoadedChunksCount();
            }
        }
        return new WorkCounters(renderedSections, clientChunks, serverChunks);
    }

    /**
     * Heap occupancy. Thread-agnostic: JVM-wide and safe to read from anywhere.
     *
     * <p>Heap only. Garbage collection comes from Spark, which reports it better and is already
     * the source users compare against. This reads {@code Runtime} because {@code spark-api}
     * exposes no heap figure at all — not because Spark's version was passed over.
     */
    static MemorySnapshot memory() {
        Runtime runtime = Runtime.getRuntime();
        long committed = runtime.totalMemory();
        return new MemorySnapshot(committed - runtime.freeMemory(), committed);
    }
}
