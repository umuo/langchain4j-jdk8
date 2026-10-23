package example;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.OkHttpClient;

/** Synthetic loopback fixture shared by native acceptance and the independent WIP BOM consumer. */
public final class AgenticMcpFixture implements AutoCloseable {
    public interface Echo { @Agent String echo(@V("input") String input); }
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id,@V("input") String input); }
    public interface Data { @Agent Payload read(@V("input") String input); }
    public static class Payload { public String text; public int count; }
    public final ObjectMapper json=new ObjectMapper();
    public final HttpServer server;
    public final ExecutorService serverThreads=Executors.newCachedThreadPool();
    public final OkHttpClient http=new OkHttpClient.Builder().readTimeout(10,TimeUnit.SECONDS).build();
    public final List<JsonNode> calls=new CopyOnWriteArrayList<>();
    public final CountDownLatch started=new CountDownLatch(1),socketClosed=new CountDownLatch(1),cancelled=new CountDownLatch(1);
    public final AtomicInteger cancellationCount=new AtomicInteger();
    public final AtomicBoolean stopping=new AtomicBoolean();
    public volatile long cancelledId=-1;
    public final StreamableHttpMcpTransport transport;
    public final DefaultMcpClient client;
    public AgenticMcpFixture(long timeout)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(serverThreads);server.createContext("/mcp",this::handle);server.start();
        transport=StreamableHttpMcpTransport.builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp").httpClient(http).build();
        client=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).initializationTimeout(Duration.ofSeconds(5)).toolExecutionTimeout(Duration.ofMillis(timeout)).build();
    }
    public static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static Object tools(){return map("tools",Arrays.asList(map("name","echo","description","Echo the input","inputSchema",map("type","object","properties",map("input",map("type","string","description","Input text"),"tags",map("type","array","items",map("type","integer")),"choice",map("anyOf",Arrays.asList(map("type","string"),map("type","null")))),"required",Arrays.asList("input")))));}
    static Object result(JsonNode request){
        String method=request.path("method").asText();
        if(method.equals("initialize"))return map("protocolVersion","2025-11-25","capabilities",map("tools",map()),"serverInfo",map("name","agentic-fixture","version","1"));
        if(method.equals("tools/list"))return tools();
        if(method.equals("tools/call")){
            String input=request.path("params").path("arguments").path("input").asText();
            String text=input.equals("json")?"{\"text\":\"payload\",\"count\":7}":input.equals("sse")?"晴天":input;
            return map("content",Arrays.asList(map("type","text","text",text)),"isError",input.equals("application-error"));
        }
        return map();
    }
    void handle(HttpExchange exchange)throws IOException{
        try{
            if(exchange.getRequestMethod().equals("DELETE")){exchange.sendResponseHeaders(200,-1);return;}
            JsonNode request=json.readTree(exchange.getRequestBody());
            if(!request.has("id")){
                if(request.path("method").asText().equals("notifications/cancelled")){cancelledId=request.path("params").path("requestId").asLong();cancellationCount.incrementAndGet();cancelled.countDown();}
                exchange.sendResponseHeaders(202,-1);return;
            }
            String method=request.path("method").asText();
            if(method.equals("initialize"))exchange.getResponseHeaders().set("Mcp-Session-Id","agentic-fixture-session");
            String input=request.path("params").path("arguments").path("input").asText();
            if(method.equals("tools/call")){
                calls.add(request);started.countDown();
                if(input.equals("hold")){
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                    try{while(!stopping.get()){exchange.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();Thread.sleep(15);}}
                    catch(IOException closed){socketClosed.countDown();}catch(InterruptedException e){Thread.currentThread().interrupt();}return;
                }
            }
            Map<String,Object> response=input.equals("rpc-error")?map("jsonrpc","2.0","id",request.path("id").asLong(),"error",map("code",-32602,"message","synthetic RPC error")):
                map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result(request));
            byte[] body=json.writeValueAsBytes(response);
            if(input.equals("sse")){
                exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);
                byte[] stream=("event: message\r\ndata: "+new String(body,StandardCharsets.UTF_8)+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                for(byte b:stream){exchange.getResponseBody().write(b);exchange.getResponseBody().flush();}
            }else{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}
        }finally{exchange.close();}
    }
    public void drained()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end&&(http.dispatcher().runningCallsCount()!=0||http.dispatcher().queuedCallsCount()!=0))Thread.sleep(5);
        if(http.dispatcher().runningCallsCount()!=0||http.dispatcher().queuedCallsCount()!=0)throw new AssertionError("MCP dispatcher did not drain");
    }
    @Override public void close()throws Exception{stopping.set(true);client.close();transport.close();server.stop(0);serverThreads.shutdownNow();http.dispatcher().executorService().shutdownNow();http.connectionPool().evictAll();if(!serverThreads.awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("fixture server threads leaked");}
    /** Real Java8 stdio child, used without JBang or a remote service. */
    public static final class StdioServer {
        public static void main(String[] args)throws Exception{
            if(!System.getProperty("java.version").startsWith("1.8."))throw new AssertionError("Java8 child required");
            ObjectMapper mapper=new ObjectMapper();BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));PrintWriter out=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
            String line;while((line=in.readLine())!=null){JsonNode request=mapper.readTree(line);if(request.has("id"))out.println(mapper.writeValueAsString(map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result(request))));}
        }
    }
}
