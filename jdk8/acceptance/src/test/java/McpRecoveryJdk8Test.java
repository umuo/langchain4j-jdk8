import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Session generations and SSE recovery over real Java 8 loopback connections. */
public class McpRecoveryJdk8Test {
    interface Handler { void run(HttpExchange exchange, JsonNode request) throws Exception; }
    final ObjectMapper json = new ObjectMapper();
    HttpServer server;
    ExecutorService workers;
    OkHttpClient http;
    StreamableHttpMcpTransport transport;
    DefaultMcpClient client;
    String url;
    final AtomicInteger initializes = new AtomicInteger(), initialized = new AtomicInteger(), posts = new AtomicInteger(), gets = new AtomicInteger(), deletes = new AtomicInteger(), cancellations = new AtomicInteger();
    final List<String> deletedSessions = new CopyOnWriteArrayList<>();
    final List<String> getCursors = new CopyOnWriteArrayList<>(), getSessions = new CopyOnWriteArrayList<>();
    final List<String> postSessions = new CopyOnWriteArrayList<>(), methods = new CopyOnWriteArrayList<>();
    final List<Long> getTimes = new CopyOnWriteArrayList<>();
    final List<Throwable> serverErrors = new CopyOnWriteArrayList<>();
    final AtomicInteger notifications = new AtomicInteger();
    volatile Handler onGet = (exchange, request) -> status(exchange,405);
    volatile Handler onCall = (exchange, request) -> result(exchange,request,toolResult("ok"));
    volatile Handler onDelete = (exchange, request) -> status(exchange,204);
    volatile Handler beforeInitialize = (exchange, request) -> {};
    volatile long requestTimeout = 3000, closeTimeout = 300;
    volatile int reconnects = 2;
    boolean ownTransportClient;
    final CountDownLatch unblock = new CountDownLatch(1);

    @Before public void start() throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/mcp",exchange->{
            try {
                String method=exchange.getRequestMethod();methods.add(method);
                if (!method.equals("POST")) exchange.getRequestBody().close();
                if(method.equals("GET")) {
                    gets.incrementAndGet();getTimes.add(System.nanoTime());
                    getCursors.add(String.valueOf(exchange.getRequestHeaders().getFirst("Last-Event-ID")));
                    getSessions.add(String.valueOf(exchange.getRequestHeaders().getFirst("MCP-Session-Id")));
                    assertEquals("text/event-stream",exchange.getRequestHeaders().getFirst("Accept"));
                    assertEquals("2025-11-25",exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                    assertEquals("synthetic",exchange.getRequestHeaders().getFirst("X-Token"));
                    onGet.run(exchange,null);return;
                }
                if(method.equals("DELETE")) {
                    deletes.incrementAndGet();deletedSessions.add(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));
                    assertEquals("2025-11-25",exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                    assertEquals("synthetic",exchange.getRequestHeaders().getFirst("X-Token"));
                    onDelete.run(exchange,null);return;
                }
                JsonNode request=json.readTree(exchange.getRequestBody());
                String rpc=request.path("method").asText();
                if(rpc.equals("initialize")) {
                    initializes.incrementAndGet();
                    assertNull(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));
                    assertNull(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                    assertNull(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                    beforeInitialize.run(exchange,request);
                    if(!exchange.getResponseHeaders().containsKey("X-Handled")) {
                        exchange.getResponseHeaders().set("MCP-Session-Id","session-"+initializes.get());
                        result(exchange,request,McpJdk8Test.map("protocolVersion","2025-11-25","capabilities",McpJdk8Test.map("tools",McpJdk8Test.map()),"serverInfo",McpJdk8Test.map("name","fixture-"+initializes.get(),"version","1")));
                    }
                } else if(rpc.equals("notifications/initialized")) {initialized.incrementAndGet();status(exchange,202);}
                else if(rpc.equals("notifications/cancelled")) {cancellations.incrementAndGet();status(exchange,202);}
                else if(rpc.equals("tools/call")) {posts.incrementAndGet();postSessions.add(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));onCall.run(exchange,request);}
                else if(rpc.equals("tools/list")) result(exchange,request,McpJdk8Test.map("tools",Arrays.asList(McpJdk8Test.map("name","tool-"+initializes.get(),"inputSchema",McpJdk8Test.map("type","object","properties",McpJdk8Test.map())))));
                else if(rpc.equals("ping")) result(exchange,request,McpJdk8Test.map());
                else status(exchange,202);
            } catch(IOException expectedDisconnectedSocket) {
                // Cancellation/close deliberately disconnect streaming sockets; do not hide assertion failures.
            } catch(Throwable error) {serverErrors.add(error);}
            finally {exchange.close();}
        });
        server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/mcp";
        http=new OkHttpClient.Builder().readTimeout(10,TimeUnit.SECONDS).build();
    }
    void connect(boolean subsidiary) {
        transport=StreamableHttpMcpTransport.builder().url(url).httpClient(ownTransportClient?null:http).subsidiaryChannel(subsidiary)
                .timeout(Duration.ofMillis(requestTimeout)).closeTimeout(Duration.ofMillis(closeTimeout))
                .reconnectInterval(Duration.ofMillis(30)).maxReconnectAttempts(reconnects)
                .customHeaders((McpHeadersSupplier)context->new HashMap<String,String>(){{put("X-Token","synthetic");put("MCP-Session-Id","stale");put("MCP-Protocol-Version","bad");put("Last-Event-ID","stale-cursor");}}).build();
        client=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false)
                .initializationTimeout(Duration.ofSeconds(4)).toolExecutionTimeout(Duration.ofSeconds(5))
                .listener(new McpClientListener(){@Override public void onNotificationToolsListChanged(){notifications.incrementAndGet();}}).build();
    }
    @After public void stop() throws Exception {
        unblock.countDown();
        if(client!=null)client.close();if(transport!=null)transport.close();
        server.stop(0);workers.shutdownNow();http.dispatcher().executorService().shutdownNow();http.connectionPool().evictAll();
        assertTrue("Server assertion: "+serverErrors,serverErrors.isEmpty());
    }
    static Object toolResult(String text){return McpJdk8Test.map("content",Arrays.asList(McpJdk8Test.map("type","text","text",text)));}
    static void status(HttpExchange exchange,int status)throws IOException{exchange.sendResponseHeaders(status,-1);}
    void result(HttpExchange exchange,JsonNode request,Object result)throws IOException{
        byte[] body=json.writeValueAsBytes(McpJdk8Test.map("jsonrpc","2.0","id",request.path("id").longValue(),"result",result));
        exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
    }
    OutputStream sse(HttpExchange exchange)throws IOException{
        exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);return exchange.getResponseBody();
    }
    void event(OutputStream output,String id,String data)throws IOException{
        output.write(((id==null?"":"id: "+id+"\r\n")+"data: "+data+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));output.flush();
    }
    void text(OutputStream output,String text)throws IOException{output.write(text.getBytes(StandardCharsets.UTF_8));output.flush();}
    String response(JsonNode request,String text)throws Exception{return json.writeValueAsString(McpJdk8Test.map("jsonrpc","2.0","id",request.path("id").longValue(),"result",toolResult(text)));}
    CompletableFuture<ToolExecutionResult> call(){return call("tool");}
    CompletableFuture<ToolExecutionResult> call(String name){return client.executeToolAsync(ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments("{}").build(),InvocationContext.builder().build());}
    void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue("Condition timed out",condition.getAsBoolean());
    }
    void released()throws Exception{await(()->http.dispatcher().runningCallsCount()==0&&http.dispatcher().queuedCallsCount()==0);}
    void assertFailure(CompletableFuture<?> future)throws Exception{assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));}
    OkHttpClient transportHttp(String field)throws Exception{
        java.lang.reflect.Field declared=StreamableHttpMcpTransport.class.getDeclaredField(field);declared.setAccessible(true);return (OkHttpClient)declared.get(transport);
    }
    void streamsReleased()throws Exception{
        OkHttpClient streams=transportHttp("streamClient");await(()->streams.dispatcher().runningCallsCount()==0&&streams.dispatcher().queuedCallsCount()==0);
    }
    static final String CHANGED="{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}";

    @Test public void expiredToolFailsAndNextCallUsesNewSessionWithoutReplay()throws Exception{
        onCall=(exchange,request)->{if("session-1".equals(exchange.getRequestHeaders().getFirst("MCP-Session-Id")))status(exchange,404);else result(exchange,request,toolResult("new session"));};
        connect(false);assertEquals("tool-1",client.listTools().get(0).name());assertFailure(call());
        await(()->initialized.get()==2);transport.sessionInitialization().get(5,TimeUnit.SECONDS);assertEquals(1,posts.get());
        assertEquals("tool-2",client.listTools().get(0).name());
        assertEquals("new session",call().get(5,TimeUnit.SECONDS).resultText());assertEquals(2,posts.get());
        assertEquals(Arrays.asList("session-1","session-2"),postSessions);
    }
    @Test public void concurrentExpiredResponsesShareOneHandshakeAndCancelledWaiterDoesNotCancelIt()throws Exception{
        CountDownLatch recovering=new CountDownLatch(1), allPosted=new CountDownLatch(8), answerOld=new CountDownLatch(1);
        beforeInitialize=(exchange,request)->{if(initializes.get()>1){recovering.countDown();unblock.await(5,TimeUnit.SECONDS);}};
        onCall=(exchange,request)->{if("session-1".equals(exchange.getRequestHeaders().getFirst("MCP-Session-Id"))){allPosted.countDown();answerOld.await(5,TimeUnit.SECONDS);status(exchange,404);}else result(exchange,request,toolResult("ok"));};
        // Allow all eight old-session HTTP requests to be on the wire before any 404 arrives.
        http.dispatcher().setMaxRequestsPerHost(16);connect(false);
        List<CompletableFuture<ToolExecutionResult>> old=new ArrayList<>();for(int i=0;i<8;i++)old.add(call());
        assertTrue(allPosted.await(5,TimeUnit.SECONDS));answerOld.countDown();assertTrue(recovering.await(5,TimeUnit.SECONDS));
        for(CompletableFuture<?> result:old)assertFailure(result);
        CompletableFuture<ToolExecutionResult> abandoned=call(),remaining=call();abandoned.cancel(true);
        assertEquals(8,posts.get());unblock.countDown();assertEquals("ok",remaining.get(5,TimeUnit.SECONDS).resultText());
        assertEquals(2,initializes.get());assertEquals(9,posts.get());assertEquals(0,cancellations.get());
    }
    @Test public void failedRecoveryCanBeRetriedByLaterNewRequest()throws Exception{
        beforeInitialize=(exchange,request)->{if(initializes.get()==2){exchange.getResponseHeaders().set("X-Handled","true");status(exchange,503);}};
        onCall=(exchange,request)->{if(posts.get()==1)status(exchange,404);else result(exchange,request,toolResult("recovered"));};
        connect(false);assertFailure(call());await(()->initializes.get()==2);released();
        assertEquals("recovered",call().get(5,TimeUnit.SECONDS).resultText());assertEquals(3,initializes.get());assertEquals(2,posts.get());
    }
    @Test public void unsolicitedGetReceivesNotificationsAndReconnectsWithItsCursor()throws Exception{
        CountDownLatch secondGet=new CountDownLatch(1);
        onGet=(exchange,request)->{
            OutputStream output=sse(exchange);
            if(gets.get()==1){event(output,"channel-a",CHANGED);text(output,"retry: 80\n");}
            else {event(output,"channel-b",CHANGED);secondGet.countDown();unblock.await(5,TimeUnit.SECONDS);}
        };
        connect(true);assertTrue(secondGet.await(5,TimeUnit.SECONDS));await(()->notifications.get()==2);
        assertEquals(Arrays.asList("null","channel-a"),getCursors);assertEquals(Arrays.asList("session-1","session-1"),getSessions);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(getTimes.get(1)-getTimes.get(0))>=70);
        assertEquals("ok",call().get(5,TimeUnit.SECONDS).resultText());
    }
    @Test public void get405DisablesOptionalChannelWithoutBreakingTools()throws Exception{
        connect(true);await(()->gets.get()==1);transport.subsidiaryChannelCompletion().get(5,TimeUnit.SECONDS);transport.checkHealth();
        assertEquals("ok",call().get(5,TimeUnit.SECONDS).resultText());assertEquals(1,gets.get());
    }
    @Test public void get404ReinitializesAndNewChannelDoesNotReuseOldCursor()throws Exception{
        onGet=(exchange,request)->{
            if(gets.get()==1){OutputStream output=sse(exchange);event(output,"old-channel",CHANGED);}
            else if(gets.get()==2)status(exchange,404);
            else {OutputStream output=sse(exchange);event(output,"new-channel",CHANGED);unblock.await(5,TimeUnit.SECONDS);}
        };
        connect(true);await(()->gets.get()==3);await(()->initialized.get()==2);
        assertEquals(Arrays.asList("null","old-channel","null"),getCursors);
        assertEquals(Arrays.asList("session-1","session-1","session-2"),getSessions);
        assertEquals("ok",call().get(5,TimeUnit.SECONDS).resultText());
    }
    @Test public void postSseResumesOnlyViaGetWithOriginalRequestAndSingleToolPost()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();
        onCall=(exchange,request)->{response.set(response(request,"恢复成功"));OutputStream output=sse(exchange);event(output,"tool-a","");text(output,"retry: 75\r\n");};
        onGet=(exchange,request)->event(sse(exchange),"tool-b",response.get());
        connect(false);long started=System.nanoTime();assertEquals("恢复成功",call().get(5,TimeUnit.SECONDS).resultText());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>=65);assertEquals(1,posts.get());assertEquals(Arrays.asList("tool-a"),getCursors);released();
    }
    @Test public void twoPostStreamsKeepIndependentRecoveryCursors()throws Exception{
        Map<String,String> responses=new ConcurrentHashMap<>();
        onCall=(exchange,request)->{String name=request.path("params").path("name").asText();responses.put(name,response(request,name));event(sse(exchange),name,"");};
        onGet=(exchange,request)->{String cursor=exchange.getRequestHeaders().getFirst("Last-Event-ID");event(sse(exchange),cursor+"-done",responses.get(cursor));};
        connect(false);CompletableFuture<ToolExecutionResult> first=call("first"),second=call("second");
        assertEquals("first",first.get(5,TimeUnit.SECONDS).resultText());assertEquals("second",second.get(5,TimeUnit.SECONDS).resultText());
        assertEquals(new HashSet<>(Arrays.asList("first","second")),new HashSet<>(getCursors));assertEquals(2,posts.get());released();
    }
    @Test public void retryBudgetStopsEmptyResumedStreams()throws Exception{
        onCall=(exchange,request)->event(sse(exchange),"cursor","");onGet=(exchange,request)->sse(exchange);
        connect(false);assertFailure(call());assertEquals(2,gets.get());assertEquals(1,posts.get());released();
    }
    @Test public void originalPostWithoutCursorIsNeverReplayed()throws Exception{
        onCall=(exchange,request)->event(sse(exchange),null,CHANGED);
        connect(false);assertFailure(call());assertEquals(0,gets.get());assertEquals(1,posts.get());released();
    }
    @Test public void resetsEmptyCursorAndDoesNotCommitUnterminatedEvents()throws Exception{
        onCall=(exchange,request)->{OutputStream output=sse(exchange);event(output,"committed","");text(output,"id: uncommitted\ndata: {broken");};
        onGet=(exchange,request)->{OutputStream output=sse(exchange);event(output,"","");};
        connect(false);assertFailure(call());assertEquals(Arrays.asList("committed"),getCursors);assertEquals(1,posts.get());released();
    }
    @Test public void cancellationWhileWaitingToResumeStopsGetAndNotifiesOnce()throws Exception{
        onCall=(exchange,request)->{OutputStream output=sse(exchange);event(output,"wait","");text(output,"retry: 5000\n");};
        connect(false);CompletableFuture<ToolExecutionResult> result=call();await(()->posts.get()==1);released();result.cancel(true);
        await(()->cancellations.get()==1);assertEquals(0,gets.get());transport.terminateSession().get(5,TimeUnit.SECONDS);assertEquals(0,gets.get());
    }
    @Test public void timeoutIncludesAllResumeAttemptsAndCancelsGet()throws Exception{
        requestTimeout=180;
        onCall=(exchange,request)->{OutputStream output=sse(exchange);event(output,"wait","");text(output,"retry: 5000\n");};
        connect(false);assertTrue(call().get(5,TimeUnit.SECONDS).resultText().contains("timeout"));await(()->cancellations.get()==1);assertEquals(0,gets.get());released();
    }
    @Test public void closeDuringPendingGetReleasesSessionAndTerminatesChannel()throws Exception{
        CountDownLatch connected=new CountDownLatch(1);
        onGet=(exchange,request)->{sse(exchange);connected.countDown();unblock.await(5,TimeUnit.SECONDS);};
        connect(true);assertTrue(connected.await(5,TimeUnit.SECONDS));CompletableFuture<String> channel=transport.subsidiaryChannelCompletion();
        transport.terminateSession().get(5,TimeUnit.SECONDS);channel.get(5,TimeUnit.SECONDS);
        assertEquals(Arrays.asList("session-1"),deletedSessions);assertFalse(http.dispatcher().executorService().isShutdown());released();
        assertThrows(IllegalStateException.class,transport::checkHealth);
    }
    @Test public void deleteIsIdempotentUnderConcurrentCloseAndDoesNotStopCallerClient()throws Exception{
        connect(false);ExecutorService closers=Executors.newFixedThreadPool(6);
        try{List<Future<?>> closes=new ArrayList<>();for(int i=0;i<12;i++)closes.add(closers.submit(transport::close));for(Future<?> close:closes)close.get(5,TimeUnit.SECONDS);}
        finally{closers.shutdownNow();}
        assertEquals(1,deletes.get());assertEquals(Arrays.asList("session-1"),deletedSessions);assertFalse(http.dispatcher().executorService().isShutdown());
        // Another transport really uses the same supplied client after the first has closed.
        StreamableHttpMcpTransport first=transport;DefaultMcpClient original=client;connect(false);assertEquals("ok",call().get(5,TimeUnit.SECONDS).resultText());original.close();first.close();
    }
    @Test public void delete405IsNormalLifecycleCompletion()throws Exception{
        onDelete=(exchange,request)->status(exchange,405);connect(false);transport.terminateSession().get(5,TimeUnit.SECONDS);assertEquals(1,deletes.get());
    }
    @Test public void deleteErrorsAreVisibleButLocalWorkStillStops()throws Exception{
        onDelete=(exchange,request)->status(exchange,503);connect(false);assertFailure(transport.terminateSession());assertEquals(1,deletes.get());assertThrows(IllegalStateException.class,transport::checkHealth);
    }
    @Test public void deleteTimeoutIsBoundedAndDoesNotLeaveQueuedCalls()throws Exception{
        onDelete=(exchange,request)->unblock.await(5,TimeUnit.SECONDS);closeTimeout=100;connect(false);
        long begin=System.nanoTime();assertFailure(transport.terminateSession());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<1500);released();
    }
    @Test public void noPostReplayOn503RetryAfterZeroOr307Redirect()throws Exception{
        onCall=(exchange,request)->{exchange.getResponseHeaders().set("Retry-After","0");status(exchange,503);};
        connect(false);assertFailure(call());assertEquals(1,posts.get());
        onCall=(exchange,request)->{exchange.getResponseHeaders().set("Location",url);status(exchange,307);};
        assertFailure(call());assertEquals(2,posts.get());released();
    }
    @Test public void malformedSseIsNotRetriedAsIfItWereANetworkFailure()throws Exception{
        onCall=(exchange,request)->event(sse(exchange),"broken","{bad");connect(false);assertFailure(call());assertEquals(0,gets.get());assertEquals(1,posts.get());released();
    }
    @Test public void repeatedGetFailuresStopAtConfiguredBudget()throws Exception{
        onGet=(exchange,request)->status(exchange,503);connect(true);
        await(()->gets.get()==3);assertFailure(transport.subsidiaryChannelCompletion());assertThrows(RuntimeException.class,transport::checkHealth);assertEquals(3,gets.get());
    }
    @Test public void retryZeroAndInvalidRetryFieldsAndSplitUtf8AreHandled()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();
        onCall=(exchange,request)->{response.set(response(request,"中文"));OutputStream output=sse(exchange);text(output,"\uFEFFretry: 0\rretry: -1\rretry: nope\rid: utf8\rdata:\r\r");};
        onGet=(exchange,request)->{OutputStream output=sse(exchange);byte[] bytes=("data: "+response.get()+"\r\r").getBytes(StandardCharsets.UTF_8);for(byte b:bytes){output.write(b);output.flush();}};
        connect(false);assertEquals("中文",call().get(5,TimeUnit.SECONDS).resultText());assertEquals(Arrays.asList("utf8"),getCursors);assertEquals(1,posts.get());released();
    }
    @Test public void delete404IsNormalLifecycleCompletion()throws Exception{
        onDelete=(exchange,request)->status(exchange,404);connect(false);transport.terminateSession().get(5,TimeUnit.SECONDS);assertEquals(1,deletes.get());streamsReleased();
    }
    @Test public void cancellingActiveResumedGetStopsIoAndNotifiesOnce()throws Exception{
        CountDownLatch active=new CountDownLatch(1);
        onCall=(exchange,request)->event(sse(exchange),"active","");
        onGet=(exchange,request)->{OutputStream output=sse(exchange);text(output,": reading\n\n");active.countDown();unblock.await(5,TimeUnit.SECONDS);};
        connect(false);CompletableFuture<ToolExecutionResult> result=call();assertTrue(active.await(5,TimeUnit.SECONDS));result.cancel(true);
        await(()->cancellations.get()==1);streamsReleased();released();assertEquals(1,posts.get());assertEquals(1,gets.get());
    }
    @Test public void timeoutCancelsActiveResumedGetAndReturnsModelVisibleError()throws Exception{
        requestTimeout=250;onCall=(exchange,request)->event(sse(exchange),"active","");
        onGet=(exchange,request)->{OutputStream output=sse(exchange);text(output,": active\n\n");unblock.await(5,TimeUnit.SECONDS);};
        connect(false);assertTrue(call().get(5,TimeUnit.SECONDS).resultText().contains("timeout"));await(()->cancellations.get()==1);streamsReleased();released();assertEquals(1,posts.get());
    }
    @Test public void closingDuringBackoffCancelsTimerAndDoesNotOpenAnotherGet()throws Exception{
        CountDownLatch read=new CountDownLatch(1);
        onGet=(exchange,request)->{OutputStream output=sse(exchange);text(output,"retry: 5000\n");event(output,"background","");read.countDown();};
        connect(true);assertTrue(read.await(5,TimeUnit.SECONDS));transport.terminateSession().get(5,TimeUnit.SECONDS);streamsReleased();assertEquals(1,gets.get());assertEquals(1,deletes.get());
    }
    @Test public void resumedGet404FailsOriginalRequestAndReinitializesWithoutPostReplay()throws Exception{
        onCall=(exchange,request)->event(sse(exchange),"expired-tool","");onGet=(exchange,request)->status(exchange,404);
        connect(false);assertFailure(call());await(()->initialized.get()==2);transport.sessionInitialization().get(5,TimeUnit.SECONDS);
        assertEquals(1,posts.get());assertEquals(1,gets.get());assertEquals(Arrays.asList("expired-tool"),getCursors);
        onCall=(exchange,request)->result(exchange,request,toolResult("next"));assertEquals("next",call().get(5,TimeUnit.SECONDS).resultText());assertEquals(2,posts.get());streamsReleased();
    }
    @Test public void sessionless404DoesNotTriggerSpuriousReinitialization()throws Exception{
        beforeInitialize=(exchange,request)->{exchange.getResponseHeaders().set("X-Handled","true");result(exchange,request,McpJdk8Test.map("protocolVersion","2025-11-25","capabilities",McpJdk8Test.map("tools",McpJdk8Test.map())));};
        onCall=(exchange,request)->status(exchange,404);connect(false);assertFailure(call());assertEquals(1,initializes.get());assertNull(transport.getMcpSessionId());transport.terminateSession().get(5,TimeUnit.SECONDS);assertEquals(0,deletes.get());
    }
    @Test public void recoveryHandshakeTimeoutStopsWaitingRequestsWithoutPostingThem()throws Exception{
        requestTimeout=200;
        beforeInitialize=(exchange,request)->{if(initializes.get()>1)unblock.await(5,TimeUnit.SECONDS);};
        onCall=(exchange,request)->status(exchange,404);connect(false);assertFailure(call());await(()->initializes.get()==2);
        CompletableFuture<ToolExecutionResult> waiting=call();waiting.get(5,TimeUnit.SECONDS);
        await(()->transport.sessionInitialization().isCompletedExceptionally());assertEquals(1,posts.get());assertEquals(2,initializes.get());released();
    }
    @Test public void abruptPostSocketFailureIsNotRetried()throws Exception{
        onCall=(exchange,request)->exchange.close();connect(false);assertFailure(call());assertEquals(1,posts.get());assertEquals(0,gets.get());released();
    }
    @Test public void ownedClientsAndRecoveryTimerAreStoppedAfterDelete()throws Exception{
        ownTransportClient=true;connect(true);await(()->gets.get()==1);transport.subsidiaryChannelCompletion().get(5,TimeUnit.SECONDS);transport.terminateSession().get(5,TimeUnit.SECONDS);
        assertTrue(transportHttp("client").dispatcher().executorService().isShutdown());assertTrue(transportHttp("streamClient").dispatcher().executorService().isShutdown());
        java.lang.reflect.Field field=StreamableHttpMcpTransport.class.getDeclaredField("timer");field.setAccessible(true);assertTrue(((ScheduledThreadPoolExecutor)field.get(transport)).isShutdown());
        assertEquals(0,transportHttp("client").connectionPool().connectionCount());streamsReleased();
    }

    @Test public void get503RetryAfterZeroStillUsesTransportRetryBudget()throws Exception{
        onGet=(exchange,request)->{exchange.getResponseHeaders().set("Retry-After","0");status(exchange,503);};
        connect(true);assertFailure(transport.subsidiaryChannelCompletion());
        assertEquals(3,gets.get());
        for(int i=1;i<getTimes.size();i++)assertTrue(TimeUnit.NANOSECONDS.toMillis(getTimes.get(i)-getTimes.get(i-1))>=20);
        streamsReleased();
    }

}
