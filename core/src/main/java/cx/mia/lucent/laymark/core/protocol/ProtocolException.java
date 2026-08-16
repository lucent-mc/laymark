package cx.mia.lucent.laymark.core.protocol;

/** A frame could not be understood, or the peer is speaking a different protocol version. */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
