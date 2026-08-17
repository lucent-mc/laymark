package cx.mia.lucent.laymark.core.harness;

/**
 * Where the player stands and what it looks at.
 *
 * <p>Fixed rather than derived from spawn. Vanilla's spawn point moves with world seed and
 * version, and a pose that drifts between arms changes which chunks are in view — the single
 * largest lever on frame time there is.
 *
 * @param pitch degrees; positive is downward
 */
public record Pose(double x, double y, double z, float yaw, float pitch) {

    /** Straight down, from above the terrain. */
    public static final float LOOKING_DOWN = 90f;

    public Pose {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new HarnessException("pose has a non-finite coordinate");
        }
        if (!Float.isFinite(yaw) || pitch < -90f || pitch > 90f) {
            throw new HarnessException("pose has an out-of-range rotation");
        }
    }

    public static Pose lookingDown(double x, double y, double z) {
        return new Pose(x, y, z, 0f, LOOKING_DOWN);
    }

    /** Chunk coordinates, for deciding whether a region has been generated. */
    public int chunkX() {
        return Math.floorDiv((int) Math.floor(x), 16);
    }

    public int chunkZ() {
        return Math.floorDiv((int) Math.floor(z), 16);
    }
}
