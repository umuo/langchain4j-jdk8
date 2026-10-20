import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.McpHeaderEncoding;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Fixed-baseline modern protocol through actual Java 8 HTTP/SSE, with no external server. */
public class McpModernHttpJdk8Test {
    long toolTimeout=2000;
    final Fixture f=new Fixture();final List<String> updates=new CopyOnWriteArrayList<>();
    @Before public void start()throws Exception{f.start();}
    @After public void close()throws Exception{f.stop();}
    void connect(){connect("2026-07-28",false,1000,3000);}
    void connect(String protocol,boolean lists,long resourceTimeout,long transportTimeout){
        f.transport=StreamableHttpMcpTransport.builder().url(f.url).httpClient(f.http).timeout(Duration.ofMillis(transportTimeout))
                .customHeaders(staleHeaders()).build();
        f.client=DefaultMcpClient.builder().transport(f.transport).protocolVersion(protocol).autoHealthCheck(false)
                .subscribeToToolListChanges(lists).subscribeToResourceListChanges(lists).subscribeToPromptListChanges(lists)
                .resourcesTimeout(Duration.ofMillis(resourceTimeout)).promptsTimeout(Duration.ofMillis(resourceTimeout))
                .initializationTimeout(Duration.ofSeconds(2)).protocolDetectionTimeout(Duration.ofMillis(200))
                .multiRoundTripMaxRetries(2).toolExecutionTimeout(Duration.ofMillis(toolTimeout)).onResourceUpdated((c,u)->updates.add(u)).build();
    }
    Map<String,String> staleHeaders(){Map<String,String> m=new HashMap<>();m.put("MCP-Session-Id","stale");m.put("MCP-Protocol-Version","stale");m.put("Mcp-Method","stale");m.put("Mcp-Name","stale");m.put("Mcp-Param-location","stale");return m;}
    CompletableFuture<ToolExecutionResult> call(){return f.client.executeToolAsync(ToolExecutionRequest.builder().id("call").name("天气").arguments("{\"city\":\"上海\"}").build(),null);}
    @Test public void discoveryDoesNotInitializeOrUseSessionHeaders(){
        connect();assertTrue(f.client.isModernProtocol());assertEquals("modern instructions",f.client.instructions());
        assertEquals(1,f.count("server/discover"));assertEquals(0,f.count("initialize"));
        assertNull(f.transport.getMcpSessionId());
        Headers header=f.requestHeaders.get(0);assertEquals("2026-07-28",header.getFirst("MCP-Protocol-Version"));
        assertEquals("server/discover",header.getFirst("Mcp-Method"));assertNull(header.getFirst("MCP-Session-Id"));
        assertNull(header.getFirst("Mcp-Name"));assertNull(header.getFirst("Mcp-Param-location"));
        assertThrows(IllegalStateException.class,()->f.transport.setMcpSessionId("external"));
    }
    @Test public void unicodeNameAndSchemaDeclaredParamsAreEncoded()throws Exception{
        connect();f.client.listTools();assertEquals("sun",call().get(3,TimeUnit.SECONDS).resultText());
        Headers header=f.requestHeaders.get(f.requestHeaders.size()-1);
        assertEquals("tools/call",header.getFirst("Mcp-Method"));assertEquals(McpHeaderEncoding.encode("天气"),header.getFirst("Mcp-Name"));
        assertEquals(McpHeaderEncoding.encode("上海"),header.getFirst("Mcp-Param-location"));assertNull(header.getFirst("MCP-Session-Id"));
        f.client.listTools();assertEquals(1,f.count("tools/list"));
    }
    @Test public void resourcesAndPromptsUseModernHeadersAndMeta(){
        connect();assertEquals("data",((McpTextResourceContents)f.client.readResource("test://资源").contents().get(0)).text());
        assertEquals(McpHeaderEncoding.encode("test://资源"),f.requestHeaders.get(f.requestHeaders.size()-1).getFirst("Mcp-Name"));
        assertEquals(1,f.client.getPrompt("问候",Collections.emptyMap()).messages().size());
        assertEquals(McpHeaderEncoding.encode("问候"),f.requestHeaders.get(f.requestHeaders.size()-1).getFirst("Mcp-Name"));
        assertTrue(f.messages.get(0).path("params").path("_meta").has("io.modelcontextprotocol/protocolVersion"));
    }
    @Test public void modern404FailsOnceWithoutLegacyRecoveryOrReplay()throws Exception{
        connect();f.mode="http404";assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        f.mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(2,f.toolsCalled.get());
        assertEquals(1,f.count("server/discover"));assertEquals(0,f.count("initialize"));
    }
    @Test public void directCancelClosesRequestWithoutCancellationPost()throws Exception{
        connect();f.mode="hold";CompletableFuture<?> pending=call();assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS));pending.cancel(true);f.released();
        assertEquals(0,f.count("notifications/cancelled"));
    }
    @Test public void subscriptionAcknowledgesAndUnsubscribeReleasesStream()throws Exception{
        connect();long id=f.client.subscribeToResources(Arrays.asList("test://data"));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(updates.isEmpty()&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals(Collections.singletonList("test://data"),updates);
        assertTrue(id>=0);f.client.unsubscribeFromResources(id);assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));
        assertEquals(0,f.count("notifications/cancelled"));assertEquals(0,f.gets.get());f.released();
    }
    @Test public void subscriptionIsNotLimitedByUnaryDeadline()throws Exception{
        connect("2026-07-28",false,1000,120);long id=f.client.subscribeToResources(Arrays.asList("test://data"));
        Thread.sleep(300);assertEquals(1,f.activeStreams.get());f.client.unsubscribeFromResources(id);assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));
    }
    @Test public void missingAcknowledgementTimesOutAndReleases()throws Exception{
        f.mode="noAck";connect("2026-07-28",false,150,3000);
        assertThrows(RuntimeException.class,()->f.client.subscribeToResources(Arrays.asList("test://data")));
        assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));f.released();
    }
    @Test public void declinedAcknowledgementCancelsStream()throws Exception{
        f.mode="decline";connect();assertThrows(IllegalStateException.class,()->f.client.subscribeToResources(Arrays.asList("test://data")));
        assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));assertEquals(0,f.count("notifications/cancelled"));
    }
    @Test public void gracefulEndBeforeAcknowledgementFailsCaller()throws Exception{
        f.mode="endBeforeAck";connect();assertThrows(IllegalStateException.class,()->f.client.subscribeToResources(Arrays.asList("test://data")));f.released();
    }
    @Test public void interruptedSubscriptionDoesNotLeakStream()throws Exception{
        f.mode="noAck";connect();AtomicBoolean interrupted=new AtomicBoolean();
        Thread caller=new Thread(()->{try{f.client.subscribeToResources(Arrays.asList("test://data"));}catch(RuntimeException expected){interrupted.set(Thread.currentThread().isInterrupted());}});
        caller.start();assertTrue(f.subscriptionStarted.await(3,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);
        assertFalse(caller.isAlive());assertTrue(interrupted.get());assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));f.released();
    }
    @Test public void subscriptionEofFailsAndNewExplicitSubscribeWorks()throws Exception{
        f.mode="eof";connect();f.client.subscribeToResources(Arrays.asList("test://data"));
        assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));f.mode="normal";
        long id=f.client.subscribeToResources(Arrays.asList("test://data"));f.client.unsubscribeFromResources(id);
        assertEquals(2,f.count("subscriptions/listen"));assertEquals(0,f.gets.get());
    }
    @Test public void autoListSubscriptionInvalidatesToolsCache()throws Exception{
        connect("2026-07-28",true,1000,3000);assertEquals("天气",f.client.listTools().get(0).name());
        f.changed=true;f.notifyList.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(f.count("tools/list")<2&&System.nanoTime()<end){f.client.listTools();Thread.sleep(5);}
        assertEquals("changed",f.client.listTools().get(0).name());assertEquals(1,f.count("subscriptions/listen"));
    }
    @Test public void autoDetectionFallsBackAfterMethodNotFound(){
        f.mode="legacyOnly";connect("",false,1000,3000);assertFalse(f.client.isModernProtocol());assertEquals(1,f.count("server/discover"));assertEquals(1,f.count("initialize"));
    }
    @Test public void versionErrorSelectsSupportedLegacyVersion(){
        f.mode="versionError";connect("",false,1000,3000);assertFalse(f.client.isModernProtocol());assertEquals(1,f.count("initialize"));
        JsonNode init=f.messages.get(f.messages.size()-2);assertEquals("2024-11-05",init.path("params").path("protocolVersion").asText());
    }
    @Test public void legacy2024InitializeRetainsItsVersion(){
        connect("2024-11-05",false,1000,3000);assertFalse(f.client.isModernProtocol());f.client.listTools();
        assertEquals("2024-11-05",f.requestHeaders.get(f.requestHeaders.size()-1).getFirst("MCP-Protocol-Version"));
    }
    @Test public void resourceContinuationCarriesRequestState(){
        f.mode="continue";connect();assertEquals(1,f.client.readResource("test://data").contents().size());
        assertEquals(2,f.count("resources/read"));assertEquals("state1",f.messages.get(f.messages.size()-1).path("params").path("requestState").asText());
    }
    @Test public void modernCloseDoesNotDeleteSession(){
        connect();f.client.close();assertEquals(0,f.deletes.get());
    }
    @Test public void parallelSubscriptionsAreNotStarvedByLongLivedStreams()throws Exception{
        connect();ExecutorService callers=Executors.newFixedThreadPool(8);
        try{
            List<Future<Long>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(callers.submit(()->f.client.subscribeToResources(Arrays.asList("test://data"))));
            List<Long> ids=new ArrayList<>();for(Future<Long> call:calls)ids.add(call.get(3,TimeUnit.SECONDS));
            assertEquals(8,f.activeStreams.get());for(long id:ids)f.client.unsubscribeFromResources(id);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(f.activeStreams.get()!=0&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(0,f.activeStreams.get());
        }finally{callers.shutdownNow();}
    }
    @Test public void interruptedContinuationCancelsCurrentRequest()throws Exception{
        f.mode="continueHold";connect();AtomicBoolean interrupted=new AtomicBoolean();
        Thread caller=new Thread(()->{try{f.client.readResource("test://data");}catch(RuntimeException expected){interrupted.set(Thread.currentThread().isInterrupted());}});
        caller.start();assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);
        assertFalse(caller.isAlive());assertTrue(interrupted.get());f.released();assertEquals(2,f.count("resources/read"));
    }
    @Test public void transportBoundsSubscriptionWithoutAcknowledgement()throws Exception{
        f.mode="noAck";connect("2026-07-28",false,3000,150);
        long start=System.nanoTime();assertThrows(RuntimeException.class,()->f.client.subscribeToResources(Arrays.asList("test://data")));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));
    }
    @Test public void asyncToolContinuationUsesNewIdAndServerState()throws Exception{
        f.mode="toolContinue";connect();f.client.listTools();assertEquals("sun",call().get(3,TimeUnit.SECONDS).resultText());
        assertEquals(2,f.toolsCalled.get());JsonNode last=f.messages.get(f.messages.size()-1);
        assertEquals("tool-state",last.path("params").path("requestState").asText());
        assertEquals(McpHeaderEncoding.encode("上海"),f.requestHeaders.get(f.requestHeaders.size()-1).getFirst("Mcp-Param-location"));
    }
    @Test public void cancellingAsyncContinuationReleasesCurrentHttpRequest()throws Exception{
        f.mode="toolContinueHold";connect();CompletableFuture<?> pending=call();assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS));
        pending.cancel(true);f.released();assertEquals(2,f.toolsCalled.get());assertEquals(0,f.count("notifications/cancelled"));
    }
    @Test public void asyncContinuationLimitFailsWithoutUnboundedToolRequests()throws Exception{
        f.mode="toolLoop";connect();assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));assertEquals(3,f.toolsCalled.get());
    }
    @Test public void interruptedBlockingToolReleasesHttpRequest()throws Exception{
        f.mode="hold";connect();AtomicBoolean interrupted=new AtomicBoolean();
        Thread caller=new Thread(()->{try{f.client.executeTool(ToolExecutionRequest.builder().id("call").name("天气").arguments("{}").build());}catch(RuntimeException expected){interrupted.set(Thread.currentThread().isInterrupted());}});
        caller.start();assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);
        assertFalse(caller.isAlive());assertTrue(interrupted.get());f.released();
    }
    @Test public void discoveryTimeoutFallsBackWithoutLeavingModernRequestActive()throws Exception{
        f.mode="discoverHold";connect("",false,1000,3000);assertFalse(f.client.isModernProtocol());assertEquals(1,f.count("server/discover"));assertEquals(1,f.count("initialize"));f.released();
    }
    @Test public void asyncContinuationTimeoutCancelsCurrentRequest()throws Exception{
        f.mode="toolContinueHold";toolTimeout=150;connect();assertTrue(call().get(3,TimeUnit.SECONDS).isError());
        f.released();assertEquals(2,f.toolsCalled.get());assertEquals(0,f.count("notifications/cancelled"));
    }
    @Test public void responseCannotCompleteAnotherConcurrentHttpRequest()throws Exception{
        f.mode="misroute";toolTimeout=5000;connect();CompletableFuture<?> first=call();assertTrue(f.toolStarted.await(3,TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));assertFalse(first.isDone());first.cancel(true);f.released();
    }
    @Test public void acknowledgementCannotConfirmAnotherConcurrentSubscription()throws Exception{
        f.mode="wrongAck";connect("2026-07-28",false,3000,4000);ExecutorService callers=Executors.newSingleThreadExecutor();
        Future<Long> first=callers.submit(()->f.client.subscribeToResources(Arrays.asList("test://first")));
        try{
            assertTrue(f.subscriptionStarted.await(3,TimeUnit.SECONDS));
            assertThrows(RuntimeException.class,()->f.client.subscribeToResources(Arrays.asList("test://second")));
            assertFalse(first.isDone());first.cancel(true);
        }finally{callers.shutdownNow();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test public void protocolSwitchFromCompletedResponseCallbackDoesNotSeeFalseBusy()throws Exception{
        connect();f.mode="discoverGate";
        CompletableFuture<Void> switched=f.transport.sendRequest(new dev.langchain4j.mcp.protocol.McpServerDiscoverRequest(999L))
                .thenRun(()->f.transport.setModernProtocol(false));
        assertTrue(f.discoveryEntered.await(3,TimeUnit.SECONDS));f.releaseDiscovery.countDown();switched.get(3,TimeUnit.SECONDS);
    }
    @Test public void protocolSwitchWithActiveSubscriptionIsRejected()throws Exception{
        connect();long id=f.client.subscribeToResources(Arrays.asList("test://data"));
        assertThrows(IllegalStateException.class,()->f.transport.setModernProtocol(false));
        f.client.unsubscribeFromResources(id);assertTrue(f.streamClosed.await(3,TimeUnit.SECONDS));
    }
    static class Fixture extends McpJdk8Test {
        final AtomicInteger gets=new AtomicInteger(),deletes=new AtomicInteger(),activeStreams=new AtomicInteger();
        final CountDownLatch streamClosed=new CountDownLatch(1),subscriptionStarted=new CountDownLatch(1),notifyList=new CountDownLatch(1);
        final CountDownLatch discoveryEntered=new CountDownLatch(1),releaseDiscovery=new CountDownLatch(1);
        volatile boolean changed;
        volatile long wrongId;
        volatile long wrongAckId=Long.MIN_VALUE;
        int count(String method){int n=0;for(JsonNode m:messages)if(method.equals(m.path("method").asText()))n++;return n;}
        @Override void handle(HttpExchange e)throws IOException{
            boolean streaming=false;
            try{
                if("GET".equals(e.getRequestMethod())){gets.incrementAndGet();e.getRequestBody().close();e.sendResponseHeaders(405,-1);return;}
                if("DELETE".equals(e.getRequestMethod())){deletes.incrementAndGet();e.getRequestBody().close();e.sendResponseHeaders(204,-1);return;}
                JsonNode request=json.readTree(e.getRequestBody());messages.add(request);requestHeaders.add(e.getRequestHeaders());String method=request.path("method").asText();JsonNode params=request.path("params");
                if(!request.has("id")){if(method.equals("notifications/cancelled"))cancelled.countDown();e.sendResponseHeaders(202,-1);return;}
                Object result;
                switch(method){
                    case "server/discover":
                        if(mode.equals("discoverGate")){discoveryEntered.countDown();releaseDiscovery.await(5,TimeUnit.SECONDS);}
                        if(mode.equals("discoverHold")){releaseTool.await(5,TimeUnit.SECONDS);return;}
                        if(mode.equals("legacyOnly")){reply(e,request,null,map("code",-32601,"message","not found"));return;}
                        if(mode.equals("versionError")){reply(e,request,null,map("code",-32022,"message","unsupported","data",map("supported",Arrays.asList("2024-11-05"))));return;}
                        result=map("supportedVersions",Arrays.asList("2026-07-28"),"capabilities",map("tools",map(),"resources",map(),"prompts",map()),"instructions","modern instructions","_meta",map("io.modelcontextprotocol/serverInfo",map("name","modern","version","1")));break;
                    case "initialize":result=map("protocolVersion",params.path("protocolVersion").asText(),"capabilities",map("tools",map()),"serverInfo",map("name","legacy","version","1"));break;
                    case "tools/list":result=map("tools",Arrays.asList(map("name",changed?"changed":"天气","inputSchema",map("type","object","properties",map("city",map("type","string","x-mcp-header","location"))))));break;
                    case "tools/call":int attempt=toolsCalled.incrementAndGet();
                        if(mode.equals("misroute")){
                            if(attempt==1){wrongId=request.path("id").asLong();toolStarted.countDown();releaseTool.await(5,TimeUnit.SECONDS);return;}
                            reply(e,json.createObjectNode().put("id",wrongId),map("content",Arrays.asList(map("type","text","text","wrong request"))),null);return;
                        }
                        if(mode.equals("toolContinueHold")&&params.has("requestState")){toolStarted.countDown();releaseTool.await(5,TimeUnit.SECONDS);return;}
                        if(mode.equals("toolLoop")||((mode.equals("toolContinue")||mode.equals("toolContinueHold"))&&!params.has("requestState"))){result=map("resultType","input_required","requestState","tool-state");break;}
                        toolStarted.countDown();
                        if(mode.equals("hold")){releaseTool.await(5,TimeUnit.SECONDS);return;}
                        if(mode.equals("http404")){e.sendResponseHeaders(404,-1);return;}
                        result=map("content",Arrays.asList(map("type","text","text","sun")));break;
                    case "resources/read":
                        if(mode.equals("continueHold")&&params.has("requestState")){toolStarted.countDown();releaseTool.await(5,TimeUnit.SECONDS);return;}
                        if((mode.equals("continue")||mode.equals("continueHold"))&&!params.has("requestState"))result=map("resultType","input_required","requestState","state1");
                        else result=map("contents",Arrays.asList(map("uri","test://data","text","data")));break;
                    case "prompts/get":result=map("messages",Arrays.asList(map("role","user","content",map("type","text","text","hello"))));break;
                    case "subscriptions/listen":
                        e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);streaming=true;activeStreams.incrementAndGet();
                        if(mode.equals("wrongAck")&&wrongAckId==Long.MIN_VALUE)wrongAckId=request.path("id").asLong();
                        subscriptionStarted.countDown();
                        OutputStream out=e.getResponseBody();
                        if(mode.equals("endBeforeAck")){event(out,map("jsonrpc","2.0","id",request.path("id").asLong(),"result",map()));return;}
                        if(!mode.equals("noAck")&&!(mode.equals("wrongAck")&&wrongAckId==request.path("id").asLong()))event(out,map("jsonrpc","2.0","method","notifications/subscriptions/acknowledged","params",map("_meta",map("io.modelcontextprotocol/subscriptionId",mode.equals("wrongAck")?wrongAckId:request.path("id").asLong()),"notifications",mode.equals("decline")?map("resourceSubscriptions",Collections.emptyList()):json.convertValue(params.path("notifications"),Map.class))));
                        if(params.path("notifications").has("resourceSubscriptions"))event(out,map("jsonrpc","2.0","method","notifications/resources/updated","params",map("uri","test://data")));
                        if(mode.equals("eof"))return;
                        boolean notified=false;
                        while(!releaseTool.await(20,TimeUnit.MILLISECONDS)){
                            if(!notified&&notifyList.getCount()==0){event(out,map("jsonrpc","2.0","method","notifications/tools/list_changed"));notified=true;}
                            out.write(": heartbeat\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
                        }return;
                    default:result=map();
                }
                // A stateless response must not accidentally create a legacy session.
                e.getResponseHeaders().set("MCP-Session-Id","must-be-ignored-in-modern-mode");reply(e,request,result,null);
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}
            catch(IOException ex){if(!streaming)throw ex;}
            finally{if(streaming){activeStreams.decrementAndGet();streamClosed.countDown();}e.close();}
        }
        void event(OutputStream out,Object event)throws IOException{out.write(("data: "+json.writeValueAsString(event)+"\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();}
    }
}
