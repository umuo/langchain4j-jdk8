package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.InvocationTasks;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
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

public class AgenticTerminalStreamJdk8Test {
    public interface Writer { @Agent(outputKey="x") @UserMessage("fixture") TokenStream ask(); }
    public interface Work extends AgenticScopeAccess { @Agent TokenStream run(@MemoryId String id); }
    @Test(timeout=15000) public void syncTerminalCancelledBeforeHeaders() throws Exception { child("sync-header"); }
    @Test(timeout=15000) public void asyncTerminalCancelledBeforeHeaders() throws Exception { child("async-header"); }
    @Test(timeout=15000) public void syncTerminalCancelledAfterFirstChunk() throws Exception { child("sync-token"); }
    @Test(timeout=15000) public void asyncTerminalCancelledAfterFirstChunk() throws Exception { child("async-token"); }
    @Test(timeout=15000) public void syncCancelledRootDoesNotStartTerminalModel() throws Exception { child("sync-before"); }
    @Test(timeout=15000) public void asyncCancelledRootDoesNotStartTerminalModel() throws Exception { child("async-before"); }
    @Test(timeout=15000) public void syncNewRootCancellationDoesNotCancelOldTerminal() throws Exception { child("sync-generation"); }
    @Test(timeout=15000) public void asyncNewRootCancellationDoesNotCancelOldTerminal() throws Exception { child("async-generation"); }
    @Test(timeout=15000) public void syncTerminalSuccessKeepsCapturedOwner() throws Exception { child("sync-success"); }
    @Test(timeout=15000) public void asyncTerminalSuccessKeepsCapturedOwner() throws Exception { child("async-success"); }
    @Test(timeout=15000) public void syncTerminalHttpErrorKeepsCapturedOwner() throws Exception { child("sync-error"); }
    @Test(timeout=15000) public void asyncTerminalHttpErrorKeepsCapturedOwner() throws Exception { child("async-error"); }
    @Test(timeout=15000) public void syncTerminalSseErrorClosesConnectionWithoutRootCancel() throws Exception { child("sync-event"); }
    @Test(timeout=15000) public void asyncTerminalSseErrorClosesConnectionWithoutRootCancel() throws Exception { child("async-event"); }
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"terminal-stream-",".log");
        Process process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticTerminalStreamJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue("child timed out: "+mode,process.waitFor(9,TimeUnit.SECONDS)); assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,process.exitValue()); }
        finally { if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(2,TimeUnit.SECONDS));} Files.deleteIfExists(log); }
    }
    static String event(String text) { return "data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""+text+"\"}}]}\n\n"; }
    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8.")); AgenticScopePersister.setStore(null); String mode=args[0];
        boolean before=mode.endsWith("before"), success=mode.endsWith("success"), error=mode.endsWith("error"), generation=mode.endsWith("generation"), sseError=mode.endsWith("event");
        AtomicInteger requests=new AtomicInteger(), modelCalls=new AtomicInteger(), completions=new AtomicInteger(), errors=new AtomicInteger();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>(), callbackFailure=new AtomicReference<>();
        CountDownLatch ready=new CountDownLatch(1), closed=new CountDownLatch(1), token=new CountDownLatch(1), terminal=new CountDownLatch(1);
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1")); server.setSoTimeout(before?500:5000); ExecutorService fixture=Executors.newSingleThreadExecutor();
        okhttp3.Dispatcher dispatcher=new okhttp3.Dispatcher(); okhttp3.ConnectionPool pool=new okhttp3.ConnectionPool();
        fixture.submit(()->{
            try(Socket socket=server.accept()) {
                requests.incrementAndGet(); AgenticNetworkCancellationJdk8Test.readRequest(socket); socket.setSoTimeout(5000);
                if(before) { closed.countDown(); return; }
                if(error) { byte[] body="{\"error\":{\"message\":\"synthetic terminal error\",\"type\":\"invalid_request_error\"}}".getBytes(StandardCharsets.UTF_8); socket.getOutputStream().write(("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body); socket.getOutputStream().flush(); ready.countDown(); closed.countDown(); return; }
                if(!mode.endsWith("header")) {
                    String body="HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+event(success?"你好":"first");
                    if(success) body+="data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                    if(sseError) body+="event: error\ndata: synthetic-stream-error\n\n";
                    socket.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
                    if(success){ ready.countDown(); closed.countDown(); return; }
                }
                ready.countDown(); assertEquals("terminal connection not closed by root cancellation",-1,socket.getInputStream().read()); closed.countDown();
            } catch(SocketTimeoutException timeout) { if(before && requests.get()==0){ready.countDown();closed.countDown();}else{serverFailure.set(timeout);ready.countDown();} }
            catch(Throwable failure){serverFailure.set(failure);ready.countDown();}
        });
        try {
            OpenAiStreamingChatModel delegate=OpenAiStreamingChatModel.builder().apiKey("synthetic-terminal").modelName("fixture").baseUrl("http://127.0.0.1:"+server.getLocalPort()+"/v1").timeout(Duration.ofSeconds(20)).httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool))).build();
            StreamingChatModel counted=new StreamingChatModel(){ public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();} public void doChat(ChatRequest request,StreamingChatResponseHandler handler){modelCalls.incrementAndGet();delegate.chat(request,handler);} };
            Object writer=AgenticServices.agentBuilder(Writer.class).streamingChatModel(counted).async(mode.startsWith("async")).build();
            AtomicReference<DefaultAgenticScope> captured=new AtomicReference<>();
            Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(writer).outputKey("x").beforeCall(scope->captured.set((DefaultAgenticScope)scope)).build();
            TokenStream stream=work.run("session"); DefaultAgenticScope scope=captured.get(); InvocationTasks owner=scope.invocationTasks(); assertEquals(0,modelCalls.get()); assertEquals(0,requests.get());
            Runnable checkOwner=()->{if(InvocationTasks.bound(scope)!=owner)callbackFailure.compareAndSet(null,new AssertionError("terminal callback lost captured root generation"));};
            StringBuffer text=new StringBuffer();
            stream.onPartialResponseWithContext((part,context)->{checkOwner.run();text.append(part.text());token.countDown();}).onCompleteResponse(response->{checkOwner.run();completions.incrementAndGet();terminal.countDown();}).onError(failure->{checkOwner.run();errors.incrementAndGet();terminal.countDown();});
            if(before) scope.cancelPendingTasks(new IllegalStateException("cancelled before lazy start"));
            InvocationTasks fresh=null;
            if(generation){work.run("session");assertSame(scope,captured.get());fresh=scope.invocationTasks();assertNotSame(owner,fresh);}
            assertNull(dev.langchain4j.internal.CancellationContext.current());
            if(generation){InvocationTasks caller=fresh;caller.call(()->{stream.start();assertSame(caller,InvocationTasks.bound(scope));return null;});}
            else stream.start();
            assertNull(dev.langchain4j.internal.CancellationContext.current());
            if(before) {
                assertEquals("cancelled root still invoked model",0,modelCalls.get()); assertTrue(terminal.await(2,TimeUnit.SECONDS)); assertTrue(ready.await(2,TimeUnit.SECONDS)); assertEquals(0,requests.get()); assertEquals(1,errors.get());
            } else if(success || error || sseError) {
                assertTrue(terminal.await(3,TimeUnit.SECONDS)); assertEquals(success?1:0,completions.get()); assertEquals(success?0:1,errors.get()); if(success)assertEquals("你好",text.toString());
                if(sseError){assertEquals("first",text.toString());assertTrue("SSE error did not release connection",closed.await(2,TimeUnit.SECONDS));}
            } else {
                assertTrue(ready.await(3,TimeUnit.SECONDS)); if(!mode.endsWith("header"))assertTrue(token.await(2,TimeUnit.SECONDS)); assertNull(serverFailure.get());
                if(generation){fresh.cancel(new IllegalStateException("fresh root cancelled"));assertFalse("new root cancelled older terminal",closed.await(150,TimeUnit.MILLISECONDS));assertEquals(0,completions.get()+errors.get());}
                owner.cancel(new IllegalStateException("terminal root cancelled")); assertTrue("terminal HTTP call survived owner cancellation",closed.await(2,TimeUnit.SECONDS)); assertTrue(terminal.await(2,TimeUnit.SECONDS)); assertEquals(1,errors.get()); assertEquals(0,completions.get());
            }
            assertTrue(closed.await(2,TimeUnit.SECONDS)); assertNull(serverFailure.get()); assertNull(callbackFailure.get());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(dispatcher.runningCallsCount()!=0&&System.nanoTime()<end)Thread.sleep(2);
            assertEquals(0,dispatcher.runningCallsCount());assertEquals(0,dispatcher.queuedCallsCount());
            assertEquals(success?1:0,completions.get());assertEquals(success?0:1,errors.get());assertNull(callbackFailure.get());
            assertFalse(dispatcher.executorService().isShutdown());assertNull(InvocationTasks.bound(scope));assertNull(dev.langchain4j.internal.CancellationContext.current());
        } finally {dispatcher.cancelAll();server.close();fixture.shutdownNow();dispatcher.executorService().shutdownNow();pool.evictAll();assertTrue(fixture.awaitTermination(2,TimeUnit.SECONDS));assertTrue(dispatcher.executorService().awaitTermination(2,TimeUnit.SECONDS));}
    }
}
