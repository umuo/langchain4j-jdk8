package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.internal.DelayedResponse;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.openai.*;
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

public class AgenticNetworkCancellationJdk8Test {
    public interface Stream { @Agent(outputKey="x") @UserMessage("fixture") TokenStream ask(); }
    public interface Chat { @Agent(outputKey="x") @UserMessage("fixture") String ask(); }
    public interface Work extends AgenticScopeAccess { @Agent String run(@MemoryId String id); }
    @Test(timeout=15000) public void syncStreamBeforeHeaders() throws Exception { child("sync-header"); }
    @Test(timeout=15000) public void asyncStreamBeforeHeaders() throws Exception { child("async-header"); }
    @Test(timeout=15000) public void syncStreamAfterToken() throws Exception { child("sync-token"); }
    @Test(timeout=15000) public void asyncStreamAfterToken() throws Exception { child("async-token"); }
    @Test(timeout=15000) public void asyncChatBeforeHeaders() throws Exception { child("chat-header"); }
    @Test(timeout=15000) public void directAsyncHttpBeforeHeaders() throws Exception { child("http-async"); }
    @Test(timeout=15000) public void callbackToolContinuationRetainsCancellationOwner() throws Exception { child("tool-next"); }
    public static class Lookup {
        final AtomicInteger calls = new AtomicInteger();
        @dev.langchain4j.agent.tool.Tool public String lookup() { calls.incrementAndGet(); return "synthetic-tool-result"; }
    }
    static String readRequest(Socket socket) throws Exception {
        socket.setSoTimeout(7000);InputStream in=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int match=0,b;
        byte[] end={13,10,13,10};while(match<4&&(b=in.read())!=-1){headers.write(b);match=b==end[match]?match+1:(b==13?1:0);}assertEquals(4,match);
        String h=headers.toString("US-ASCII");int length=0;for(String line:h.split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
        ByteArrayOutputStream body=new ByteArrayOutputStream();for(int n=0;n<length;n++){b=in.read();assertTrue(b!=-1);body.write(b);}return body.toString("UTF-8");
    }
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"network-cancel-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticNetworkCancellationJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("child timed out: "+mode,p.waitFor(9,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally {if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        CountDownLatch ready=new CountDownLatch(1),closed=new CountDownLatch(1);AtomicReference<Throwable> serverFailure=new AtomicReference<>();AtomicInteger errors=new AtomicInteger();
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(7000);ExecutorService fixture=Executors.newSingleThreadExecutor();
        okhttp3.Dispatcher dispatcher=new okhttp3.Dispatcher();okhttp3.ConnectionPool pool=new okhttp3.ConnectionPool();
        fixture.submit(()->{try {
            if(mode.equals("tool-next"))try(Socket first=server.accept()) {
                assertTrue(readRequest(first).contains("lookup"));
                String tool="HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                    +"data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"tool-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}\n\n"
                    +"data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
                first.getOutputStream().write(tool.getBytes(StandardCharsets.UTF_8));first.getOutputStream().flush();
            }
            try(Socket socket=server.accept()) {
                String body=readRequest(socket);InputStream in=socket.getInputStream();
                if(mode.equals("tool-next")){assertTrue(body.contains("tool_call_id"));assertTrue(body.contains("synthetic-tool-result"));}
                if(mode.endsWith("token")){
                    String event="HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"first\"}}]}\n\n";
                    socket.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                }
                ready.countDown();assertEquals("peer did not close",-1,in.read());closed.countDown();
            }
        }catch(Throwable failure){serverFailure.set(failure);ready.countDown();}});
        Lookup tool = new Lookup();
        AtomicReference<CompletableFuture<?>> directHttp = new AtomicReference<>();
        try {
            String base="http://127.0.0.1:"+server.getLocalPort()+"/v1";
            OkHttpClientBuilder transport=new OkHttpClientBuilder().okHttpClientBuilder(new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool));
            Object producer=mode.equals("http-async") ? AgenticServices.nonAiAgentBuilder(scope -> {
                directHttp.set(transport.build().executeAsync(dev.langchain4j.http.client.HttpRequest.builder().method(dev.langchain4j.http.client.HttpMethod.POST).url(base+"/chat/completions").body("{}").build()));
                return "started";
            }).name("direct-http").outputKey("x").build() : mode.startsWith("chat")?AgenticServices.agentBuilder(Chat.class).chatModel(OpenAiChatModel.builder().apiKey("synthetic-fixture").modelName("fixture").baseUrl(base).timeout(Duration.ofSeconds(20)).maxRetries(0).httpClientBuilder(transport).build()).async(true).build()
                :AgenticServices.agentBuilder(Stream.class).streamingChatModel(OpenAiStreamingChatModel.builder().apiKey("synthetic-fixture").modelName("fixture").baseUrl(base).timeout(Duration.ofSeconds(20)).httpClientBuilder(transport).build()).async(mode.startsWith("async")).tools(mode.equals("tool-next") ? new Object[]{tool} : new Object[0]).build();
            Object bad=AgenticServices.nonAiAgentBuilder(scope->{assertTrue(ready.await(3,TimeUnit.SECONDS));assertNull(serverFailure.get());throw new IllegalStateException("primary failure");}).name("bad").outputKey("z").build();
            Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(producer,bad).outputKey("z").errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
            try {work.run("failed");fail("must fail");}catch(AgentInvocationException e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();assertEquals("primary failure",cause.getMessage());}
            assertTrue("HTTP connection still open after root failed",closed.await(2,TimeUnit.SECONDS));assertNull(serverFailure.get());
            if (mode.equals("http-async")) { assertTrue(directHttp.get().isCancelled()); } else {
            Object pending=work.getAgenticScope("failed").state().get("x");assertTrue(pending instanceof DelayedResponse<?>);assertTrue("stream waiter still pending",((DelayedResponse<?>)pending).isDone());
            try {work.getAgenticScope("failed").readState("x");fail("expected cancelled result");}catch(CancellationException expected){}
            }
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(dispatcher.runningCallsCount()!=0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(0,dispatcher.runningCallsCount());assertEquals(0,dispatcher.queuedCallsCount());assertEquals("cancel must not retry",1,errors.get());if(mode.equals("tool-next"))assertEquals(1,tool.calls.get());
        }finally {dispatcher.cancelAll();server.close();fixture.shutdownNow();dispatcher.executorService().shutdownNow();pool.evictAll();assertTrue(fixture.awaitTermination(2,TimeUnit.SECONDS));assertTrue(dispatcher.executorService().awaitTermination(2,TimeUnit.SECONDS));}
    }
}
