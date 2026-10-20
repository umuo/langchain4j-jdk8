import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent BOM: failed request is not replayed, new AI Services call uses a fresh WebSocket. */
public class McpWebSocketExample {
    public interface Assistant { String chat(String question); }
    public static void main(String[] args)throws Exception {
        if(!"1.8".equals(System.getProperty("java.specification.version")))throw new IllegalStateException("Requires Java 8");
        ObjectMapper json=new ObjectMapper();AtomicInteger attempts=new AtomicInteger(),executed=new AtomicInteger();
        HttpServer modelServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        modelServer.createContext("/v1/chat/completions",exchange->{
            try {
                JsonNode request=json.readTree(exchange.getRequestBody());boolean result=false;
                for(JsonNode m:request.path("messages"))if(m.path("role").asText().equals("tool")){
                    if(!m.path("content").asText().equals("7 yuan"))throw new AssertionError("Wrong tool result");result=true;
                }
                String message=result?"{\"role\":\"assistant\",\"content\":\"7 yuan\"}":"{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"price1\",\"type\":\"function\",\"function\":{\"name\":\"price\",\"arguments\":\"{}\"}}]}";
                byte[] body=("{\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\""+(result?"stop":"tool_calls")+"\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}
        });modelServer.start();
        try(LocalMcpWebSocketServer ws=new LocalMcpWebSocketServer()){
            ws.messages=(peer,text)->{
                try{
                    JsonNode request=json.readTree(text);if(!request.has("id")||!request.has("method"))return;String result;
                    switch(request.path("method").asText()){
                        case "initialize":result="{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";break;
                        case "tools/list":result="{\"tools\":[{\"name\":\"price\",\"inputSchema\":{\"type\":\"object\"}}]}";break;
                        case "tools/call":
                            if(attempts.incrementAndGet()==1){peer.close();return;}
                            executed.incrementAndGet();result="{\"content\":[{\"type\":\"text\",\"text\":\"7 yuan\"}]}";break;
                        default:result="{}";
                    }
                    String reply="{\"jsonrpc\":\"2.0\",\"id\":"+request.path("id").asLong()+",\"result\":"+result+"}";
                    if(request.path("method").asText().equals("tools/call"))peer.fragmented(reply);else peer.text(reply);
                }catch(Exception e){throw new RuntimeException(e);}
            };
            WebSocketMcpTransport transport=WebSocketMcpTransport.builder().url(ws.url()).timeout(Duration.ofSeconds(3)).closeTimeout(Duration.ofMillis(500)).build();
            try(DefaultMcpClient mcp=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).build()){
                try{
                    mcp.executeToolAsync(ToolExecutionRequest.builder().id("lost").name("price").arguments("{}").build(),null).get(3,TimeUnit.SECONDS);
                    throw new AssertionError("Lost request must fail without replay");
                }catch(ExecutionException expected){}
                OpenAiChatModel model=OpenAiChatModel.builder().baseUrl("http://127.0.0.1:"+modelServer.getAddress().getPort()+"/v1").apiKey("synthetic").modelName("fixture").maxRetries(0).build();
                Assistant service=AiServices.builder(Assistant.class).chatModel(model).toolProvider(McpToolProvider.builder().mcpClients(mcp).build()).build();
                if(!"7 yuan".equals(service.chat("price?")))throw new AssertionError("Wrong answer");
            }
            if(ws.connections.get()!=2||attempts.get()!=2||executed.get()!=1||ws.closedFrames.get()!=1||!ws.errors.isEmpty())throw new AssertionError("Replay or lifecycle failure: "+ws.errors);
            System.out.println("Java 8 MCP WebSocket OK; connections: 2; failed tool: 1; executed tool: 1; close: 1; fragmented result: 7 yuan");
        }finally{modelServer.stop(0);}
    }
}
