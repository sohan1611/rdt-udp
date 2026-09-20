package app;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import rdt.ArqProtocol;
import rdt.ProtocolConfig;
import rdt.RunStats;
import rdt.StopAndWait;

public final class Sender
{
    private Sender()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path file = null;
        InetSocketAddress peer = null;
        String protocol = "stopwait";
        int window = 1;
        String rto = "adaptive";
        int seqBits = 32;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--file":
                    file = Path.of(args[++i]);
                    break;

                case "--to":
                    String[] address = args[++i].split(":", 2);
                    peer = new InetSocketAddress(
                            address[0],
                            Integer.parseInt(address[1])
                    );
                    break;

                case "--protocol":
                    protocol = args[++i];
                    break;

                case "--window":
                    window = Integer.parseInt(args[++i]);
                    break;

                case "--rto":
                    rto = args[++i];
                    break;

                case "--seqbits":
                    seqBits = Integer.parseInt(args[++i]);
                    break;

                case "--verbose":
                    verbose = true;
                    break;

                default:
                    throw new IllegalArgumentException(
                            "Unknown argument: " + args[i]
                    );
            }
        }

        if (file == null || peer == null)
        {
            throw new IllegalArgumentException(
                    "--file and --to are required"
            );
        }

        ArqProtocol arq;

        switch (protocol)
        {
            case "stopwait":
                arq = new StopAndWait();
                break;

            default:
                throw new IllegalArgumentException(
                        "Unsupported protocol: " + protocol
                );
        }

        ProtocolConfig config =
                new ProtocolConfig(
                        window,
                        rto,
                        seqBits,
                        1400
                );

        RunStats stats = arq.send(file, peer, config);

        System.out.println(stats.resultLine());

        if (verbose)
        {
            System.out.println("Transfer complete");
        }
    }
}