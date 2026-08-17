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
     * Every chunk within the preset's render distance of the observation point.
     *
     * <p>Derived from configuration both arms share, so it demands identical work of each — which
     * is the only thing that makes a completion target comparable at all.
     */
    record AllInRadius() implements TargetRef {

        /** The name an operator writes. */
        public static final String NAME = "all-in-radius";

        @Override
        public long count(StopCondition.Kind kind, Preset preset) {
            if (kind != StopCondition.Kind.CHUNKS) {
                throw new PlanException(
                        NAME + " counts chunks, so it cannot be a target for " + kind);
            }
            // The square the client loads: radius chunks out in each direction, plus the one the
            // player stands in.
            long side = 2L * preset.renderDistance() + 1;
            return side * side;
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
