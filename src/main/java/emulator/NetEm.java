package emulator;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The emulator stays outside the protocol process so protocol code needs no test-only branches and does not know it is being tested.
 */
public final class NetEm{
    private static final int MAX_BLOCK_MS=200;
    private static final int SOCKET_BUFFER_BYTES=4*1024*1024;
    private final InetSocketAddress listenAddr;
    private final InetSocketAddress receiverAddr;
    private final Channel c2s;
    private final Channel s2c;
    private final boolean verbose;
    // A heap does not preserve order for equal keys, so order breaks ties deterministically and prevents packets with the same release time from being reordered accidentally.
    private final PriorityQueue<Pending> queue=new PriorityQueue<>(
        Comparator.comparingLong(Pending::releaseNanos)
            .thenComparingLong(Pending::order)
    );
    private long order;
    private SocketAddress senderAddr;
    private volatile boolean running=true;
    private final CountDownLatch ready=new CountDownLatch(1);
    private volatile int boundPort=-1;
    private record Pending(byte[] data,long releaseNanos,boolean toReceiver,long order){}
    public NetEm(InetSocketAddress listenAddr,InetSocketAddress receiverAddr,ChannelConfig up,ChannelConfig down,long seed,TraceLog trace,boolean verbose){
        this.listenAddr=listenAddr;
        this.receiverAddr=receiverAddr;
        this.verbose=verbose;
        Random master=new Random(seed);
        // One master seed gives two independent reproducible streams instead of making both directions act in lockstep.
        this.c2s=new Channel("c2s",up,master.nextLong(),trace);
        this.s2c=new Channel("s2c",down,master.nextLong(),trace);
    }

    public void run()throws IOException{
        try(DatagramSocket sock=new DatagramSocket(listenAddr)){
            // Large buffers prevent the kernel from dropping burst traffic before NetEm can apply its configured impairments.
            sock.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
            sock.setSendBufferSize(SOCKET_BUFFER_BYTES);
            boundPort=sock.getLocalPort();
            ready.countDown();
            byte[] buf=new byte[65535];
            while(running){
                releaseDue(sock);
                // Zero means wait forever, so use at least 1 ms; the 200 ms cap lets stop() be noticed while idle.
                sock.setSoTimeout(nextTimeoutMs());
                DatagramPacket packet=new DatagramPacket(buf,buf.length);
                try{
                    sock.receive(packet);
                }catch(SocketTimeoutException e){
                    continue;
                }
                handle(packet);
            }
            System.err.println(c2s.summary());
            System.err.println(s2c.summary());
        }
    }

    public void stop(){
        running=false;
    }

    public boolean awaitReady(long timeoutMs)throws InterruptedException{
        return ready.await(timeoutMs,TimeUnit.MILLISECONDS);
    }

    public int boundPort(){
        return boundPort;
    }

    private void handle(DatagramPacket p){
        SocketAddress from=p.getSocketAddress();
        Channel channel;
        boolean toReceiver;
        String direction;

        if(from.equals(receiverAddr)){
            if(senderAddr==null){
                if(verbose)System.err.println("s2c drop: sender unknown");
                return;
            }
            channel=s2c;
            toReceiver=false;
            direction="s2c";
        }else{
            if(senderAddr==null){
                senderAddr=from;
                if(verbose)System.err.println("learned sender "+senderAddr);
            }else if(!from.equals(senderAddr)){
                if(verbose)System.err.println("ignored third party "+from);
                return;
            }

            channel=c2s;
            toReceiver=true;
            direction="c2s";
        }

        // Channel copies the received bytes before creating a Delivery, so reuse of the socket receive buffer cannot overwrite queued packets.
        List<Channel.Delivery> deliveries=channel.offer(
            p.getData(),
            p.getLength(),
            System.nanoTime()
        );

        for(Channel.Delivery d:deliveries){
            queue.add(new Pending(
                d.data,
                d.releaseNanos,
                toReceiver,
                order++
            ));
            if(verbose){
                System.err.printf(
                    "%s size=%d delay=%.1fms dup=%s corrupt=%s reordered=%s%n",
                    direction,
                    d.data.length,
                    d.delayMs,
                    d.duplicate,
                    d.corrupted,
                    d.reordered
              );
            
            }
        }
    }

    private void releaseDue(DatagramSocket sock){
        long now=System.nanoTime();
        while(!queue.isEmpty()&&queue.peek().releaseNanos()<=now){
            Pending pending=queue.poll();
            SocketAddress destination=pending.toReceiver()?receiverAddr:senderAddr;
            try{
                sock.send(new DatagramPacket(
                    pending.data(),
                    pending.data().length,
                    destination
                ));
            }catch(IOException e){
                System.err.println("NetEm send warning: "+e.getMessage());
            }
        }
    }

    private int nextTimeoutMs(){
        if(queue.isEmpty())return MAX_BLOCK_MS;
        long delta=queue.peek().releaseNanos()-System.nanoTime();
        if(delta<=0)return 1;
        long ms=delta/1_000_000L;
        if(delta%1_000_000L!=0)ms++;
        return (int)Math.max(1,Math.min(MAX_BLOCK_MS,ms));
    }

    private static void usage(java.io.PrintStream out){
        out.println(
            "Usage: java -cp build/classes emulator.NetEm "+
            "[--listen PORT] [--to host:port] "+
            "[--seed N] [--up SPEC] [--down SPEC] [--both SPEC] "+
            "[--trace FILE] [--verbose] [--help]"
        );
    }

    public static void main(String[] args)throws Exception{
        // Defaults are the manual-testing ports from CONVENTIONS section 6.
        int listenPort=9000;
        String to="127.0.0.1:9001";
        long seed=1L;
        String upSpec="";
        String downSpec="";
        String traceFile=null;
        boolean verbose=false;
        for(int i=0;i<args.length;i++){
            switch(args[i]){
                case "--help"->{
                    usage(System.out);
                    return;
                }
                case "--verbose"->verbose=true;
                case "--listen"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    listenPort=Integer.parseInt(args[++i]);
                }
                case "--to"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    to=args[++i];
                }
                case "--seed"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    seed=Long.parseLong(args[++i]);
                }
                case "--up"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    upSpec=args[++i];
                }
                case "--down"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    downSpec=args[++i];
                }
                case "--both"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    String spec=args[++i];
                    upSpec=spec;
                    downSpec=spec;
                }
                case "--trace"->{
                    if(i+1>=args.length){
                        usage(System.err);
                        System.exit(2);
                        return;
                    }
                    traceFile=args[++i];
                }
                default->{
                    usage(System.err);
                    System.exit(2);
                    return;
                }
            }
        }

        String[] target=to.split(":",2);
        if(target.length!=2||target[0].isEmpty()||target[1].isEmpty()){
            usage(System.err);
            System.exit(2);
            return;
        }
        InetSocketAddress listenAddr=new InetSocketAddress(listenPort);
        InetSocketAddress receiverAddr=new InetSocketAddress(
            target[0],
            Integer.parseInt(target[1])
        );
        ChannelConfig up=ChannelConfig.parse(upSpec);
        ChannelConfig down=ChannelConfig.parse(downSpec);
        TraceLog trace=traceFile==null
            ?TraceLog.disabled()
            :new TraceLog(Path.of(traceFile));
        NetEm netem=new NetEm(
            listenAddr,
            receiverAddr,
            up,
            down,
            seed,
            trace,
            verbose
        );
        Runtime.getRuntime().addShutdownHook(new Thread(netem::stop));
        try{
            netem.run();
        }finally{
            trace.close();
        }
    }
}