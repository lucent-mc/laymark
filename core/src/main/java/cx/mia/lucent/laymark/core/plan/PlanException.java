package cx.mia.lucent.laymark.core.plan;

/**
 * A plan is invalid and the run must not start.
 *
 * <p>The runner has no interactive CLI and cannot prompt, so an ambiguity is an error rather
 * than a question. Failing here is cheap; failing three days into a selection run is not.
 */
public class PlanException extends RuntimeException {

    public PlanException(String message) {
        super(message);
    }
}
