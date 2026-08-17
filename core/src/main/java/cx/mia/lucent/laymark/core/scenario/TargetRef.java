package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.StopCondition;

/** How much of something a capture runs until, either counted out or named. */
public sealed interface TargetRef {

    /** A literal quantity, in whatever unit the kind implies. */
    record Count(long value) implements TargetRef {

        public Count {
            if (value <= 0) {
                throw new PlanException("a stop target must be positive, got " + value);
            }
        }

        @Override
        public long count(StopCondition.Kind kind, Preset preset) {
            return value;
        }
    }

    /**
     * Every chunk the game will actually send for the preset's render distance.
     *
     * <p>Derived from configuration both arms share, so it demands identical work of each — which
     * is the only thing that makes a completion target comparable at all.
     */
    record AllInRadius() implements TargetRef {

        /** The name an operator writes. */
        public static final String NAME = "all-in-radius";

        /**
         * {@code ChunkTrackingView.isWithinDistance}'s neighbour allowance: distances shrink by
         * this before the radius test, giving the disc a flattened core.
         */
        private static final int TRACKING_BUFFER = 2;

        @Override
        public long count(StopCondition.Kind kind, Preset preset) {
            if (kind != StopCondition.Kind.CHUNKS) {
                throw new PlanException(
                        NAME + " counts chunks, so it cannot be a target for " + kind);
            }
            return sentChunks(preset.renderDistance());
        }

        /**
         * Replicates the send-set predicate rather than approximating its area.
         *
         * <p>The server does not send a square, and not quite a circle either:
         * {@code ChunkTrackingView.isWithinDistance} tests
         * {@code max(0,|dx|-2)^2 + max(0,|dz|-2)^2 < r^2}, a disc with a flattened core and a
         * <em>strict</em> inequality. A target computed as the enclosing square — {@code (2r+1)^2}
         * — asks for ~28% more chunks than the game will ever deliver, so the capture waits out
         * its entire timeout on a number that cannot arrive. Verified against a real run: at
         * render distance 32 the square says 4225, the game stops sending at what this counts.
         */
        static long sentChunks(int viewDistance) {
            long sent = 0;
            int reach = viewDistance + TRACKING_BUFFER;
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    long clampedX = Math.max(0, Math.abs(dx) - TRACKING_BUFFER);
                    long clampedZ = Math.max(0, Math.abs(dz) - TRACKING_BUFFER);
                    if (clampedX * clampedX + clampedZ * clampedZ
                            < (long) viewDistance * viewDistance) {
                        sent++;
                    }
                }
            }
            return sent;
        }
    }

    /**
     * @param kind the unit being counted, so a name can refuse a kind it does not describe
     * @param preset the scenario's resolved settings, which is what a named target is derived from
     */
    long count(StopCondition.Kind kind, Preset preset);

    static TargetRef of(long value) {
        return new Count(value);
    }

    static TargetRef allInRadius() {
        return new AllInRadius();
    }
}
