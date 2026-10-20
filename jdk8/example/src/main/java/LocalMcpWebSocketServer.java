import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

/** Loopback-only RFC 6455 test fixture, not a production WebSocket server. */
public class LocalMcpWebSocketServer implements AutoCloseable {
    final ServerSocket listener;
    final ExecutorService workers=Executors.newCachedThreadPool();
    final Set<Peer> peers=ConcurrentHashMap.newKeySet();
    final List<Throwable> errors=new CopyOnWriteArrayList<>();
    public final AtomicInteger connections=new AtomicInteger();
    public final AtomicInteger closedFrames=new AtomicInteger();
    public volatile BiConsumer<Peer,String> messages=(p,s)->{};
    public volatile boolean ignoreClose, holdUpgrade;
    public volatile int upgradeStatus=101;
    volatile boolean closed;
    final boolean tls;
    public LocalMcpWebSocketServer() throws IOException { this(null); }
    public LocalMcpWebSocketServer(javax.net.ssl.SSLContext context) throws IOException {
        tls=context!=null;
        listener=context==null?new ServerSocket(0,50,InetAddress.getByName("127.0.0.1")):
                context.getServerSocketFactory().createServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
        workers.execute(()->{
            while(!closed)try {
                Peer peer=new Peer(listener.accept());peers.add(peer);workers.execute(()->serve(peer));
            }catch(IOException e){if(!closed)errors.add(e);}
        });
    }
    public String url(){return (tls?"wss://127.0.0.1:":"ws://127.0.0.1:")+listener.getLocalPort()+"/mcp";}
    void serve(Peer p) {
        try {
            String request=line(p.in);if(request==null)return;
            String h;while((h=line(p.in))!=null&&!h.isEmpty()){
                int colon=h.indexOf(':');if(colon>0)p.headers.put(h.substring(0,colon).trim().toLowerCase(Locale.ROOT),h.substring(colon+1).trim());
            }
            p.number=connections.incrementAndGet();
            if(holdUpgrade){while(!closed&&!p.socket.isClosed())Thread.sleep(10);return;}
            if(upgradeStatus!=101){p.out.write(("HTTP/1.1 "+upgradeStatus+" Rejected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));p.out.flush();return;}
            String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((p.headers.get("sec-websocket-key")+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            p.out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));p.out.flush();
            ByteArrayOutputStream text=new ByteArrayOutputStream();
            while(!closed){
                int a=p.in.read();if(a<0)return;int b=p.in.read();if(b<0)throw new EOFException();
                int opcode=a&15;long length=b&127;
                if(length==126)length=((p.in.readUnsignedByte()<<8)|p.in.readUnsignedByte());
                else if(length==127)length=p.in.readLong();
                if(length<0||length>20*1024*1024)throw new IOException("Fixture frame size invalid");
                if((b&128)==0)throw new IOException("Client frames must be masked");
                byte[] mask=new byte[4];p.in.readFully(mask);byte[] body=new byte[(int)length];p.in.readFully(body);
                for(int i=0;i<body.length;i++)body[i]^=mask[i%4];
                if(opcode==8){closedFrames.incrementAndGet();if(ignoreClose)continue;p.frame(8,true,body);return;}
                if(opcode==9){p.frame(10,true,body);continue;}if(opcode==10)continue;
                if(opcode==1||opcode==0){text.write(body);if((a&128)!=0){messages.accept(p,new String(text.toByteArray(),StandardCharsets.UTF_8));text.reset();}}
            }
        }catch(SocketException|EOFException expected){ }
        catch(Throwable e){if(!closed)errors.add(e);}
        finally{p.close();peers.remove(p);}
    }
    static String line(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1&&c!='\n'){if(b.size()>16384)throw new IOException("Fixture header too long");if(c!='\r')b.write(c);}
        return c<0&&b.size()==0?null:new String(b.toByteArray(),StandardCharsets.US_ASCII);
    }
    public static class Peer implements AutoCloseable {
        final Socket socket;final DataInputStream in;final OutputStream out;
        public final Map<String,String> headers=new ConcurrentHashMap<>();
        public int number;
        Peer(Socket socket)throws IOException{this.socket=socket;in=new DataInputStream(socket.getInputStream());out=socket.getOutputStream();}
        public void text(String json)throws IOException{frame(1,true,json.getBytes(StandardCharsets.UTF_8));}
        public synchronized void fragmented(String json)throws IOException{
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);int split=bytes.length/2;
            frame(1,false,Arrays.copyOfRange(bytes,0,split));frame(9,true,new byte[]{1});frame(0,true,Arrays.copyOfRange(bytes,split,bytes.length));
        }
        public synchronized void frame(int opcode,boolean fin,byte[] data)throws IOException{
            out.write((fin?128:0)|opcode);int n=data.length;
            if(n<126)out.write(n);else if(n<=65535){out.write(126);out.write(n>>>8);out.write(n);}else{out.write(127);for(int shift=56;shift>=0;shift-=8)out.write((int)(((long)n)>>>shift));}
            out.write(data);out.flush();
        }
        @Override public void close(){try{socket.close();}catch(IOException ignored){}}
    }
    @Override public void close()throws IOException{
        closed=true;listener.close();for(Peer p:peers)p.close();workers.shutdownNow();
        try{if(!workers.awaitTermination(5,TimeUnit.SECONDS))throw new IOException("Fixture worker leaked");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
}
