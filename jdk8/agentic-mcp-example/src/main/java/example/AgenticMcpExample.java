package example;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.declarative.*;
import dev.langchain4j.agentic.mcp.McpAgent;
import dev.langchain4j.agentic.patterns.debate.ConvergenceStrategy;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.V;
import dev.langchain4j.spi.ExecutorProvider;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Independent experimental BOM consumer; loopback only, no model or service credentials. */
public final class AgenticMcpExample {
    static McpClient current;
    public interface Declarative {
        @McpClientAgent(toolName="echo",outputKey="answer",async=true) String echo(@V("input") String input);
        @McpClientSupplier static Object client(){return current;}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        check(System.getProperty("java.version").startsWith("1.8."),"actual Java8 required");
        ExecutorService workers=Executors.newFixedThreadPool(2);ExecutorProvider previous=ExecutorProvider.get();ExecutorProvider.set(()->workers);
        try{
            try(AgenticMcpFixture fixture=new AgenticMcpFixture(2000)){
                current=fixture.client;
                AgenticMcpFixture.Work root=AgenticServices.sequenceBuilder(AgenticMcpFixture.Work.class).subAgents(Declarative.class).outputKey("answer").build();
                check("bom-http".equals(root.run("http","bom-http")),"MCP/Agentic declarative async result");
                check("bom-http".equals(root.getAgenticScope("http").readState("answer")),"result state");
                AgenticMcpFixture.Echo echo=McpAgent.builder(fixture.client,AgenticMcpFixture.Echo.class).toolName("echo").build();
                check(echo.equals(echo)&&echo.hashCode()==System.identityHashCode(echo)&&echo.toString().contains("echo"),"safe proxy identity");
                check(fixture.calls.size()==1,"identity methods sent a remote tool");fixture.drained();fixture.client.checkHealth();fixture.drained();
            }finally{current=null;}
            // Reader and stderr are blocking transport I/O; keep them off the bounded agent worker pool.
            ExecutorService io=Executors.newFixedThreadPool(2);
            try {
            StdioMcpTransport transport=StdioMcpTransport.builder().executorService(io).command(Arrays.asList(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),AgenticMcpFixture.StdioServer.class.getName())).build();
            DefaultMcpClient client=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).initializationTimeout(Duration.ofSeconds(5)).build();Process process=transport.getProcess();
            try{
                AgenticMcpFixture.Echo echo=McpAgent.builder(client,AgenticMcpFixture.Echo.class).toolName("echo").outputKey("answer").async(true).build();
                AgenticMcpFixture.Work root=AgenticServices.sequenceBuilder(AgenticMcpFixture.Work.class).subAgents(echo).outputKey("answer").build();
                check("bom-stdio".equals(root.run("stdio","bom-stdio")),"stdio result");client.close();check(process.waitFor(2,TimeUnit.SECONDS)&&!process.isAlive(),"stdio child leaked");
            }finally{client.close();transport.close();}
            check(!io.isShutdown(),"transport closed borrowed I/O executor");
            }finally{io.shutdownNow();check(io.awaitTermination(2,TimeUnit.SECONDS),"stdio reader threads leaked");}
            check(ConvergenceStrategy.unanimous().hasConverged(Arrays.<Object>asList("yes","yes")),"patterns BOM positive");
            check(!ConvergenceStrategy.unanimous().hasConverged(Arrays.<Object>asList("yes","no")),"patterns BOM negative");
            check(!workers.isShutdown(),"library closed borrowed executor");
        }finally{ExecutorProvider.set(previous);workers.shutdownNow();check(workers.awaitTermination(2,TimeUnit.SECONDS),"worker leak");}
        System.out.println("Java 8 experimental Agentic MCP BOM OK; declarative async HTTP/state, safe identity, stdio child released, patterns strategy, borrowed executor retained");
    }
}
