package rdt;

/**
 * Thrown when a received datagram cannot be trusted: truncated, wrong version,
 * inconsistent declared length, or a failed checksum.
 *
 * <p>This is a normal, expected event on a lossy channel, not a programming
 * error. Receivers catch it, count it, and drop the datagram; they must never
 * let it terminate the receive loop.
 */
public class CorruptPacketException extends Exception {

    private static final long serialVersionUID = 1L;

    public CorruptPacketException(String message) {
        super(message);
    }
}
