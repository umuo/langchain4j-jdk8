package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.*;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgenticEagerTerminalToolJdk8Test {
    public interface Writer { @Agent(outputKey="x") @UserMessage("fixture") TokenStream ask(); }
    public interface Work extends AgenticScopeAccess { @Agent TokenStream run(@MemoryId String id); }
    @Test(timeout=15000) public void syncRootCancelsPendingEagerFuture() throws Exception {child("sync-root");}
    @Test(timeout=15000) public void asyncRootCancelsPendingEagerFuture() throws Exception {child("async-root");}
    @Test(timeout=15000) public void syncSseErrorCancelsPendingEagerFuture() throws Exception {child("sync-error");}
    @Test(timeout=15000) public void asyncSseErrorCancelsPendingEagerFuture() throws Exception {child("async-error");}
    @Test(timeout=15000) public void syncSseErrorInterruptsRunningTool() throws Exception {child("sync-running");}
    @Test(timeout=15000) public void asyncSseErrorInterruptsRunningTool() throws Exception {child("async-running");}
    @Test(timeout=15000) public void syncSseErrorSkipsQueuedTool() throws Exception {child("sync-queued");}
    @Test(timeout=15000) public void asyncSseErrorSkipsQueuedTool() throws Exception {child("async-queued");}
    @Test(timeout=15000) public void syncEagerToolsContinueWithAlignedResults() throws Exception {child("sync-success");}
    @Test(timeout=15000) public void asyncEagerToolsContinueWithAlignedResults() throws Exception {child("async-success");}
    @Test(timeout=15000) public void plainSseErrorCancelsPendingEagerFuture() throws Exception {child("plain-error");}
    @Test(timeout=15000) public void plainSseErrorInterruptsRunningTool() throws Exception {child("plain-running");}
    @Test(timeout=15000) public void plainSseErrorSkipsQueuedTool() throws Exception {child("plain-queued");}
    @Test(timeout=15000) public void plainEagerToolsContinueWithAlignedResults() throws Exception {child("plain-success");}
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"eager-terminal-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticEagerTerminalToolJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue("child timed out: "+mode,p.waitFor(10,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static class Tools {
        final CompletableFuture<String> future=new CompletableFuture<>();
        final AtomicInteger calls=new AtomicInteger(), interrupted=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final boolean blocking;
        Tools(boolean blocking){this.blocking=blocking;}
        @Tool public CompletableFuture<String> weather(){calls.incrementAndGet();entered.countDown();if(blocking)try{release.await();}catch(InterruptedException e){interrupted.incrementAndGet();Thread.currentThread().interrupt();}return future;}
    }
    static String call(int index){return "data: {\"id\":\"fixture\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":"+index+",\"id\":\"call"+index+"\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}]}}]}\n\n";}
    static void write(Socket s,String body)throws Exception{s.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
    public static void main(String[] args)throws Exception{
        assertTrue(System.getProperty("java.version").startsWith("1.8."));AgenticScopePersister.setStore(null);String mode=args[0];
        boolean success=mode.endsWith("success"),root=mode.endsWith("root"),queued=mode.endsWith("queued");
        Tools tool=new Tools(mode.endsWith("running"));if(success)tool.future.complete("sunny");
        ThreadPoolExecutor tools=(ThreadPoolExecutor)Executors.newFixedThreadPool(1);
        CountDownLatch blockerEntered=new CountDownLatch(1),blockerRelease=new CountDownLatch(1),frame=new CountDownLatch(1),closed=new CountDownLatch(1),terminal=new CountDownLatch(1);
        AtomicInteger unrelatedInterrupts=new AtomicInteger(),requests=new AtomicInteger(),errors=new AtomicInteger(),completions=new AtomicInteger(),policies=new AtomicInteger();
        if(queued){tools.submit(()->{blockerEntered.countDown();try{blockerRelease.await();}catch(InterruptedException e){unrelatedInterrupts.incrementAndGet();}});assertTrue(blockerEntered.await(2,TimeUnit.SECONDS));}
        ServerSocket server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(5000);ExecutorService fixture=Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>(),callbackFailure=new AtomicReference<>();
        okhttp3.Dispatcher dispatcher=new okhttp3.Dispatcher();okhttp3.ConnectionPool pool=new okhttp3.ConnectionPool();
        fixture.submit(()->{try{
            try(Socket socket=server.accept()){
                requests.incrementAndGet();String request=AgenticNetworkCancellationJdk8Test.readRequest(socket);assertTrue(request,request.contains("weather"));
                write(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+call(0)+call(1));
                if(success){write(socket,"data: [DONE]\n\n");}
                else{assertTrue(frame.await(4,TimeUnit.SECONDS));if(!root)write(socket,"event: error\ndata: eager-fixture-error\n\n");assertEquals("SSE connection survived terminal failure",-1,socket.getInputStream().read());closed.countDown();}
            }
            if(success)try(Socket socket=server.accept()){
                requests.incrementAndGet();String request=AgenticNetworkCancellationJdk8Test.readRequest(socket);
                com.fasterxml.jackson.databind.JsonNode messages = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request).get("messages");
                java.util.Set<String> ids = new java.util.HashSet<>();
                int results = 0;
                for (com.fasterxml.jackson.databind.JsonNode message : messages) if ("tool".equals(message.path("role").asText())) {
                    results++; ids.add(message.path("tool_call_id").asText()); assertEquals("sunny",message.path("content").asText());
                }
                assertEquals(2,results);assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("call0","call1")),ids);
                write(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+AgenticTerminalStreamJdk8Test.event("done")+"data: [DONE]\n\n");closed.countDown();
            }
        }catch(Throwable error){serverFailure.set(error);closed.countDown();}});
        try{
            OpenAiStreamingChatModel model=OpenAiStreamingChatModel.builder().apiKey("synthetic-eager").modelName("fixture").baseUrl("http://127.0.0.1:"+server.getLocalPort()+"/v1").timeout(Duration.ofSeconds(20)).httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool))).build();
            Object writer=AgenticServices.agentBuilder(Writer.class).streamingChatModel(model).async(mode.startsWith("async")).tools(tool).executeToolsConcurrently(tools).toolExecutionErrorHandler((error,context)->{policies.incrementAndGet();return ToolErrorHandlerResult.text("retry");}).build();
            AtomicReference<DefaultAgenticScope> scope=new AtomicReference<>();
            Work work=AgenticServices.sequenceBuilder(Work.class).subAgents(writer).outputKey("x").beforeCall(s->scope.set((DefaultAgenticScope)s)).build();
            TokenStream stream=mode.startsWith("plain")
                    ? AiServices.builder(Writer.class).streamingChatModel(model).tools(tool).executeToolsConcurrently(tools).toolExecutionErrorHandler((error,context)->{policies.incrementAndGet();return ToolErrorHandlerResult.text("retry");}).build().ask()
                    : work.run("same");
            assertEquals(0,requests.get());
            stream.onCompleteResponse(response->{completions.incrementAndGet();if(!"done".equals(response.aiMessage().text()))callbackFailure.set(new AssertionError("wrong final output"));terminal.countDown();}).onError(error->{errors.incrementAndGet();terminal.countDown();}).start();
            if(!success){
                if(queued){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(tools.getQueue().isEmpty()&&System.nanoTime()<end)Thread.sleep(2);assertFalse("eager tool not queued",tools.getQueue().isEmpty());}
                else assertTrue("tool not started before SSE close",tool.entered.await(3,TimeUnit.SECONDS));
                frame.countDown();if(root)scope.get().cancelPendingTasks(new IllegalStateException("root cancelled"));
            }
            assertTrue("terminal signal missing",terminal.await(3,TimeUnit.SECONDS));assertTrue(closed.await(2,TimeUnit.SECONDS));if(serverFailure.get()!=null)throw new AssertionError("SSE fixture failed",serverFailure.get());
            if(!success&&!queued){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!tool.future.isCancelled()&&System.nanoTime()<end)Thread.sleep(2);assertTrue("raw eager tool Future survived cancellation/error",tool.future.isCancelled());}
            blockerRelease.countDown();tools.submit(()->{}).get(2,TimeUnit.SECONDS);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(dispatcher.runningCallsCount()!=0&&System.nanoTime()<end)Thread.sleep(2);
            assertEquals(0,dispatcher.runningCallsCount());assertEquals(0,dispatcher.queuedCallsCount());assertEquals(success?2:1,requests.get());
            assertEquals(success?2:queued?0:1,tool.calls.get());assertEquals(mode.endsWith("running")?1:0,tool.interrupted.get());assertEquals(0,unrelatedInterrupts.get());assertEquals(0,policies.get());
            assertEquals(success?1:0,completions.get());assertEquals(success?0:1,errors.get());assertNull(callbackFailure.get());assertFalse(tools.isShutdown());assertFalse(dispatcher.executorService().isShutdown());
        }finally{frame.countDown();blockerRelease.countDown();tool.release.countDown();tool.future.complete("fixture cleanup");dispatcher.cancelAll();server.close();fixture.shutdownNow();tools.shutdownNow();dispatcher.executorService().shutdownNow();pool.evictAll();assertTrue(fixture.awaitTermination(2,TimeUnit.SECONDS));assertTrue(tools.awaitTermination(2,TimeUnit.SECONDS));assertTrue(dispatcher.executorService().awaitTermination(2,TimeUnit.SECONDS));}
    }
}
