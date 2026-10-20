import com.fasterxml.jackson.databind.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Same protocol contract against actual Java 8 stdio child and actual framed WebSocket. */
@RunWith(Parameterized.class)
public class McpModernChannelsJdk8Test {
    @Parameterized.Parameters(name="{0}") public static Object[] channels(){return new Object[]{"stdio","websocket"};}
    @Parameterized.Parameter public String channel;
    static final ObjectMapper JSON=new ObjectMapper();
    final List<JsonNode> wire=new CopyOnWriteArrayList<>();
    final List<String> updates=new CopyOnWriteArrayList<>();
    final CountDownLatch started=new CountDownLatch(1);
    LocalMcpWebSocketServer server;okhttp3.OkHttpClient http;McpTransport transport;DefaultMcpClient client;
    volatile String mode="normal";long requestTimeout=3000;boolean autoHealth;Path generationFile;
    boolean websocket(){return channel.equals("websocket");}
    void connect(){connect("2026-07-28",false);}
    void connect(String version,boolean autoLists){
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        try{
            if(websocket()){
                http=new okhttp3.OkHttpClient();server=new LocalMcpWebSocketServer();
                Map<LocalMcpWebSocketServer.Peer,ModernMcpChannelFixture> protocols=new ConcurrentHashMap<>();
                server.messages=(peer,text)->{
                    try{wire.add(JSON.readTree(text));protocols.computeIfAbsent(peer,p->new ModernMcpChannelFixture(()->mode,p.number,s->{try{p.text(s);}catch(IOException e){throw new UncheckedIOException(e);}},p::close)).accept(text);}
                    catch(Exception e){throw new RuntimeException(e);}
                };
                transport=WebSocketMcpTransport.builder().url(server.url()).httpClient(http).timeout(Duration.ofMillis(600))
                        .requestTimeout(Duration.ofMillis(requestTimeout)).closeTimeout(Duration.ofMillis(100)).build();
            }else{
                if(mode.equals("generations")){generationFile=Files.createTempFile(Paths.get(System.getProperty("user.dir"),"target"),"mcp-child-generation-",".txt");Files.write(generationFile,"0".getBytes(StandardCharsets.UTF_8));}
                String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
                transport=StdioMcpTransport.builder().command(Arrays.asList(System.getProperty("java.home")+"/bin/java","-cp",cp,Child.class.getName(),mode,generationFile==null?"-":generationFile.toString())).build();
            }
            client=DefaultMcpClient.builder().transport(transport).protocolVersion(version).autoHealthCheck(autoHealth).autoHealthCheckInterval(Duration.ofMillis(50))
                    .initializationTimeout(Duration.ofSeconds(2)).protocolDetectionTimeout(Duration.ofMillis(200))
                    .toolExecutionTimeout(Duration.ofMillis(requestTimeout)).resourcesTimeout(Duration.ofMillis(500))
                    .subscribeToToolListChanges(autoLists).subscribeToPromptListChanges(autoLists).subscribeToResourceListChanges(autoLists)
                    .progressHandler(p->started.countDown()).onResourceUpdated((c,u)->updates.add(u)).build();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    CompletableFuture<ToolExecutionResult> call(String action){return client.executeToolAsync(ToolExecutionRequest.builder().id("call").name("echo").arguments("{\"action\":\""+action+"\"}").build(),null);}
    JsonNode status()throws Exception{return JSON.readTree(call("status").get(3,TimeUnit.SECONDS).resultText());}
    @After public void close()throws Exception{
        if(client!=null)client.close();if(transport!=null)transport.close();
        if(server!=null){server.close();assertTrue("Fixture errors: "+server.errors,server.errors.isEmpty());}
        if(http!=null){http.dispatcher().executorService().shutdownNow();http.connectionPool().evictAll();}
        if(generationFile!=null)Files.deleteIfExists(generationFile);
    }
    @Test public void modernDiscoverAndUnicodeTool()throws Exception{
        connect();assertTrue(client.isModernProtocol());assertEquals("channel-1",client.instructions());
        assertEquals("中文 🚀",call("echo").get(3,TimeUnit.SECONDS).resultText());
        assertEquals(1,status().path("discovers").asInt());assertEquals(0,status().path("initializes").asInt());
    }
    @Test public void resourceAndPromptAndAcknowledgedSubscription()throws Exception{
        connect();assertEquals("data",((McpTextResourceContents)client.readResource("test://data").contents().get(0)).text());
        assertEquals(1,client.getPrompt("hello",Collections.emptyMap()).messages().size());
        long id=client.subscribeToResources(Arrays.asList("test://data"));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(updates.isEmpty()&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals(Collections.singletonList("test://data"),updates);client.unsubscribeFromResources(id);
        assertEquals(1,status().path("cancellations").asInt());
    }
    @Test public void unsubscribeDoesNotSendDuplicateCancellation()throws Exception{
        connect();long id=client.subscribeToResources(Arrays.asList("test://data"));client.unsubscribeFromResources(id);
        JsonNode result=status();assertEquals(1,result.path("cancellations").asInt());assertEquals(0,result.path("activeSubscriptions").asInt());
    }
    @Test public void subscriptionSurvivesUnaryTimeoutAfterAck()throws Exception{
        requestTimeout=120;connect();long id=client.subscribeToResources(Arrays.asList("test://data"));Thread.sleep(300);
        assertEquals(1,status().path("activeSubscriptions").asInt());client.unsubscribeFromResources(id);assertEquals(1,status().path("cancellations").asInt());
    }
    @Test public void missingAckTimeoutCancelsExactlyOnce()throws Exception{
        mode="noAck";connect();assertThrows(RuntimeException.class,()->client.subscribeToResources(Arrays.asList("test://data")));
        assertEquals(1,status().path("cancellations").asInt());assertEquals(0,status().path("activeSubscriptions").asInt());
    }
    @Test public void declinedAckCancelsExactlyOnce()throws Exception{
        mode="decline";connect();assertThrows(IllegalStateException.class,()->client.subscribeToResources(Arrays.asList("test://data")));
        assertEquals(1,status().path("cancellations").asInt());
    }
    @Test public void callerCancellationNotifiesOnlyCurrentContinuation()throws Exception{
        connect();CompletableFuture<?> pending=call("continueHold");assertTrue(started.await(3,TimeUnit.SECONDS));pending.cancel(true);
        JsonNode result=status();assertEquals(1,result.path("cancellations").asInt());assertEquals(1,result.path("continuations").asInt());
    }
    @Test public void serverCancellationIsNotEchoed()throws Exception{
        connect();assertThrows(ExecutionException.class,()->call("serverCancel").get(3,TimeUnit.SECONDS));assertEquals(0,status().path("cancellations").asInt());
    }
    @Test public void asyncStateContinuationExecutesOnce()throws Exception{
        connect();assertEquals("中文 🚀",call("continue").get(3,TimeUnit.SECONDS).resultText());
        JsonNode status=status();assertEquals(1,status.path("executed").asInt());assertEquals(1,status.path("continuations").asInt());
    }
    @Test public void modernErrorFallsBackToLegacy()throws Exception{
        mode="legacyOnly";connect("",false);assertFalse(client.isModernProtocol());assertEquals("中文 🚀",call("echo").get(3,TimeUnit.SECONDS).resultText());
        assertEquals(1,status().path("initializes").asInt());
    }
    @Test public void legacy2024StillWorks()throws Exception{
        connect("2024-11-05",false);assertFalse(client.isModernProtocol());assertEquals("中文 🚀",call("echo").get(3,TimeUnit.SECONDS).resultText());
    }
    @Test public void closeFailsToolAndReleasesChannel()throws Exception{
        connect();Process child=transport instanceof StdioMcpTransport?((StdioMcpTransport)transport).getProcess():null;
        CompletableFuture<?> pending=call("hold");assertTrue(started.await(3,TimeUnit.SECONDS));client.close();
        assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));if(child!=null)assertFalse(child.isAlive());
    }
    @Test public void continuationTimeoutNotifiesExactlyOnce()throws Exception{
        requestTimeout=150;connect();assertTrue(call("continueHold").get(3,TimeUnit.SECONDS).isError());
        assertEquals(1,status().path("cancellations").asInt());
    }
    @Test public void listSubscriptionSurvivesUnaryTimeout()throws Exception{
        requestTimeout=150;connect("2026-07-28",true);Thread.sleep(300);
        assertEquals(1,status().path("activeSubscriptions").asInt());assertEquals("echo",client.listTools().get(0).name());
        call("listChange").get(3,TimeUnit.SECONDS);assertEquals("changed",client.listTools().get(0).name());
    }
    @Test public void interruptingAckWaitReleasesSubscription()throws Exception{
        mode="noAck";connect();AtomicBoolean interrupted=new AtomicBoolean();
        Thread caller=new Thread(()->{try{client.subscribeToResources(Arrays.asList("test://data"));}catch(RuntimeException expected){interrupted.set(Thread.currentThread().isInterrupted());}});
        caller.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(status().path("activeSubscriptions").asInt()==0&&System.nanoTime()<end)Thread.sleep(5);
        caller.interrupt();caller.join(3000);assertFalse(caller.isAlive());assertTrue(interrupted.get());assertEquals(1,status().path("cancellations").asInt());
    }
    @Test public void restartRefreshesCapabilitiesAndToolCacheWithoutReplayingLostTool()throws Exception{
        mode="generations";autoHealth=!websocket();connect("2026-07-28",true);
        assertEquals("echo1",client.listTools().get(0).name());
        assertThrows(ExecutionException.class,()->call("drop").get(3,TimeUnit.SECONDS));
        if(!websocket()){
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!"channel-2".equals(client.instructions())&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals("channel-2",client.instructions());
            assertEquals("2",new String(Files.readAllBytes(generationFile),StandardCharsets.UTF_8));
        }
        assertEquals("echo2",client.listTools().get(0).name());assertEquals("channel-2",client.instructions());
        assertEquals("中文 🚀",call("echo").get(3,TimeUnit.SECONDS).resultText());
        assertEquals(1,status().path("executed").asInt());assertEquals(1,status().path("activeSubscriptions").asInt());
    }
    @Test public void automaticModernNegotiationUsesSelectedVersionOnSubsequentRequests()throws Exception{
        connect("",false);assertTrue(client.isModernProtocol());
        assertEquals("中文 🚀",call("echo").get(3,TimeUnit.SECONDS).resultText());client.checkHealth();
        assertEquals(0,status().path("badMetadata").asInt());
    }
    public static class Child {
        public static void main(String[] args)throws Exception{
            if(!System.getProperty("java.version").startsWith("1.8."))System.exit(91);
            PrintWriter out=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
            int generation=1;
            if(args[0].equals("generations")){Path file=Paths.get(args[1]);generation=Integer.parseInt(new String(Files.readAllBytes(file),StandardCharsets.UTF_8))+1;Files.write(file,Integer.toString(generation).getBytes(StandardCharsets.UTF_8));}
            ModernMcpChannelFixture protocol=new ModernMcpChannelFixture(()->args[0],generation,out::println,()->System.exit(3));
            BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
            while((line=reader.readLine())!=null)protocol.accept(line);
        }
    }
    static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
