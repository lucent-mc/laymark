package cx.mia.lucent.laymark.core.stats;

import cx.mia.lucent.laymark.core.harness.HarnessException;

/**
 * Two-sided 95% critical values for Student's t.
 *
 * <p>Student's t rather than the normal distribution, and the reason is the whole point of the
 * gates: at the sample sizes a benchmark can afford, the standard-deviation estimate is itself
 * uncertain, and assuming normal produces intervals that are too narrow. Too-narrow intervals
 * manufacture exactly the false positives the gates exist to prevent — Laymark would confidently
 * report improvements that are noise.
 *
 * <p>A table rather than an incomplete-beta implementation. Only one confidence level is ever used,
 * the values are fixed constants, and a table is checkable against any statistics reference by
 * reading it.
 */
final class StudentT {

    private StudentT() {}

    /** Two-sided 95% critical value, indexed by degrees of freedom starting at 1. */
    private static final double[] CRITICAL = {
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
    };

    /** Beyond the table the value is flat to three decimals; this is the z it converges on. */
    private static final double LARGE_SAMPLE = 1.960;

    /**
     * @param degreesOfFreedom one less than the number of paired observations
     * @throws HarnessException below one, where no interval exists at all — a single observation
     *     cannot say anything about spread, and returning a number would imply it could
     */
    static double criticalValue(int degreesOfFreedom) {
        if (degreesOfFreedom < 1) {
            throw new HarnessException(
                    "a confidence interval needs at least two observations, got "
                            + (degreesOfFreedom + 1));
        }
        return degreesOfFreedom <= CRITICAL.length
                ? CRITICAL[degreesOfFreedom - 1]
                : LARGE_SAMPLE;
    }
}
