package dev.langchain4j.agentic.mcp.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.declarative.*;
import dev.langchain4j.agentic.mcp.*;
import dev.langchain4j.agentic.planner.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.V;
import example.AgenticMcpFixture;
import example.AgenticMcpFixture.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class AgenticMcpJdk8Test {
    AgenticMcpFixture f;
    static McpClient declarativeClient;
    public interface Declarative {
        @McpClientAgent(toolName="echo",outputKey="answer") String echo(@V("input") String value);
        @McpClientSupplier static Object client(){return declarativeClient;}
    }
    @Before public void start()throws Exception{assertTrue(System.getProperty("java.version").startsWith("1.8."));f=new AgenticMcpFixture(1500);}
    @After public void stop()throws Exception{declarativeClient=null;if(f!=null)f.close();}
    Echo echo(boolean async){return McpAgent.builder(f.client,Echo.class).toolName("echo").outputKey("answer").async(async).build();}
    Work work(boolean async){return AgenticServices.sequenceBuilder(Work.class).subAgents(echo(async)).outputKey("answer").build();}
    @Test(timeout=10000) public void typedDirectCallMapsArgumentsAndMetadata()throws Exception{
        Echo agent=echo(false);assertEquals("typed",agent.echo("typed"));assertEquals("typed",f.calls.get(0).path("params").path("arguments").path("input").asText());
        McpClientInstance meta=(McpClientInstance)agent;assertEquals("Input text",meta.arguments().get(0).description());assertEquals(AgenticSystemTopology.NON_AI_AGENT,meta.topology());assertEquals(1,f.calls.size());f.drained();
    }
    @Test(timeout=10000) public void untypedWorkflowKeepsOutputAndInputSnapshot()throws Exception{
        UntypedAgent agent=McpAgent.builder(f.client).toolName("echo").inputKeys("input").outputKey("answer").build();
        UntypedAgent pipeline=AgenticServices.sequenceBuilder().subAgents(agent).outputKey("answer").build();ResultWithAgenticScope<String> result=pipeline.invokeWithAgenticScope(Collections.singletonMap("input","snapshot"));
        assertEquals("snapshot",result.result());result.agenticScope().writeState("input","later");assertEquals("snapshot",result.agenticScope().agentInvocations().get(0).input().get("input"));assertEquals(1,f.calls.size());f.drained();
    }
    @Test(timeout=10000) public void asyncWorkflowSettlesNetworkResultAndKeepsBorrowedClient()throws Exception{Work w=work(true);assertEquals("async",w.run("one","async"));assertEquals("async",w.getAgenticScope("one").readState("answer"));assertFalse(f.http.dispatcher().executorService().isShutdown());f.client.checkHealth();f.drained();}
    @Test(timeout=10000) public void declarativeSpiDiscoversMcpFactory()throws Exception{declarativeClient=f.client;UntypedAgent pipeline=AgenticServices.sequenceBuilder().subAgents(Declarative.class).outputKey("answer").build();assertEquals("spi",pipeline.invoke(Collections.singletonMap("input","spi")));assertEquals(1,f.calls.size());f.drained();}
    @Test(timeout=10000) public void schemaParameterizedTypeHasDefensiveArrayAndOptionalMetadata(){
        UntypedAgent agent=McpAgent.builder(f.client).toolName("echo").build();List<AgentArgument> arguments=((McpClientInstance)agent).arguments();assertEquals(3,arguments.size());
        AgentArgument tags=arguments.stream().filter(a->a.name().equals("tags")).findFirst().get();assertTrue(tags.isOptional());ParameterizedType type=(ParameterizedType)tags.type();assertEquals(List.class,type.getRawType());assertArrayEquals(new Type[]{Integer.class},type.getActualTypeArguments());Type[] copy=type.getActualTypeArguments();copy[0]=String.class;assertEquals(Integer.class,type.getActualTypeArguments()[0]);
        assertThrows(UnsupportedOperationException.class,()->arguments.clear());assertThrows(UnsupportedOperationException.class,()->((McpClientInstance)agent).inputDescriptions().put("bad","bad"));
    }
    @Test(timeout=10000) public void objectMethodsCannotExecuteRemoteTool(){Echo agent=echo(false);assertTrue(agent.equals(agent));assertFalse(agent.equals(new Object()));assertEquals(System.identityHashCode(agent),agent.hashCode());assertTrue(agent.toString().contains("echo"));assertEquals(0,f.calls.size());}
    @Test(timeout=10000) public void activationConditionSkipsWithoutRemoteSideEffect()throws Exception{
        UntypedAgent agent=McpAgent.builder(f.client).toolName("echo").inputKeys("input").outputKey("answer").activationCondition(scope->scope.readState("enabled",false)).build();
        UntypedAgent sequence=AgenticServices.sequenceBuilder().subAgents(agent).outputKey("answer").build();Map<String,Object> values=new HashMap<>();values.put("input","enabled");values.put("enabled",false);assertNull(sequence.invoke(values));assertEquals(0,f.calls.size());values.put("enabled",true);assertEquals("enabled",sequence.invoke(values));assertEquals(1,f.calls.size());f.drained();
    }
    @Test(timeout=10000) public void toolTextCanDecodeStructuredPojo()throws Exception{Data agent=McpAgent.builder(f.client,Data.class).toolName("echo").build();Payload p=agent.read("json");assertEquals("payload",p.text);assertEquals(7,p.count);assertEquals(1,f.calls.size());f.drained();}
    @Test(timeout=10000) public void applicationErrorPropagatesWithoutAutomaticReplay()throws Exception{McpApplicationErrorException e=assertThrows(McpApplicationErrorException.class,()->echo(false).echo("application-error"));assertEquals("application-error",e.getMessage());assertEquals(1,f.calls.size());f.drained();}
    @Test(timeout=10000) public void rpcErrorPropagatesWithoutAutomaticReplay()throws Exception{assertThrows(RuntimeException.class,()->work(false).run("bad","rpc-error"));assertEquals(1,f.calls.size());f.drained();}
    @Test(timeout=10000) public void timeoutCancelsPhysicalCallBeforeFixtureCleanup()throws Exception{
        f.close();f=new AgenticMcpFixture(200);assertThrows(RuntimeException.class,()->echo(false).echo("hold"));assertTrue(f.cancelled.await(3,TimeUnit.SECONDS));assertTrue(f.socketClosed.await(3,TimeUnit.SECONDS));f.drained();assertEquals(1,f.calls.size());assertEquals(f.calls.get(0).path("id").asLong(),f.cancelledId);assertEquals(1,f.cancellationCount.get());assertEquals("healthy",echo(false).echo("healthy"));
    }
    @Test(timeout=10000) public void failedRootInterruptsMcpWorkerAndReleasesConnection()throws Exception{
        AtomicInteger recovery=new AtomicInteger();Object fail=AgenticServices.nonAiAgentBuilder(scope->{assertTrue(f.started.await(3,TimeUnit.SECONDS));throw new IllegalStateException("root-primary");}).name("fail").outputKey("failure").build();
        Work root=AgenticServices.sequenceBuilder(Work.class).subAgents(echo(true),fail).outputKey("answer").errorHandler(error->{recovery.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
        assertThrows(RuntimeException.class,()->root.run("bad","hold"));assertTrue(f.cancelled.await(3,TimeUnit.SECONDS));assertTrue(f.socketClosed.await(3,TimeUnit.SECONDS));f.drained();assertEquals(1,recovery.get());assertEquals(1,f.calls.size());assertEquals(1,f.cancellationCount.get());assertFalse(f.http.dispatcher().executorService().isShutdown());assertEquals("fresh",work(false).run("healthy","fresh"));f.drained();
    }
    @Test(timeout=10000) public void concurrentMemoryIdsDoNotMixMcpArgumentsOrState()throws Exception{
        Work root=work(true);ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<String>> values=new ArrayList<>();for(int i=0;i<8;i++){final String id="id"+i;values.add(pool.submit(()->root.run(id,id)));}for(int i=0;i<8;i++){assertEquals("id"+i,values.get(i).get(4,TimeUnit.SECONDS));assertEquals("id"+i,root.getAgenticScope("id"+i).readState("answer"));}assertEquals(8,f.calls.size());Set<Long> ids=new HashSet<>();f.calls.forEach(c->assertTrue(ids.add(c.path("id").asLong())));f.drained();}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test(timeout=10000) public void fragmentedUtf8SseDecodesInWorkflow()throws Exception{assertEquals("晴天",work(true).run("sse","sse"));assertEquals(1,f.calls.size());f.drained();}
    @Test(timeout=10000) public void stdioAgentUsesRealJava8ChildAndReleasesIt()throws Exception{
        StdioMcpTransport transport=StdioMcpTransport.builder().command(Arrays.asList(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticMcpFixture.StdioServer.class.getName())).build();
        DefaultMcpClient client=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).initializationTimeout(Duration.ofSeconds(5)).build();Process child=transport.getProcess();
        try{Echo agent=McpAgent.builder(client,Echo.class).toolName("echo").async(true).outputKey("answer").build();Work root=AgenticServices.sequenceBuilder(Work.class).subAgents(agent).outputKey("answer").build();assertEquals("stdio",root.run("stdio","stdio"));assertTrue(child.isAlive());client.checkHealth();client.close();assertTrue(child.waitFor(2,TimeUnit.SECONDS));assertFalse(child.isAlive());}
        finally{client.close();transport.close();}
    }
}
