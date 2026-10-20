import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent BOM: real Java 8 child MCP, resources/prompts and OpenAI -> AI Services tool loop. */
public class McpStdioExample {
    public interface Assistant { String chat(String question); }
    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new IllegalStateException("Requires Java 8");
        ObjectMapper json = new ObjectMapper(); AtomicInteger modelCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody()); modelCalls.incrementAndGet();
                boolean hasToolResult=false;
                for(JsonNode message:request.path("messages")) {
                    if("tool".equals(message.path("role").asText())) {
                        if(!"7 yuan".equals(message.path("content").asText())) throw new AssertionError(message);
                        hasToolResult=true;
                    }
                }
                String message=hasToolResult ? "{\"role\":\"assistant\",\"content\":\"7 yuan\"}" :
                        "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"price1\",\"type\":\"function\",\"function\":{\"name\":\"price\",\"arguments\":\"{\\\"item\\\":\\\"apple\\\"}\"}}]}";
                byte[] body=("{\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\""+(hasToolResult?"stop":"tool_calls")+"\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } finally {exchange.close();}
        });server.start();
        StdioMcpTransport transport=StdioMcpTransport.builder().command(Arrays.asList(
                System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),Server.class.getName())).build();
        Process child=null;
        try(DefaultMcpClient mcp=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).build()) {
            child=transport.getProcess();
            String resource=((McpTextResourceContents)mcp.readResource("test://price").contents().get(0)).text();
            if(!"apple price".equals(resource))throw new AssertionError(resource);
            McpGetPromptResult prompt=mcp.getPrompt("price",Collections.singletonMap("item","apple"));
            if(prompt.messages().size()!=1)throw new AssertionError(prompt);
            OpenAiChatModel model=OpenAiChatModel.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                    .apiKey("synthetic").modelName("fixture").maxRetries(0).build();
            Assistant assistant=AiServices.builder(Assistant.class).chatModel(model)
                    .toolProvider(McpToolProvider.builder().mcpClients(mcp).build()).build();
            String answer=assistant.chat(resource);
            if(!"7 yuan".equals(answer)||modelCalls.get()!=2)throw new AssertionError(answer);
        } finally { transport.close();server.stop(0); }
        if(child==null||child.isAlive())throw new AssertionError("Child not released");
        System.out.println("Java 8 MCP stdio OK; resources/prompts: OK; answer: 7 yuan; model calls: 2; child released");
    }
    public static class Server {
        public static void main(String[] args) throws Exception {
            if(!"1.8".equals(System.getProperty("java.specification.version")))System.exit(91);
            ObjectMapper json=new ObjectMapper();BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            PrintWriter out=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
            int calls=0;String line;
            while((line=input.readLine())!=null) {
                JsonNode request=json.readTree(line);if(!request.has("id"))continue;Object result;
                switch(request.path("method").asText()) {
                    case "initialize":result=map("protocolVersion","2025-11-25","serverInfo",map("name","stdio-fixture","version","1"),"capabilities",map("tools",map(),"resources",map(),"prompts",map()));break;
                    case "resources/read":result=map("contents",Arrays.asList(map("uri","test://price","text","apple price")));break;
                    case "prompts/get":result=map("messages",Arrays.asList(map("role","user","content",map("type","text","text","apple price?"))));break;
                    case "tools/list":result=map("tools",Arrays.asList(map("name","price","inputSchema",map("type","object","properties",map("item",map("type","string")),"required",Arrays.asList("item")))));break;
                    case "tools/call":
                        if(++calls!=1||!"apple".equals(request.path("params").path("arguments").path("item").asText()))throw new AssertionError("Unexpected tool replay/input");
                        result=map("content",Arrays.asList(map("type","text","text","7 yuan")));break;
                    default:result=map();
                }
                out.println(json.writeValueAsString(map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result)));
            }
        }
        static Map<String,Object> map(Object... pairs) {Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    }
}
