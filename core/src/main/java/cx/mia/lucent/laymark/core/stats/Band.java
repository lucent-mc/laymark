package cx.mia.lucent.laymark.core.stats;

/**
 * The four plain-language verdicts a comparison can reach.
 *
 * <p>The band leads and the numbers follow, because "a 2.1% improvement, interval −0.3% to 4.5%" is
 * precisely the sentence most readers take as a 2.1% improvement. Stating the verdict first makes
 * the interval qualify a claim the reader has already been given, rather than being the thing they
 * have to derive the claim from.
 */
public enum Band {

    /** Real, and large enough to be worth having. */
    IMPROVED("improved"),

    /** Real, but under the practical floor — measurable is not the same as worth acting on. */
    NEGLIGIBLE("negligible"),

    /**
     * The interval spans zero. Not a claim that the two are equal — a claim that this experiment
     * cannot tell them apart, which is a different and weaker statement.
     */
    NO_MEASURABLE_DIFFERENCE("no measurable difference"),

    /** Real and in the wrong direction. Reported, never a rejection. */
    REGRESSED("regressed");

    private final String label;

    Band(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public boolean real() {
        return this != NO_MEASURABLE_DIFFERENCE;
    }
}
