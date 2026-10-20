import com.fasterxml.jackson.databind.*;
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Actual modern framed connections: recovery handshake, pending work and subscription ownership. */
public class McpModernWebSocketRecoveryJdk8Test {
    final McpModernChannelsJdk8Test f=new McpModernChannelsJdk8Test();
    @Before public void start(){f.channel="websocket";}
    @After public void close()throws Exception{f.close();}
    long count(String method){return f.wire.stream().filter(m->method.equals(m.path("method").asText())).count();}
    void loseConnection()throws Exception{assertThrows(ExecutionException.class,()->f.call("drop").get(3,TimeUnit.SECONDS));}
    void waitForRecoveryDiscovery()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(count("server/discover")<2&&System.nanoTime()<end)Thread.sleep(5);assertEquals(2,count("server/discover"));
    }
    @Test public void concurrentWaitersShareDiscoverAndOneCancellationDoesNotCancelHandshake()throws Exception{
        f.connect();loseConnection();f.mode="recoveryHold";
        List<CompletableFuture<ToolExecutionResult>> calls=new ArrayList<>();for(int i=0;i<20;i++)calls.add(f.call("echo"));
        waitForRecoveryDiscovery();calls.get(0).cancel(true);((WebSocketMcpTransport)f.transport).sessionInitialization().cancel(true);
        JsonNode discover=null;for(JsonNode m:f.wire)if(m.path("method").asText().equals("server/discover"))discover=m;
        assertTrue(discover.path("id").asLong()<0);f.mode="normal";
        LocalMcpWebSocketServer.Peer peer=null;for(LocalMcpWebSocketServer.Peer p:f.server.peers)if(p.number==2)peer=p;assertNotNull(peer);
        peer.text(McpModernChannelsJdk8Test.JSON.writeValueAsString(McpModernChannelsJdk8Test.map("jsonrpc","2.0","id",discover.path("id").asLong(),"result",McpModernChannelsJdk8Test.map("supportedVersions",Arrays.asList("2026-07-28"),"instructions","channel-2","capabilities",McpModernChannelsJdk8Test.map("tools",McpModernChannelsJdk8Test.map())))));
        for(int i=1;i<calls.size();i++)assertFalse(calls.get(i).get(3,TimeUnit.SECONDS).isError());
        assertEquals(2,count("server/discover"));assertEquals(20,count("tools/call"));assertEquals("channel-2",f.client.instructions());
    }
    @Test public void failedRecoveryDoesNotReplayAndLaterNewRequestCanRecover()throws Exception{
        f.connect();loseConnection();f.mode="recoveryHold";assertTrue(f.call("echo").get(3,TimeUnit.SECONDS).isError());
        assertEquals(1,count("tools/call"));f.mode="normal";assertFalse(f.call("echo").get(3,TimeUnit.SECONDS).isError());
        assertEquals(3,count("server/discover"));assertEquals(2,count("tools/call"));assertEquals("channel-3",f.client.instructions());
    }
    @Test public void oldSubscriptionCancellationIsNotSentOnNewConnection()throws Exception{
        f.connect();long old=f.client.subscribeToResources(Arrays.asList("test://old"));loseConnection();
        assertEquals(0,f.status().path("activeSubscriptions").asInt());f.client.unsubscribeFromResources(old);
        assertEquals(0,count("notifications/cancelled"));
        long fresh=f.client.subscribeToResources(Arrays.asList("test://new"));f.client.unsubscribeFromResources(fresh);
        assertEquals(1,f.status().path("cancellations").asInt());
        for(JsonNode m:f.wire)if(m.path("method").asText().equals("notifications/cancelled")){
            assertEquals(fresh,m.path("params").path("requestId").asLong());
            assertEquals("2026-07-28",m.path("params").path("_meta").path("io.modelcontextprotocol/protocolVersion").asText());
        }
    }
    @Test public void closingRecoveryFailsWaitersAndDoesNotOpenAnotherConnection()throws Exception{
        f.connect();loseConnection();f.mode="recoveryHold";CompletableFuture<?> waiting=f.call("echo");waitForRecoveryDiscovery();
        f.client.close();assertThrows(ExecutionException.class,()->waiting.get(3,TimeUnit.SECONDS));
        assertEquals(2,f.server.connections.get());assertEquals(1,count("tools/call"));assertFalse(f.http.dispatcher().executorService().isShutdown());
    }
    @Test public void healthProbeRecoveryRefreshesCapabilitiesAndListSubscription()throws Exception{
        f.mode="generations";f.connect("2026-07-28",true);assertEquals("echo1",f.client.listTools().get(0).name());
        loseConnection();f.client.checkHealth();
        assertEquals("channel-2",f.client.instructions());assertEquals("echo2",f.client.listTools().get(0).name());
        assertEquals(1,f.status().path("activeSubscriptions").asInt());assertEquals(2,count("server/discover"));assertEquals(2,count("subscriptions/listen"));
    }

}
