package app;

import java.nio.file.Path;

import rdt.ArqProtocol;
import rdt.ProtocolConfig;
import rdt.RunStats;
import rdt.StopAndWait;

public final class Receiver
{
    private Receiver()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path output = null;
        int port = -1;
        String protocol = "stopwait";
        int window = 1;
        String rto = "adaptive";
        int seqBits = 32;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;

                case "--out":
                    output = Path.of(args[++i]);
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

        if (output == null || port < 0)
        {
            throw new IllegalArgumentException(
                    "--port and --out are required"
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
                        1400,
                        0
                );

        RunStats stats = arq.receive(
                output,
                port,
                config
        );

        if (verbose)
        {
            System.out.println("Transfer complete");
        }
    }
}