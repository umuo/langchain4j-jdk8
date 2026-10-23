package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.agent.*;
import dev.langchain4j.agentic.observability.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticSuccessStreamDrainJdk8Test {
    public interface Writer { @Agent(outputKey="x") @UserMessage("fixture") TokenStream ask(); }
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    @Test(timeout=15000) public void overwrittenIntermediateStreamWaitsForSuccess()throws Exception{child("success");}
    @Test(timeout=15000) public void overwrittenIntermediateStreamErrorFailsRoot()throws Exception{child("error");}
    static void child(String mode)throws Exception{
        Path log=Files.createTempFile(Paths.get("target"),"success-stream-drain-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticSuccessStreamDrainJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(p.waitFor(10,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args)throws Exception{
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);boolean error=args[0].equals("error");
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(5000);ExecutorService fixture=Executors.newSingleThreadExecutor();
        CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1),finish=new CountDownLatch(1),returned=new CountDownLatch(1);
        AtomicReference<Throwable> fixtureError=new AtomicReference<>(),failure=new AtomicReference<>();AtomicReference<String> result=new AtomicReference<>();
        AtomicInteger requests=new AtomicInteger(),successes=new AtomicInteger(),errors=new AtomicInteger(),recoveries=new AtomicInteger();
        okhttp3.Dispatcher dispatcher=new okhttp3.Dispatcher();okhttp3.ConnectionPool pool=new okhttp3.ConnectionPool();
        fixture.submit(()->{try(Socket socket=server.accept()){
            requests.incrementAndGet();AgenticNetworkCancellationJdk8Test.readRequest(socket);
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+AgenticTerminalStreamJdk8Test.event("background")).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();ready.countDown();
            assertTrue(release.await(4,TimeUnit.SECONDS));
            socket.getOutputStream().write((error?"event: error\ndata: background-stream-failure\n\n":"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            if(error)assertEquals("stream error did not release socket",-1,socket.getInputStream().read());closed.countDown();
        }catch(Throwable problem){fixtureError.set(problem);ready.countDown();closed.countDown();}});
        OpenAiStreamingChatModel model=OpenAiStreamingChatModel.builder().apiKey("synthetic-success-drain").modelName("fixture").baseUrl("http://127.0.0.1:"+server.getLocalPort()+"/v1").timeout(Duration.ofSeconds(20)).httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool))).build();
        Writer writer=AgenticServices.agentBuilder(Writer.class).streamingChatModel(model).build();
        Object complete=AgenticServices.nonAiAgentBuilder(scope->{assertTrue(ready.await(3,TimeUnit.SECONDS));scope.writeState("x","replacement");finish.countDown();return "done";}).name("finish").outputKey("answer").build();
        Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(writer,complete).outputKey("answer").errorHandler(context->{recoveries.incrementAndGet();return ErrorRecoveryResult.throwException();})
                .listener(new AgentListener(){
                    @Override public void afterAgentInvocation(AgentResponse response){if(response.agent().type()==Work.class)successes.incrementAndGet();}
                    @Override public void onAgentInvocationError(AgentInvocationError problem){if(problem.agent().type()==Work.class)errors.incrementAndGet();}
                }).build();
        Thread caller=new Thread(()->{try{result.set(work.run("session"));}catch(Throwable problem){failure.set(problem);}finally{returned.countDown();}},"stream-root");
        try{
            caller.start();assertTrue(finish.await(3,TimeUnit.SECONDS));assertNull(fixtureError.get());
            assertFalse("root returned while overwritten stream active",returned.await(150,TimeUnit.MILLISECONDS));assertEquals(0,successes.get());release.countDown();
            assertTrue(returned.await(3,TimeUnit.SECONDS));assertTrue(closed.await(2,TimeUnit.SECONDS));assertNull(fixtureError.get());
            if(error){assertTrue(String.valueOf(failure.get()),failure.get() instanceof AgentInvocationException);Throwable cause=failure.get();while(cause.getCause()!=null)cause=cause.getCause();assertTrue(cause.toString(),cause.getMessage().contains("background-stream-failure"));assertEquals(0,successes.get());assertEquals(1,errors.get());assertEquals(1,recoveries.get());}
            else{assertNull(failure.get());assertEquals("done",result.get());assertEquals(1,successes.get());assertEquals(0,errors.get());assertEquals(0,recoveries.get());}
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(dispatcher.runningCallsCount()!=0&&System.nanoTime()<end)Thread.sleep(2);
            assertEquals(0,dispatcher.runningCallsCount());assertEquals(0,dispatcher.queuedCallsCount());assertEquals(1,requests.get());assertEquals("replacement",work.getAgenticScope("session").readState("x"));assertFalse(dispatcher.executorService().isShutdown());
        }finally{release.countDown();caller.join(2000);assertFalse(caller.isAlive());dispatcher.cancelAll();server.close();fixture.shutdownNow();dispatcher.executorService().shutdownNow();pool.evictAll();assertTrue(fixture.awaitTermination(2,TimeUnit.SECONDS));assertTrue(dispatcher.executorService().awaitTermination(2,TimeUnit.SECONDS));}
    }
}
