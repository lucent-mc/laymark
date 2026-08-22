package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.MemorySnapshot;
import cx.mia.lucent.laymark.core.harness.WorkCounters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

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

        // Every counter is guarded on there being a world, and that is not defensive padding: a
        // scenario measuring spawn generation opens its capture *before* the world is created, so
        // the first reading is always taken with no level loaded. The renderer has no sections to
        // count then, and asking it anyway is a failure partway into an already-paid-for launch.
        if (minecraft.level == null) {
            return new WorkCounters(0, 0, 0);
        }

        int renderedSections = minecraft.levelRenderer.countRenderedSections();
        int clientChunks = minecraft.level.getChunkSource().getLoadedChunksCount();

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
     * How many chunks the client holds within the send radius of a point.
     *
     * <p>Not global occupancy, and not a difference between two occupancies — both are wrong for
     * the same reason. The client's storage holds roughly a full radius at all times: after a
     * teleport it drops what it left behind as it takes on what it arrives at, so occupancy barely
     * moves while thousands of chunks are received. A capture that stops on that difference waits
     * for a number that never arrives. Observed on a real run: 3848 chunks held around the target,
     * a 3725 target, and the delta still short of it after half an hour.
     *
     * <p>Counting what is present <em>around the pose</em> answers the question the phase actually
     * asks — is the region loaded yet — and starts near zero because the scenario teleports
     * somewhere nothing was loaded.
     *
     * <p>Client thread.
     */
    static long chunksLoadedAround(int centerChunkX, int centerChunkZ, int viewDistance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0;
        }
        ClientChunkCache chunks = minecraft.level.getChunkSource();
        long loaded = 0;
        // The same bound the server sends within, so the count and its target are one definition.
        int reach = viewDistance + CHUNK_TRACKING_BUFFER;
        for (int x = centerChunkX - reach; x <= centerChunkX + reach; x++) {
            for (int z = centerChunkZ - reach; z <= centerChunkZ + reach; z++) {
                long dx = Math.max(0, Math.abs(x - centerChunkX) - CHUNK_TRACKING_BUFFER);
                long dz = Math.max(0, Math.abs(z - centerChunkZ) - CHUNK_TRACKING_BUFFER);
                if (dx * dx + dz * dz < (long) viewDistance * viewDistance
                        && chunks.getChunk(x, z, ChunkStatus.FULL, false) != null) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /** Mirrors {@code ChunkTrackingView}'s neighbour allowance. */
    private static final int CHUNK_TRACKING_BUFFER = 2;

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

    /**
     * The live heap after an explicit collection, for cross-process comparison.
     *
     * <p>Raw heap occupancy mostly says when G1 happened to run. Collection is outside the timed
     * frame window, and every arm pays it at the same boundary, so the retained live set is the
     * memory quantity a candidate can meaningfully improve.
     */
    static MemorySnapshot retainedMemory() {
        System.gc();
        return memory();
    }
}
