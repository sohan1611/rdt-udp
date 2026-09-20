package rdt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

public interface ArqProtocol
{
    RunStats send(Path file, InetSocketAddress peer,
                  ProtocolConfig config) throws IOException;

    RunStats receive(Path file, int port,
                     ProtocolConfig config) throws IOException;
}