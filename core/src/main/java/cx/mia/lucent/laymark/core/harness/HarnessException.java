package cx.mia.lucent.laymark.core.harness;

/**
 * A run could not proceed as specified.
 *
 * <p>Unchecked, matching {@code PlanException} and {@code ProtocolException}: one paradigm per
 * module, thrown at the point of discovery and handled at the run boundary, which is the only
 * place that can decide what to do about it.
 */
public class HarnessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
