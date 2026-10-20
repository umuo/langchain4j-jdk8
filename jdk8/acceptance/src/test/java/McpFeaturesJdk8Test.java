import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.logging.McpLogMessage;
import dev.langchain4j.mcp.client.progress.McpProgressNotification;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

/** HTTP adapter business coverage beyond tools, using the existing real loopback fixture. */
public class McpFeaturesJdk8Test {
    final Fixture f = new Fixture();
    final List<McpLogMessage> logs = new CopyOnWriteArrayList<>();
    final List<McpProgressNotification> progress = new CopyOnWriteArrayList<>();
    final List<String> updates = new CopyOnWriteArrayList<>();
    @Before public void start() throws Exception { f.start(); }
    @After public void close() throws Exception { f.stop(); }
    void connect(long timeout) {
        f.transport = StreamableHttpMcpTransport.builder().url(f.url).httpClient(f.http).build();
        f.client = DefaultMcpClient.builder().transport(f.transport).autoHealthCheck(false)
                .resourcesTimeout(Duration.ofMillis(timeout)).promptsTimeout(Duration.ofMillis(timeout))
                .logHandler(logs::add).progressHandler(progress::add).onResourceUpdated((c,uri)->updates.add(uri)).build();
    }
    @Test public void paginatedResourcesTemplatesAndPrompts() {
        connect(3000);
        assertEquals(2, f.client.listResources().size());
        assertEquals(2, f.client.listResourceTemplates().size());
        assertEquals(2, f.client.listPrompts().size());
        assertEquals(2, f.count("resources/list"));
        assertEquals(2, f.count("resources/templates/list"));
        assertEquals(2, f.count("prompts/list"));
        f.client.listResources(); f.client.listResourceTemplates(); f.client.listPrompts();
        assertEquals(2, f.count("resources/list"));
        assertEquals(2, f.count("prompts/list"));
    }
    @Test public void textAndBlobResourceContents() {
        connect(3000);
        List<McpResourceContents> contents=f.client.readResource("test://data").contents();
        assertEquals("中文 🚀", ((McpTextResourceContents)contents.get(0)).text());
        McpBlobResourceContents blob=(McpBlobResourceContents)contents.get(1);
        assertEquals("AQID",blob.blob()); assertEquals("application/octet-stream",blob.mimeType());
        assertEquals("test://data", f.messages.get(f.messages.size()-1).path("params").path("uri").asText());
    }
    @Test public void promptArgumentsAndRoleConversion() {
        connect(3000);
        McpGetPromptResult result=f.client.getPrompt("greet",Collections.singletonMap("name","世界"));
        assertEquals("greeting",result.description());
        assertTrue(result.messages().get(0).toChatMessage().toString().contains("Hello 世界"));
        assertEquals(McpRole.ASSISTANT,result.messages().get(1).role());
        assertTrue(result.messages().get(1).toChatMessage().toString().contains("Welcome"));
    }
    @Test public void notificationsInvalidateAllListsAndDeliverStructuredLogs() {
        connect(3000);
        assertEquals("echo",f.client.listTools().get(0).name());
        assertEquals("first",f.client.listResources().get(0).name());
        assertEquals("greet",f.client.listPrompts().get(0).name());
        f.client.listResourceTemplates();
        f.client.readResource("test://notify");
        assertEquals("changed",f.client.listTools().get(0).name());
        assertEquals("changed",f.client.listResources().get(0).name());
        assertEquals("changed",f.client.listPrompts().get(0).name());
        f.client.listResourceTemplates();
        assertEquals(2,f.count("tools/list"));
        assertEquals(4,f.count("resources/list"));
        assertEquals(4,f.count("resources/templates/list"));
        assertEquals(4,f.count("prompts/list"));
        assertEquals(1,logs.size()); assertEquals("synthetic",logs.get(0).dataAsMap().get("source"));
        assertEquals(1,progress.size());
    }
    @Test public void toolChangeDuringFetchDoesNotRestoreStaleCache() {
        connect(3000); f.notifyDuring="tools/list";
        assertEquals("echo",f.client.listTools().get(0).name());
        assertEquals("changed",f.client.listTools().get(0).name());
        f.client.listTools(); assertEquals(2,f.count("tools/list"));
    }
    @Test public void resourceChangeDuringFetchDoesNotRestoreStaleCache() {
        connect(3000); f.notifyDuring="resources/list";
        assertEquals("first",f.client.listResources().get(0).name());
        assertEquals("changed",f.client.listResources().get(0).name());
        f.client.listResources(); assertEquals(4,f.count("resources/list"));
    }
    @Test public void promptChangeDuringFetchDoesNotRestoreStaleCache() {
        connect(3000); f.notifyDuring="prompts/list";
        assertEquals("greet",f.client.listPrompts().get(0).name());
        assertEquals("changed",f.client.listPrompts().get(0).name());
        f.client.listPrompts(); assertEquals(4,f.count("prompts/list"));
    }
    @Test public void resourceSubscriptionsDeliverUpdateAndUnsubscribe() {
        connect(3000); f.client.subscribeToResource("test://data");
        assertEquals(Collections.singletonList("test://data"),updates);
        f.client.unsubscribeFromResource("test://data");
        assertEquals(1,f.count("resources/subscribe")); assertEquals(1,f.count("resources/unsubscribe"));
    }
    @Test public void resourceRpcErrorDoesNotPoisonNextRequest() {
        connect(3000); assertThrows(RuntimeException.class,()->f.client.readResource("test://error"));
        assertEquals(2,f.client.readResource("test://data").contents().size());
    }
    @Test public void promptRpcErrorDoesNotPoisonNextRequest() {
        connect(3000); assertThrows(RuntimeException.class,()->f.client.getPrompt("error",null));
        assertEquals(2,f.client.getPrompt("greet",null).messages().size());
    }
    @Test public void resourceTimeoutCancelsAndReleasesHttp() throws Exception {
        connect(150); assertThrows(RuntimeException.class,()->f.client.readResource("test://hold"));
        assertTrue(f.cancelled.await(3,TimeUnit.SECONDS)); f.released();
    }
    @Test public void promptTimeoutCancelsAndReleasesHttp() throws Exception {
        connect(150); assertThrows(RuntimeException.class,()->f.client.getPrompt("hold",null));
        assertTrue(f.cancelled.await(3,TimeUnit.SECONDS)); f.released();
    }
    @Test public void interruptedResourceReadCancelsAndReleasesHttp() throws Exception {
        connect(5000); AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller=new Thread(()->{
            try { f.client.readResource("test://hold"); }
            catch (RuntimeException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start(); assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS)); caller.interrupt(); caller.join(3000);
        assertFalse(caller.isAlive()); assertTrue(interrupted.get());
        assertTrue(f.cancelled.await(3,TimeUnit.SECONDS)); f.released();
    }
    @Test public void interruptedPromptGetCancelsAndReleasesHttp() throws Exception {
        connect(5000); AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller=new Thread(()->{
            try { f.client.getPrompt("hold",null); }
            catch (RuntimeException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start(); assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS)); caller.interrupt(); caller.join(3000);
        assertFalse(caller.isAlive()); assertTrue(interrupted.get());
        assertTrue(f.cancelled.await(3,TimeUnit.SECONDS)); f.released();
    }
    static class Fixture extends McpJdk8Test {
        volatile boolean changed;
        volatile String notifyDuring;
        int count(String method) { int n=0;for(JsonNode m:messages)if(method.equals(m.path("method").asText()))n++;return n; }
        @Override void handle(HttpExchange e) throws IOException {
            try {
                if ("DELETE".equals(e.getRequestMethod())) { e.getRequestBody().close();e.sendResponseHeaders(405,-1);return; }
                JsonNode request=json.readTree(e.getRequestBody()); messages.add(request);
                String method=request.path("method").asText();JsonNode params=request.path("params");
                if (!request.has("id")) {
                    if (method.equals("notifications/cancelled"))cancelled.countDown();
                    e.sendResponseHeaders(202,-1); return;
                }
                if (params.path("uri").asText().endsWith("hold") || params.path("name").asText().equals("hold")) {
                    toolStarted.countDown();releaseTool.await(10,TimeUnit.SECONDS);return;
                }
                if (params.path("uri").asText().endsWith("error") || params.path("name").asText().equals("error")) {
                    reply(e,request,null,map("code",-32002,"message","synthetic missing entry"));return;
                }
                Object result; List<Object> notifications=new ArrayList<>();
                boolean next=params.has("cursor");
                switch(method) {
                    case "initialize": result=map("protocolVersion","2025-11-25","capabilities",map("tools",map("listChanged",true),"resources",map("subscribe",true,"listChanged",true),"prompts",map("listChanged",true),"logging",map()),"serverInfo",map("name","features","version","1"));break;
                    case "tools/list":result=map("tools",Arrays.asList(map("name",changed?"changed":"echo","inputSchema",map("type","object"))));break;
                    case "resources/list":result=map("resources",Arrays.asList(map("uri",next?"test://second":"test://first","name",changed?"changed":next?"second":"first")));if(!next)((Map<String,Object>)result).put("nextCursor","next");break;
                    case "resources/templates/list":result=map("resourceTemplates",Arrays.asList(map("uriTemplate",next?"test://second/{name}":"test://first/{name}","name","template")));if(!next)((Map<String,Object>)result).put("nextCursor","next");break;
                    case "prompts/list":result=map("prompts",Arrays.asList(map("name",changed?"changed":next?"other":"greet")));if(!next)((Map<String,Object>)result).put("nextCursor","next");break;
                    case "resources/read":
                        result=map("contents",Arrays.asList(map("uri","test://data","text","中文 🚀","mimeType","text/plain"),map("uri","test://blob","blob","AQID","mimeType","application/octet-stream")));
                        if(params.path("uri").asText().equals("test://notify")) {
                            changed=true;
                            for(String name:Arrays.asList("tools","resources","prompts"))notifications.add(map("jsonrpc","2.0","method","notifications/"+name+"/list_changed"));
                            notifications.add(map("jsonrpc","2.0","method","notifications/message","params",map("level","info","data",map("source","synthetic"))));
                            notifications.add(map("jsonrpc","2.0","method","notifications/progress","params",map("progressToken","read","progress",1,"total",2)));
                        } break;
                    case "prompts/get":result=map("description","greeting","messages",Arrays.asList(map("role","user","content",map("type","text","text","Hello "+params.path("arguments").path("name").asText())),map("role","assistant","content",map("type","text","text","Welcome"))));break;
                    case "resources/subscribe":notifications.add(map("jsonrpc","2.0","method","notifications/resources/updated","params",map("uri",params.path("uri").asText())));result=map();break;
                    default: result=map();
                }
                if(method.equals(notifyDuring) && !next && !changed) {
                    changed=true;
                    String name=method.substring(0,method.indexOf('/'));
                    notifications.add(map("jsonrpc","2.0","method","notifications/"+name+"/list_changed"));
                }
                if(notifications.isEmpty())reply(e,request,result,null);
                else {
                    e.getResponseHeaders().set("Content-Type","text/event-stream"); e.sendResponseHeaders(200,0);
                    OutputStream out=e.getResponseBody();
                    for(Object notification:notifications)out.write(("data: "+json.writeValueAsString(notification)+"\n\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("data: "+json.writeValueAsString(map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result))+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
                }
            } catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{e.close();}
        }
    }
}
