import com.fasterxml.jackson.databind.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real Java 8 and real framed sockets, including fragment/control frames and connection loss. */
public class McpWebSocketJdk8Test {
    LocalMcpWebSocketServer server;
    final ObjectMapper json=new ObjectMapper();
    okhttp3.OkHttpClient http;
    WebSocketMcpTransport transport;DefaultMcpClient client;
    final List<JsonNode> messages=new CopyOnWriteArrayList<>();
    final List<LocalMcpWebSocketServer.Peer> connections=new CopyOnWriteArrayList<>();
    final AtomicInteger toolCalls=new AtomicInteger(),initializations=new AtomicInteger(),lists=new AtomicInteger();
    final CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
    volatile String mode="normal";
    volatile boolean changed;
    @Before public void start()throws Exception{
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        server=new LocalMcpWebSocketServer();server.messages=this::message;
        http=new okhttp3.OkHttpClient();
    }
    WebSocketMcpTransport.Builder builder(){return WebSocketMcpTransport.builder().url(server.url()).httpClient(http)
            .timeout(Duration.ofSeconds(2)).requestTimeout(Duration.ofSeconds(3)).closeTimeout(Duration.ofMillis(150));}
    void connect(){connect(builder(),3000);}
    void connect(WebSocketMcpTransport.Builder builder,long timeout){
        transport=builder.build();client=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false)
                .initializationTimeout(Duration.ofSeconds(3)).toolExecutionTimeout(Duration.ofMillis(timeout)).build();
    }
    @After public void close()throws Exception{
        if(client!=null)client.close();if(transport!=null)transport.close();server.close();
        http.dispatcher().executorService().shutdownNow();http.connectionPool().evictAll();
        assertTrue("Fixture server error: "+server.errors,server.errors.isEmpty());
    }
    CompletableFuture<ToolExecutionResult> call(){return client.executeToolAsync(ToolExecutionRequest.builder().id("call").name("echo").arguments("{}").build(),null);}
    void message(LocalMcpWebSocketServer.Peer p,String text){
        try{
            JsonNode request=json.readTree(text);messages.add(request);
            if(request.has("result")||request.has("error"))return;
            String method=request.path("method").asText();
            if(!request.has("id")){
                if(method.equals("notifications/cancelled"))cancelled.countDown();return;
            }
            Object result;
            if(method.equals("initialize")){
                initializations.incrementAndGet();connections.add(p);
                if(mode.equals("initHold")||(mode.equals("recoveryHold")&&p.number>1))return;
                result=map("protocolVersion",mode.equals("badVersion")?"2099-01-01":"2025-11-25","capabilities",map("tools",map("listChanged",true)),"instructions","socket"+p.number,"serverInfo",map("name","socket"+p.number,"version","1"));
            }else if(method.equals("tools/list")){
                lists.incrementAndGet();result=map("tools",Arrays.asList(map("name",changed?"changed":"echo","inputSchema",map("type","object"))));
            }else if(method.equals("tools/call")){
                toolCalls.incrementAndGet();started.countDown();
                if(mode.equals("hold"))return;
                if(mode.equals("drop")){p.close();return;}
                if(mode.equals("close")){p.frame(8,true,new byte[]{3,(byte)232});return;}
                if(mode.equals("binary")){p.frame(2,true,new byte[]{1,2,3});return;}
                if(mode.equals("rpcError")){p.text(json.writeValueAsString(map("jsonrpc","2.0","id",request.path("id").asLong(),"error",map("code",-32602,"message","bad tool"))));return;}
                if(mode.equals("malformed"))p.text("not-json");
                result=map("content",Arrays.asList(map("type","text","text","中文 🚀 "+p.number)));
            }else result=map();
            String response=json.writeValueAsString(map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result));
            if(mode.equals("fragment"))p.fragmented(response);else p.text(response);
        }catch(Exception failure){throw new RuntimeException(failure);}
    }
    @Test public void ordinaryAndAsyncCallsUseOneHandshake()throws Exception{
        connect();assertEquals("echo",client.listTools().get(0).name());
        assertTrue(call().get(3,TimeUnit.SECONDS).resultText().contains("中文 🚀 1"));
        assertEquals(1,server.connections.get());assertEquals(1,initializations.get());
    }
    @Test public void fragmentedUtf8AndInterleavedPingAreReassembled()throws Exception{
        mode="fragment";connect();assertTrue(call().get(3,TimeUnit.SECONDS).resultText().contains("中文 🚀"));
    }
    @Test public void malformedMessageDoesNotKillReader()throws Exception{
        mode="malformed";connect();assertTrue(call().get(3,TimeUnit.SECONDS).resultText().contains("中文"));
    }
    @Test public void rpcErrorsFailWithoutClosingHealthyChannel()throws Exception{
        mode="rpcError";connect();assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(1,initializations.get());
    }
    @Test public void cancellationNotifiesOriginalChannelAndAllowsNextRequest()throws Exception{
        mode="hold";connect();CompletableFuture<?> pending=call();assertTrue(started.await(3,TimeUnit.SECONDS));
        assertTrue(pending.cancel(true));assertTrue(cancelled.await(3,TimeUnit.SECONDS));
        mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());
    }
    @Test public void clientTimeoutNotifiesWithoutReplay()throws Exception{
        mode="hold";connect(builder(),150);assertTrue(call().get(3,TimeUnit.SECONDS).isError());
        assertTrue(cancelled.await(3,TimeUnit.SECONDS));assertEquals(1,toolCalls.get());
    }
    @Test public void transportDeadlineNotifiesWithoutReplay()throws Exception{
        mode="hold";connect(builder().requestTimeout(Duration.ofMillis(150)),3000);
        assertTrue(call().get(3,TimeUnit.SECONDS).isError());assertTrue(cancelled.await(3,TimeUnit.SECONDS));
    }
    @Test public void droppedToolFailsAndNewCallReinitializesWithoutReplay()throws Exception{
        connect();client.listTools();mode="drop";
        assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        assertEquals(1,toolCalls.get());mode="normal";changed=true;
        assertEquals("changed",client.listTools().get(0).name());
        assertTrue(call().get(3,TimeUnit.SECONDS).resultText().contains("2"));
        assertEquals("socket2",client.instructions());assertEquals(2,initializations.get());assertEquals(2,toolCalls.get());
    }
    @Test public void concurrentNewCallsShareRecoveryAndCancelOneWaiter()throws Exception{
        connect();mode="drop";assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        mode="recoveryHold";List<CompletableFuture<ToolExecutionResult>> pending=new ArrayList<>();
        for(int i=0;i<20;i++)pending.add(call());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(initializations.get()<2&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals(2,initializations.get());assertTrue(pending.get(0).cancel(true));
        CompletableFuture<?> view=transport.sessionInitialization();assertTrue(view.cancel(true));
        JsonNode init=null;for(JsonNode m:messages)if(m.path("method").asText().equals("initialize"))init=m;
        mode="normal";LocalMcpWebSocketServer.Peer peer=connections.get(1);
        peer.text(json.writeValueAsString(map("jsonrpc","2.0","id",init.path("id").asLong(),"result",map("protocolVersion","2025-11-25","capabilities",map("tools",map()),"serverInfo",map("name","recovered","version","1")))));
        for(int i=1;i<pending.size();i++)assertFalse(pending.get(i).get(3,TimeUnit.SECONDS).isError());
        assertEquals(2,initializations.get());assertEquals(20,toolCalls.get());
    }
    @Test public void remoteCloseAndBinaryFailPending()throws Exception{
        mode="binary";connect();assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        mode="close";assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());
    }
    @Test public void pendingLimitRejectsAndRecoversAfterCancel()throws Exception{
        mode="hold";connect(builder().maxPendingRequests(1),3000);
        CompletableFuture<?> first=call();assertTrue(started.await(3,TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));assertEquals(1,toolCalls.get());
        first.cancel(true);mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());
    }
    @Test public void outboundByteLimitRejectsBeforeWireAndKeepsChannel()throws Exception{
        connect(builder().maxQueuedBytes(2048),3000);char[] chars=new char[4096];Arrays.fill(chars,'x');
        CompletableFuture<?> big=client.executeToolAsync(ToolExecutionRequest.builder().id("big").name("echo").arguments("{\"x\":\""+new String(chars)+"\"}").build(),null);
        assertThrows(ExecutionException.class,()->big.get(3,TimeUnit.SECONDS));assertEquals(0,toolCalls.get());
        assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(1,initializations.get());
    }
    @Test public void customHeadersRefreshOnEachUpgrade()throws Exception{
        AtomicInteger headers=new AtomicInteger();connect(builder().headersSupplier(()->Collections.singletonMap("X-Synthetic","v"+headers.incrementAndGet())),3000);
        assertEquals("v1",connections.get(0).headers.get("x-synthetic"));mode="drop";assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));
        mode="normal";call().get(3,TimeUnit.SECONDS);assertEquals("v2",connections.get(1).headers.get("x-synthetic"));
    }
    @Test public void failedInitializationAndUpgradeAreBounded()throws Exception{
        mode="badVersion";assertThrows(RuntimeException.class,()->connect(builder().timeout(Duration.ofMillis(500)),3000));
        transport.close();server.upgradeStatus=401;assertThrows(RuntimeException.class,()->connect(builder().timeout(Duration.ofMillis(500)),3000));
    }
    @Test public void closeDuringUpgradeStopsIoAndIsFinal()throws Exception{
        server.holdUpgrade=true;
        transport=builder().build();
        dev.langchain4j.mcp.client.transport.McpOperationHandler handler=new dev.langchain4j.mcp.client.transport.McpOperationHandler(new ConcurrentHashMap<>(),Collections::emptyList,transport,m->{},()->{},()->{},()->{},u->{},null,null,null,null,null);
        transport.start(handler);transport.close();transport.close();
        assertThrows(IllegalStateException.class,()->transport.start(handler));
        assertThrows(IllegalStateException.class,()->transport.checkHealth());
    }
    @Test public void closeFailsPendingAndBoundsUnresponsivePeer()throws Exception{
        mode="hold";server.ignoreClose=true;connect();CompletableFuture<?> pending=call();assertTrue(started.await(3,TimeUnit.SECONDS));
        long start=System.nanoTime();transport.close();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);
        assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));assertFalse(http.dispatcher().executorService().isShutdown());
    }
    @Test public void listChangedNotificationInvalidatesCache()throws Exception{
        connect();assertEquals("echo",client.listTools().get(0).name());changed=true;
        connections.get(0).text("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(lists.get()<2&&System.nanoTime()<end){client.listTools();Thread.sleep(5);}
        assertEquals("changed",client.listTools().get(0).name());assertEquals(2,lists.get());
    }
    @Test public void failedRecoveryIsBoundedAndNextNewCallCanRetry()throws Exception{
        connect(builder().timeout(Duration.ofMillis(250)),3000);mode="drop";
        assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));mode="recoveryHold";
        assertTrue(call().get(3,TimeUnit.SECONDS).isError());assertEquals(1,toolCalls.get());
        mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(3,initializations.get());assertEquals(2,toolCalls.get());
    }
    @Test public void disconnectedChannelFailsAllInFlightWithoutReplay()throws Exception{
        mode="hold";connect();List<CompletableFuture<ToolExecutionResult>> calls=new ArrayList<>();
        for(int i=0;i<20;i++)calls.add(call());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(toolCalls.get()<20&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals(20,toolCalls.get());connections.get(0).close();
        for(CompletableFuture<?> pending:calls)assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
        mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(21,toolCalls.get());assertEquals(2,initializations.get());
    }
    @Test public void serverCancellationIsNotEchoedBack()throws Exception{
        mode="hold";connect();CompletableFuture<?> pending=call();assertTrue(started.await(3,TimeUnit.SECONDS));
        JsonNode request=null;for(JsonNode m:messages)if(m.path("method").asText().equals("tools/call"))request=m;
        connections.get(0).text(json.writeValueAsString(map("jsonrpc","2.0","method","notifications/cancelled","params",map("requestId",request.path("id").asLong(),"reason","server stopped"))));
        assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
        mode="normal";assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(1,cancelled.getCount());
    }
    @Test public void serverPingAndRootsRequestsReceiveResponses()throws Exception{
        connect();client.setRoots(Arrays.asList(new McpRoot("fixture","file:///synthetic")));
        LocalMcpWebSocketServer.Peer p=connections.get(0);
        p.text("{\"jsonrpc\":\"2.0\",\"id\":900,\"method\":\"ping\"}");
        p.text("{\"jsonrpc\":\"2.0\",\"id\":901,\"method\":\"roots/list\"}");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);JsonNode roots=null,pong=null;
        while(System.nanoTime()<end){for(JsonNode m:messages)if(m.has("result")){if(m.path("id").asLong()==900)pong=m;if(m.path("id").asLong()==901)roots=m;}if(roots!=null&&pong!=null)break;Thread.sleep(5);}
        assertNotNull(pong);assertNotNull(roots);assertEquals("file:///synthetic",roots.path("result").path("roots").get(0).path("uri").asText());
    }
    @Test public void customTlsAndReloadApplyToFutureConnections()throws Exception{
        java.security.KeyStore store=java.security.KeyStore.getInstance("JKS");
        try(InputStream input=getClass().getResourceAsStream("/websocket-fixture.jks")){store.load(input,"synthetic-fixture".toCharArray());}
        javax.net.ssl.KeyManagerFactory km=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());km.init(store,"synthetic-fixture".toCharArray());
        javax.net.ssl.TrustManagerFactory tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
        javax.net.ssl.X509TrustManager manager=(javax.net.ssl.X509TrustManager)tm.getTrustManagers()[0];
        javax.net.ssl.SSLContext context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),new javax.net.ssl.TrustManager[]{manager},null);
        server.close();server=new LocalMcpWebSocketServer(context);server.messages=this::message;
        connect(builder().sslContext(context,manager),3000);assertFalse(call().get(3,TimeUnit.SECONDS).isError());
        transport.reloadSslContext(context,manager);assertEquals(1,initializations.get());
        mode="drop";assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));mode="normal";
        assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(2,initializations.get());
    }
    @Test public void ownedClientAndTimerAreReleasedAfterGracefulClose()throws Exception{
        connect(WebSocketMcpTransport.builder().url(server.url()).closeTimeout(Duration.ofMillis(300)),3000);
        java.lang.reflect.Field field=transport.getClass().getDeclaredField("client");field.setAccessible(true);okhttp3.OkHttpClient owned=(okhttp3.OkHttpClient)field.get(transport);
        java.lang.reflect.Field timerField=transport.getClass().getDeclaredField("timer");timerField.setAccessible(true);
        transport.close();
        assertEquals(1,server.closedFrames.get());assertTrue(owned.dispatcher().executorService().isShutdown());
        assertTrue(((ScheduledThreadPoolExecutor)timerField.get(transport)).isShutdown());
    }
    @Test public void reloadedCallerClientIsUsedOnlyForNewConnectionsAndRemainsOwnedByCaller()throws Exception{
        connect();okhttp3.OkHttpClient replacement=new okhttp3.OkHttpClient();
        try{
            transport.reloadHttpClient(replacement);assertEquals(1,initializations.get());mode="drop";
            assertThrows(ExecutionException.class,()->call().get(3,TimeUnit.SECONDS));mode="normal";
            assertFalse(call().get(3,TimeUnit.SECONDS).isError());assertEquals(2,initializations.get());transport.close();
            assertEquals(Integer.valueOf(42),replacement.dispatcher().executorService().submit(()->42).get(3,TimeUnit.SECONDS));
            assertFalse(http.dispatcher().executorService().isShutdown());
        }finally{replacement.dispatcher().executorService().shutdownNow();replacement.connectionPool().evictAll();}
    }
    @Test public void handshakeTimeoutReleasesWaitingRequest()throws Exception{
        mode="initHold";
        long start=System.nanoTime();assertThrows(RuntimeException.class,()->connect(builder().timeout(Duration.ofMillis(150)),3000));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);assertEquals(1,initializations.get());
    }
    @Test public void duplicateRawRequestIdDoesNotReplaceOriginalFuture()throws Exception{
        mode="hold";connect();
        dev.langchain4j.mcp.protocol.McpCallToolRequest request=new dev.langchain4j.mcp.protocol.McpCallToolRequest(777L,"echo",Collections.emptyMap(),null);
        CompletableFuture<String> first=transport.sendRequest(request);assertTrue(started.await(3,TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,()->transport.sendRequest(request).get(3,TimeUnit.SECONDS));assertEquals(1,toolCalls.get());
        connections.get(0).text("{\"jsonrpc\":\"2.0\",\"id\":777,\"result\":{\"content\":[]}}");
        assertTrue(first.get(3,TimeUnit.SECONDS).contains("content"));
    }
    static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
}
