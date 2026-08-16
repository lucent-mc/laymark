package cx.mia.lucent.laymark.runner.launch;

/** A launch could not be assembled or started. */
public class LaunchException extends RuntimeException {

    public LaunchException(String message) {
        super(message);
    }

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
