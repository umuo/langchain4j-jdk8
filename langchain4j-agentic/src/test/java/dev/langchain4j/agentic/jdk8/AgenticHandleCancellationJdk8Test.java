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

public class AgenticHandleCancellationJdk8Test {
    public interface Writer { @Agent(outputKey="x") @UserMessage("fixture") TokenStream ask(); }
    public interface Work extends AgenticScopeAccess { @Agent TokenStream run(@MemoryId String id); }
    @Test(timeout=15000) public void syncPendingSavedHandleCancelsWholeStream() throws Exception {child("sync-pending");}
    @Test(timeout=15000) public void syncQueuedSavedHandleCancelsWholeStream() throws Exception {child("sync-queued");}
    @Test(timeout=15000) public void syncHeadersSavedHandleCancelsWholeStream() throws Exception {child("sync-headers");}
    @Test(timeout=15000) public void syncTokenSavedHandleCancelsWholeStream() throws Exception {child("sync-token");}
    @Test(timeout=15000) public void asyncPendingSavedHandleCancelsWholeStream() throws Exception {child("async-pending");}
    @Test(timeout=15000) public void asyncQueuedSavedHandleCancelsWholeStream() throws Exception {child("async-queued");}
    @Test(timeout=15000) public void asyncHeadersSavedHandleCancelsWholeStream() throws Exception {child("async-headers");}
    @Test(timeout=15000) public void asyncTokenSavedHandleCancelsWholeStream() throws Exception {child("async-token");}
    @Test(timeout=15000) public void plainPendingSavedHandleCancelsWholeStream() throws Exception {child("plain-pending");}
    @Test(timeout=15000) public void plainQueuedSavedHandleCancelsWholeStream() throws Exception {child("plain-queued");}
    @Test(timeout=15000) public void plainHeadersSavedHandleCancelsWholeStream() throws Exception {child("plain-headers");}
    @Test(timeout=15000) public void plainTokenSavedHandleCancelsWholeStream() throws Exception {child("plain-token");}
    @Test(timeout=15000) public void savedHandleDoesNotCancelActiveSiblingUnderSameRoot() throws Exception {child("isolation");}
    @Test(timeout=15000) public void cancelledStreamDoesNotPublishLateToolCallback() throws Exception {child("plain-running");}
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"eager-terminal-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AgenticHandleCancellationJdk8Test.class.getName(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
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
        if(mode.equals("isolation")){isolation();return;}
        boolean continuation=mode.endsWith("headers")||mode.endsWith("token"),queued=mode.endsWith("queued");
        boolean running=mode.endsWith("running");Tools tool=new Tools(running);if(continuation||running)tool.future.complete("sunny");
        ThreadPoolExecutor tools=(ThreadPoolExecutor)Executors.newFixedThreadPool(1);
        CountDownLatch nextReady=new CountDownLatch(1),firstToken=new CountDownLatch(1),nextToken=new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> saved=new AtomicReference<>();
        CountDownLatch blockerEntered=new CountDownLatch(1),blockerRelease=new CountDownLatch(1),frame=new CountDownLatch(1),closed=new CountDownLatch(1),terminal=new CountDownLatch(1);
        AtomicInteger toolCallbacks=new AtomicInteger();
        AtomicInteger unrelatedInterrupts=new AtomicInteger(),requests=new AtomicInteger(),errors=new AtomicInteger(),completions=new AtomicInteger(),policies=new AtomicInteger();
        if(queued){tools.submit(()->{blockerEntered.countDown();try{blockerRelease.await();}catch(InterruptedException e){unrelatedInterrupts.incrementAndGet();}});assertTrue(blockerEntered.await(2,TimeUnit.SECONDS));}
        ServerSocket server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(5000);ExecutorService fixture=Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>(),callbackFailure=new AtomicReference<>();
        okhttp3.Dispatcher dispatcher=new okhttp3.Dispatcher();okhttp3.ConnectionPool pool=new okhttp3.ConnectionPool();
        fixture.submit(()->{try{
            try(Socket socket=server.accept()){
                requests.incrementAndGet();String request=AgenticNetworkCancellationJdk8Test.readRequest(socket);assertTrue(request,request.contains("weather"));
                write(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+AgenticTerminalStreamJdk8Test.event("first")+call(0)+call(1));
                if(continuation){write(socket,"data: [DONE]\n\n");}
                else{assertTrue(frame.await(4,TimeUnit.SECONDS));assertEquals("SSE connection survived terminal failure",-1,socket.getInputStream().read());closed.countDown();}
            }
            if(continuation)try(Socket socket=server.accept()){
                requests.incrementAndGet();String request=AgenticNetworkCancellationJdk8Test.readRequest(socket);
                com.fasterxml.jackson.databind.JsonNode messages = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request).get("messages");
                java.util.Set<String> ids = new java.util.HashSet<>();
                int results = 0;
                for (com.fasterxml.jackson.databind.JsonNode message : messages) if ("tool".equals(message.path("role").asText())) {
                    results++; ids.add(message.path("tool_call_id").asText()); assertEquals("sunny",message.path("content").asText());
                }
                assertEquals(2,results);assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("call0","call1")),ids);
                nextReady.countDown();
                if(mode.endsWith("token"))write(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+AgenticTerminalStreamJdk8Test.event("later"));
                assertEquals("old handle failed to close continuation connection",-1,socket.getInputStream().read());closed.countDown();
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
            stream.onToolExecuted(event->toolCallbacks.incrementAndGet()).onPartialResponseWithContext((part,context)->{saved.compareAndSet(null,context.streamingHandle());if(part.text().equals("first"))firstToken.countDown();else nextToken.countDown();}).onCompleteResponse(response->{completions.incrementAndGet();if(!"done".equals(response.aiMessage().text()))callbackFailure.set(new AssertionError("wrong final output"));terminal.countDown();}).onError(error->{errors.incrementAndGet();terminal.countDown();}).start();
            assertTrue(firstToken.await(3,TimeUnit.SECONDS));
            if(continuation){assertTrue(nextReady.await(3,TimeUnit.SECONDS));if(mode.endsWith("token"))assertTrue(nextToken.await(2,TimeUnit.SECONDS));}
            else if(queued){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(tools.getQueue().isEmpty()&&System.nanoTime()<end)Thread.sleep(2);assertFalse(tools.getQueue().isEmpty());}
            else assertTrue(tool.entered.await(3,TimeUnit.SECONDS));
            frame.countDown();saved.get().cancel();assertTrue(saved.get().isCancelled());saved.get().cancel();
            assertTrue("saved handle did not release active socket",closed.await(2,TimeUnit.SECONDS));
            if(serverFailure.get()!=null)throw new AssertionError("SSE fixture failed",serverFailure.get());
            if(!continuation&&!queued&&!running){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!tool.future.isCancelled()&&System.nanoTime()<end)Thread.sleep(2);assertTrue("manual cancellation leaked tool Future",tool.future.isCancelled());}
            blockerRelease.countDown();tools.submit(()->{}).get(2,TimeUnit.SECONDS);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(dispatcher.runningCallsCount()!=0&&System.nanoTime()<end)Thread.sleep(2);
            assertEquals(0,dispatcher.runningCallsCount());assertEquals(0,dispatcher.queuedCallsCount());assertEquals(continuation?2:1,requests.get());
            assertEquals(continuation?2:queued?0:1,tool.calls.get());assertEquals(running?1:0,tool.interrupted.get());assertEquals(continuation?2:0,toolCallbacks.get());assertEquals(0,unrelatedInterrupts.get());assertEquals(0,policies.get());
            assertEquals("cancelled stream emitted terminal callback",0,completions.get()+errors.get());assertNull(callbackFailure.get());assertFalse(tools.isShutdown());assertFalse(dispatcher.executorService().isShutdown());
            if(!mode.startsWith("plain")){
                AtomicInteger sibling=new AtomicInteger();dev.langchain4j.agentic.internal.InvocationTasks.Registration registration=scope.get().invocationTasks().register(sibling::incrementAndGet);
                assertEquals("manual stream cancel poisoned root owner",0,sibling.get());registration.close();
            }
        }finally{frame.countDown();blockerRelease.countDown();tool.release.countDown();tool.future.complete("fixture cleanup");dispatcher.cancelAll();server.close();fixture.shutdownNow();tools.shutdownNow();dispatcher.executorService().shutdownNow();pool.evictAll();assertTrue(fixture.awaitTermination(2,TimeUnit.SECONDS));assertTrue(tools.awaitTermination(2,TimeUnit.SECONDS));assertTrue(dispatcher.executorService().awaitTermination(2,TimeUnit.SECONDS));}
    }
    static void isolation() throws Exception {
        DefaultAgenticScope scope = new AgenticScopeRegistry("sibling-fixture").create("shared");
        dev.langchain4j.agentic.internal.InvocationTasks owner = scope.invocationTasks();
        ServerSocket server = new ServerSocket(0,2,InetAddress.getByName("127.0.0.1")); server.setSoTimeout(5000);
        ExecutorService fixture = Executors.newFixedThreadPool(3);
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher(); okhttp3.ConnectionPool pool = new okhttp3.ConnectionPool();
        CountDownLatch firstToken = new CountDownLatch(1), secondToken = new CountDownLatch(1), firstClosed = new CountDownLatch(1), secondClosed = new CountDownLatch(1), finishSecond = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> saved = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicInteger firstTerminal = new AtomicInteger(), secondSuccess = new AtomicInteger(), secondError = new AtomicInteger();
        fixture.submit(() -> {try {
            for(int i=0;i<2;i++){
                Socket socket=server.accept();int index=i;
                fixture.submit(() -> {try(Socket client=socket){
                    AgenticNetworkCancellationJdk8Test.readRequest(client);
                    write(client,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+AgenticTerminalStreamJdk8Test.event("sibling"+index));
                    if(index==0){assertEquals(-1,client.getInputStream().read());firstClosed.countDown();}
                    else {assertTrue(finishSecond.await(4,TimeUnit.SECONDS));write(client,"data: [DONE]\n\n");secondClosed.countDown();}
                }catch(Throwable error){failure.compareAndSet(null,error);}});
            }
        }catch(Throwable error){failure.compareAndSet(null,error);}});
        try {
            OpenAiStreamingChatModel model=OpenAiStreamingChatModel.builder().apiKey("synthetic-isolation").modelName("fixture").baseUrl("http://127.0.0.1:"+server.getLocalPort()+"/v1").timeout(Duration.ofSeconds(20)).httpClientBuilder(new OkHttpClientBuilder().okHttpClientBuilder(new okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool))).build();
            Writer service=AiServices.builder(Writer.class).streamingChatModel(model).build();
            TokenStream first=owner.call(service::ask),second=owner.call(service::ask);
            first.onPartialResponseWithContext((part,context)->{saved.set(context.streamingHandle());firstToken.countDown();}).onCompleteResponse(response->firstTerminal.incrementAndGet()).onError(error->firstTerminal.incrementAndGet()).start();
            assertTrue(firstToken.await(3,TimeUnit.SECONDS));
            second.onPartialResponseWithContext((part,context)->secondToken.countDown()).onCompleteResponse(response->{if(!"sibling1".equals(response.aiMessage().text()))failure.compareAndSet(null,new AssertionError("wrong sibling result"));secondSuccess.incrementAndGet();completed.countDown();}).onError(error->{secondError.incrementAndGet();failure.compareAndSet(null,error);completed.countDown();}).start();
            assertTrue(secondToken.await(3,TimeUnit.SECONDS));saved.get().cancel();assertTrue(firstClosed.await(2,TimeUnit.SECONDS));
            assertFalse("stream cancellation closed its sibling",secondClosed.await(150,TimeUnit.MILLISECONDS));assertEquals(0,secondSuccess.get()+secondError.get());
            owner.call(()->{scope.writeState("alive","yes");return null;});assertEquals("yes",scope.readState("alive"));
            finishSecond.countDown();assertTrue(completed.await(3,TimeUnit.SECONDS));assertTrue(secondClosed.await(2,TimeUnit.SECONDS));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(dispatcher.runningCallsCount()!=0&&System.nanoTime()<end)Thread.sleep(2);
            assertEquals(0,dispatcher.runningCallsCount());assertEquals(0,dispatcher.queuedCallsCount());assertEquals(0,firstTerminal.get());assertEquals(1,secondSuccess.get());assertEquals(0,secondError.get());
            if(failure.get()!=null)throw new AssertionError("sibling fixture failed",failure.get());
            assertFalse(dispatcher.executorService().isShutdown());assertNull(dev.langchain4j.internal.CancellationContext.current());
        }finally{finishSecond.countDown();dispatcher.cancelAll();server.close();fixture.shutdownNow();dispatcher.executorService().shutdownNow();pool.evictAll();assertTrue(fixture.awaitTermination(2,TimeUnit.SECONDS));assertTrue(dispatcher.executorService().awaitTermination(2,TimeUnit.SECONDS));}
    }

}
