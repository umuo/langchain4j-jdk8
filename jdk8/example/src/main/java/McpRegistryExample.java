import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.registryclient.DefaultMcpRegistryClient;
import dev.langchain4j.mcp.registryclient.model.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent compatibility BOM consumer: fixed-baseline Registry HTTP, local data only. */
public class McpRegistryExample {
    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new IllegalStateException("Requires Java 8");
        AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String entry="{\"server\":{\"name\":\"io.example/fixture\",\"version\":\"1.0\",\"remotes\":[{\"type\":\"streamable-http\",\"url\":\"http://127.0.0.1/mcp\"}]},\"_meta\":{\"io.modelcontextprotocol.registry/official\":{\"status\":\"active\",\"is_latest\":true}}}";
        server.createContext("/",exchange->{
            try {
                exchange.getRequestBody().close();requests.incrementAndGet();
                if(!"consumer".equals(exchange.getRequestHeaders().getFirst("X-Synthetic")))throw new AssertionError("header lost");
                String path=exchange.getRequestURI().getRawPath();String body;
                if(path.endsWith("/ping"))body="{\"pong\":true}";
                else if(path.endsWith("/health"))body="{\"status\":\"ok\"}";
                else if(path.endsWith("/servers")){
                    if(!"search=fixture%26other%3Dvalue".equals(exchange.getRequestURI().getRawQuery()))throw new AssertionError("query not encoded");
                    body="{\"servers\":["+entry+"],\"metadata\":{\"count\":1}}";
                }else body=entry;
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}
        });server.start();
        try {
            DefaultMcpRegistryClient registry=DefaultMcpRegistryClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort())
                    .headers(Collections.singletonMap("X-Synthetic","consumer")).build();
            McpServerList list=registry.listServers(McpServerListRequest.builder().search("fixture&other=value").build());
            McpGetServerResponse response=registry.getSpecificServerVersion(list.getServers().get(0).getServer().getName(),"latest");
            if(!response.getMeta().getOfficial().isLatest()||!"1.0".equals(response.getServer().getVersion())||!registry.ping().pong()||!"ok".equals(registry.healthCheck().getStatus())||requests.get()!=4)throw new AssertionError("Registry consumer failed");
        }finally{server.stop(0);}
        System.out.println("Java 8 MCP Registry OK; encoded search, version, metadata, ping, health; requests: 4");
    }
}
