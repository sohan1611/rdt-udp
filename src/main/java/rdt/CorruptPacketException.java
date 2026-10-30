package rdt;
public class CorruptPacketException extends Exception
{
    private static final long serialVersionUID = 1L;
   public CorruptPacketException(String message) {
        super(message);
    }

    public CorruptPacketException(String message, Throwable cause) {
        super(message, cause);
    }
}
