import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Independent BOM: modern discover, acknowledged SSE, and async AI Services state continuation. */
public class McpModernExample {
    public interface Assistant { CompletableFuture<String> chat(String question); }
    static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    public static void main(String[] args)throws Exception{
        if(!"1.8".equals(System.getProperty("java.specification.version")))throw new IllegalStateException("Requires Java 8");
        ObjectMapper json=new ObjectMapper();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        okhttp3.OkHttpClient http=new okhttp3.OkHttpClient();AtomicInteger discovers=new AtomicInteger(),attempts=new AtomicInteger(),executed=new AtomicInteger(),cancelPosts=new AtomicInteger();
        CountDownLatch streamClosed=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        server.createContext("/mcp",e->{
            boolean streaming=false;
            try{
                if(!e.getRequestMethod().equals("POST"))throw new AssertionError("Modern mode must not GET/DELETE sessions");
                JsonNode request=json.readTree(e.getRequestBody());String method=request.path("method").asText();JsonNode params=request.path("params");
                if(e.getRequestHeaders().getFirst("MCP-Session-Id")!=null||!method.equals(e.getRequestHeaders().getFirst("Mcp-Method"))||!"2026-07-28".equals(e.getRequestHeaders().getFirst("MCP-Protocol-Version")))throw new AssertionError("Wrong modern headers");
                Object result;
                switch(method){
                    case "server/discover":discovers.incrementAndGet();result=map("supportedVersions",Arrays.asList("2026-07-28"),"capabilities",map("tools",map(),"resources",map()),"instructions","modern fixture");break;
                    case "tools/list":result=map("tools",Arrays.asList(map("name","price","inputSchema",map("type","object","properties",map("item",map("type","string","x-mcp-header","item"))))));break;
                    case "tools/call":attempts.incrementAndGet();
                        if(!"apple".equals(e.getRequestHeaders().getFirst("Mcp-Param-item")))throw new AssertionError("Parameter header lost");
                        if(!params.has("requestState"))result=map("resultType","input_required","requestState","step2");
                        else{if(!params.path("requestState").asText().equals("step2"))throw new AssertionError("Wrong continuation state");executed.incrementAndGet();result=map("content",Arrays.asList(map("type","text","text","7 yuan")));}break;
                    case "subscriptions/listen":
                        e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);streaming=true;
                        Object ack=map("jsonrpc","2.0","method","notifications/subscriptions/acknowledged","params",map("_meta",map("io.modelcontextprotocol/subscriptionId",request.path("id").asLong()),"notifications",json.convertValue(params.path("notifications"),Map.class)));
                        e.getResponseBody().write(("data: "+json.writeValueAsString(ack)+"\n\n").getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();
                        while(!release.await(20,TimeUnit.MILLISECONDS)){e.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();}return;
                    case "notifications/cancelled":cancelPosts.incrementAndGet();e.sendResponseHeaders(202,-1);return;
                    default:throw new AssertionError(method);
                }
                byte[] body=json.writeValueAsBytes(map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result));
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
            }catch(java.io.IOException expected){if(!streaming)failure.compareAndSet(null,expected);}
            catch(Throwable error){failure.compareAndSet(null,error);}
            finally{e.close();if(streaming)streamClosed.countDown();}
        });
        server.createContext("/v1/chat/completions",e->{
            try{
                JsonNode request=json.readTree(e.getRequestBody());boolean done=false;
                for(JsonNode message:request.path("messages"))if(message.path("role").asText().equals("tool")){if(!message.path("content").asText().equals("7 yuan"))throw new AssertionError("Wrong result");done=true;}
                Object message=done?map("role","assistant","content","7 yuan"):map("role","assistant","tool_calls",Arrays.asList(map("id","call1","type","function","function",map("name","price","arguments","{\"item\":\"apple\"}"))));
                byte[] body=json.writeValueAsBytes(map("id","fixture","model","fixture","choices",Arrays.asList(map("index",0,"message",message,"finish_reason",done?"stop":"tool_calls"))));
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{e.close();}
        });server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            StreamableHttpMcpTransport transport=StreamableHttpMcpTransport.builder().url(base+"/mcp").httpClient(http).build();
            try(DefaultMcpClient mcp=DefaultMcpClient.builder().transport(transport).protocolVersion("2026-07-28").autoHealthCheck(false).subscribeToToolListChanges(false).subscribeToPromptListChanges(false).subscribeToResourceListChanges(false).build()){
                long subscription=mcp.subscribeToResources(Arrays.asList("test://price"));
                OpenAiChatModel model=OpenAiChatModel.builder().httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(http.newBuilder())).baseUrl(base+"/v1").apiKey("synthetic").modelName("fixture").maxRetries(0).build();
                Assistant assistant=AiServices.builder(Assistant.class).chatModel(model).toolProvider(McpToolProvider.builder().mcpClients(mcp).build()).build();
                if(!assistant.chat("apple price?").get(5,TimeUnit.SECONDS).equals("7 yuan"))throw new AssertionError("Wrong answer");
                mcp.unsubscribeFromResources(subscription);if(!streamClosed.await(3,TimeUnit.SECONDS))throw new AssertionError("Stream leaked");
            }
            if(discovers.get()!=1||attempts.get()!=2||executed.get()!=1||cancelPosts.get()!=0||failure.get()!=null)throw new AssertionError("Modern consumer failed",failure.get());
            System.out.println("Java 8 MCP modern OK; discover: 1; continuation frames: 2; executed tool: 1; subscription released; cancellation POST: 0; answer: 7 yuan");
        }finally{release.countDown();server.stop(0);threads.shutdownNow();http.dispatcher().executorService().shutdownNow();http.connectionPool().evictAll();}
    }
}
