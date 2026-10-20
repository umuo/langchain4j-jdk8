import com.sun.net.httpserver.*;
import dev.langchain4j.mcp.registryclient.*;
import dev.langchain4j.mcp.registryclient.model.*;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real Java 8, all fixed-baseline Registry operations against loopback HTTP. */
public class McpRegistryJdk8Test {
    HttpServer server;ExecutorService executor;okhttp3.OkHttpClient raw;
    DefaultMcpRegistryClient client;String url;volatile String path,query,header;
    volatile String mode="normal";
    final CountDownLatch release=new CountDownLatch(1);
    final List<String> queries=new CopyOnWriteArrayList<>();
    static final String ENTRY="{\"server\":{\"name\":\"io.example/fixture\",\"version\":\"1.0\",\"website_url\":\"https://example.invalid\",\"remotes\":[{\"type\":\"streamable-http\",\"url\":\"http://127.0.0.1/mcp\"}],\"packages\":[{\"registryType\":\"npm\",\"identifier\":\"synthetic-package\",\"transport\":{\"type\":\"stdio\"}}]},\"_meta\":{\"io.modelcontextprotocol.registry/official\":{\"status\":\"active\",\"is_latest\":true,\"published_at\":\"2025-09-29T12:00:00Z\"},\"io.modelcontextprotocol.registry/publisher-provided\":{\"custom\":\"synthetic\"}}}";
    @Before public void start() throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",this::handle);server.start();url="http://127.0.0.1:"+server.getAddress().getPort();
        raw=new okhttp3.OkHttpClient.Builder().readTimeout(250,TimeUnit.MILLISECONDS).build();
        client=builder().build();
    }
    DefaultMcpRegistryClient.Builder builder() {
        return DefaultMcpRegistryClient.builder().baseUrl(url).httpClient(new OkHttpClientBuilder().okHttpClientBuilder(raw.newBuilder()).build());
    }
    @After public void stop(){release.countDown();server.stop(0);executor.shutdownNow();raw.dispatcher().executorService().shutdownNow();raw.connectionPool().evictAll();}
    void handle(HttpExchange e) throws IOException {
        try {
            e.getRequestBody().close();path=e.getRequestURI().getRawPath();query=e.getRequestURI().getRawQuery();queries.add(query==null?"":query);
            header=e.getRequestHeaders().getFirst("X-Synthetic");
            if(mode.equals("timeout")){release.await(5,TimeUnit.SECONDS);return;}
            int status=mode.equals("httpError")?503:200;String body;
            if(mode.equals("malformed"))body="{bad json";
            else if(path.endsWith("/ping"))body="{\"pong\":true,\"unknown\":1}";
            else if(path.endsWith("/health"))body="{\"status\":\"ok\",\"github_client_id\":\"synthetic\"}";
            else if(path.endsWith("/servers")||path.endsWith("/versions"))body="{\"servers\":["+ENTRY+"],\"metadata\":{\"count\":1,\"next_cursor\":\""+(query==null||!query.contains("cursor=")?"page+2/=&":"")+"\"}}";
            else body=ENTRY;
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{e.close();}
    }
    @Test public void defaultSpiUsesCompatibleOkHttpAndParsesHealth(){
        DefaultMcpRegistryClient defaults=DefaultMcpRegistryClient.builder().baseUrl(url).build();
        assertTrue(defaults.ping().pong());assertEquals("ok",defaults.healthCheck().getStatus());
    }
    @Test public void allOperationsAndNestedMetadataRoundTrip(){
        McpServerList list=client.listServers(McpServerListRequest.builder().build());assertEquals(1,list.getServers().size());
        McpGetServerResponse item=list.getServers().get(0);assertEquals("io.example/fixture",item.getServer().getName());
        assertEquals("synthetic-package",item.getServer().getPackages().get(0).getIdentifier());
        assertEquals("streamable-http",item.getServer().getRemotes().get(0).getType());
        assertTrue(item.getMeta().getOfficial().isLatest());
        assertEquals(LocalDateTime.of(2025,9,29,12,0),item.getMeta().getOfficial().getPublishedAt());
        assertEquals("synthetic",item.getMeta().publisherProvided().get("custom"));
        assertEquals("1.0",client.getSpecificServerVersion("io.example/fixture","latest").getServer().getVersion());
        assertEquals(1,client.getAllVersionsOfServer("io.example/fixture").getServers().size());
        assertEquals("io.example/fixture",client.getServerDetails("io.example/fixture").getServer().getName());
        assertEquals("synthetic",client.healthCheck().getGithubClientId());assertTrue(client.ping().pong());
    }
    @Test public void queryParametersAreEncodedIndependently() throws Exception {
        client.listServers(McpServerListRequest.builder().cursor("page+2/=&").search("中文 &admin=true # /+")
                .version("1&other=2").limit(7L).updatedSince(LocalDateTime.of(2025,10,1,1,2,3)).build());
        Map<String,String> decoded=new HashMap<>();for(String part:query.split("&")){String[] pair=part.split("=",2);decoded.put(pair[0],URLDecoder.decode(pair[1],"UTF-8"));}
        assertEquals(5,decoded.size());assertEquals("page+2/=&",decoded.get("cursor"));assertEquals("中文 &admin=true # /+",decoded.get("search"));
        assertEquals("1&other=2",decoded.get("version"));assertEquals("2025-10-01T01:02:03Z",decoded.get("updated_since"));assertEquals("7",decoded.get("limit"));
    }
    @Test public void cursorCanBeFedIntoNextPageWithoutInjection(){
        McpServerList first=client.listServers(McpServerListRequest.builder().build());
        client.listServers(McpServerListRequest.builder().cursor(first.getMetadata().getNextCursor()).build());
        assertEquals("cursor=page%2B2%2F%3D%26",query);assertEquals(2,queries.size());
    }
    @Test public void serverAndVersionAreSingleEncodedPathSegments(){
        client.getSpecificServerVersion("io.example/fixture","1.0/variant");
        assertEquals("/v0.1/servers/io.example%2Ffixture/versions/1.0%2Fvariant",path);
        client.getServerDetails("io.example/fixture?admin=true");
        assertEquals("/v0/servers/io.example%2Ffixture%3Fadmin%3Dtrue",path);assertNull(query);
    }
    @Test public void immutableHeaderMapIsAcceptedWithoutMutation(){
        Map<String,String> headers=Collections.singletonMap("X-Synthetic","constant");
        DefaultMcpRegistryClient custom=builder().headers(headers).build();assertTrue(custom.ping().pong());
        assertEquals("constant",header);assertEquals(1,headers.size());
    }
    @Test public void headerSupplierRefreshesOnEveryRequest(){
        java.util.concurrent.atomic.AtomicInteger n=new java.util.concurrent.atomic.AtomicInteger();
        DefaultMcpRegistryClient custom=builder().headersSupplier(()->Collections.singletonMap("X-Synthetic","value"+n.incrementAndGet())).build();
        custom.ping();assertEquals("value1",header);custom.ping();assertEquals("value2",header);
    }
    @Test public void httpErrorReleasesResponseAndNextCallSucceeds(){
        mode="httpError";assertThrows(dev.langchain4j.exception.HttpException.class,()->client.ping());mode="normal";assertTrue(client.ping().pong());
    }
    @Test public void malformedJsonIsTypedAndNextCallSucceeds(){
        mode="malformed";assertThrows(McpRegistryClientException.class,()->client.ping());mode="normal";assertTrue(client.ping().pong());
    }
    @Test public void configuredTimeoutIsHonoredAndCallerClientRemainsUsable() throws Exception {
        mode="timeout";assertThrows(dev.langchain4j.exception.TimeoutException.class,()->client.ping());
        mode="normal";release.countDown();assertTrue(client.ping().pong());assertFalse(raw.dispatcher().executorService().isShutdown());
    }
    @Test public void concurrentQueriesShareCodecWithoutMixingResponses() throws Exception {
        ExecutorService callers=Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<McpRegistryPong>> calls=new ArrayList<>();
            for(int i=0;i<32;i++)calls.add(CompletableFuture.supplyAsync(client::ping,callers));
            for(CompletableFuture<McpRegistryPong> call:calls)assertTrue(call.get(5,TimeUnit.SECONDS).pong());
            assertEquals(32,queries.size());
        } finally {callers.shutdownNow();}
    }
    @Test public void invalidPathArgumentsDoNotIssueHttp(){
        assertThrows(IllegalArgumentException.class,()->client.getSpecificServerVersion("bad?name","latest"));
        assertThrows(IllegalArgumentException.class,()->client.getAllVersionsOfServer(""));assertThrows(IllegalArgumentException.class,()->client.getSpecificServerVersion("io.example/fixture",".."));
        assertThrows(IllegalArgumentException.class,()->client.getServerDetails("."));assertEquals(0,queries.size());
    }
}
