package dev.langchain4j.model.openai.internal;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.*;
import static org.junit.Assert.*;

public class StreamingTerminalJdk8Test {
    final AtomicInteger partials=new AtomicInteger(), completions=new AtomicInteger();
    final List<Throwable> errors=new ArrayList<>();
    @Before public void runtime(){assertTrue(System.getProperty("java.version").startsWith("1.8."));}
    void run(Consumer<ServerSentEventListener> script, Consumer<ParsedAndRawResponse<String>> partial){
        run(script,partial,errors::add);
    }
    void run(Consumer<ServerSentEventListener> script, Consumer<ParsedAndRawResponse<String>> partial, Consumer<Throwable> errorHandler){
        HttpClient client=new HttpClient(){
            public SuccessfulHttpResponse execute(HttpRequest request){throw new AssertionError("sync request");}
            public void execute(HttpRequest request,ServerSentEventParser parser,ServerSentEventListener listener){listener.onOpen(SuccessfulHttpResponse.builder().statusCode(200).build());script.accept(listener);}
        };
        new StreamingRequestExecutor<String>(client,HttpRequest.builder().method(HttpMethod.POST).url("http://127.0.0.1/fixture").body("{}").build(),String.class)
                .onPartialResponse(partial).onComplete(completions::incrementAndGet).onError(errorHandler).execute();
    }
    ServerSentEvent event(){return new ServerSentEvent(null,"\"one\"");}
    @Test public void errorThenClosePreservesOriginalErrorWithUnsupportedCancelHandle(){
        RuntimeException primary=new IllegalStateException("transport error");
        run(listener->{listener.onEvent(event());listener.onError(primary);listener.onEvent(event());listener.onClose();listener.onError(new IllegalStateException("late"));},response->partials.incrementAndGet());
        assertEquals(1,partials.get());assertEquals(0,completions.get());assertEquals(Collections.singletonList(primary),errors);assertEquals(1,primary.getSuppressed().length);
    }
    @Test public void completedStreamIgnoresLaterErrorEventsAndClose(){
        run(listener->{listener.onEvent(event());listener.onClose();listener.onError(new IllegalStateException("late"));listener.onEvent(event());listener.onClose();},response->partials.incrementAndGet());
        assertEquals(1,partials.get());assertEquals(1,completions.get());assertTrue(errors.isEmpty());
    }
    @Test public void emptyStreamReportsNoChoicesOnce(){
        run(listener->{listener.onClose();listener.onClose();listener.onError(new IllegalStateException("late"));},response->partials.incrementAndGet());
        assertEquals(0,partials.get());assertEquals(0,completions.get());assertEquals(1,errors.size());
    }
    static final class Handle implements ServerSentEventParsingHandle {
        int cancels;boolean cancelled;RuntimeException failure;
        public void cancel(){cancels++;if(failure!=null)throw failure;cancelled=true;}
        public boolean isCancelled(){return cancelled;}
    }
    @Test public void partialProcessingFailureCancelsParserAndTerminatesOnce(){
        Handle handle=new Handle();RuntimeException primary=new IllegalStateException("consumer failed");ServerSentEventContext context=new ServerSentEventContext(handle);
        run(listener->{listener.onEvent(event(),context);listener.onEvent(event(),context);listener.onClose();},response->{partials.incrementAndGet();throw primary;});
        assertEquals(1,partials.get());assertEquals(1,handle.cancels);assertTrue(handle.cancelled);assertEquals(Collections.singletonList(primary),errors);assertEquals(0,completions.get());
    }
    @Test public void parserCancellationFailureIsSuppressedWithoutReplacingStreamError(){
        Handle handle=new Handle();handle.failure=new IllegalStateException("parser cancel");RuntimeException primary=new IllegalStateException("stream error");ServerSentEventContext context=new ServerSentEventContext(handle);
        run(listener->{listener.onEvent(event(),context);listener.onError(primary);listener.onClose();},response->partials.incrementAndGet());
        assertEquals(1,handle.cancels);assertEquals(Collections.singletonList(primary),errors);assertArrayEquals(new Throwable[]{handle.failure},primary.getSuppressed());assertEquals(0,completions.get());
    }
    @Test public void errorEventDoesNotSwallowErrorHandlerFailureOrNotifyTwice(){
        RuntimeException primary=new IllegalStateException("error consumer failed");
        try {
            run(listener->listener.onEvent(new ServerSentEvent("error","synthetic error")),response->partials.incrementAndGet(),error->{errors.add(error);throw primary;});
            fail("error consumer failure was swallowed");
        } catch(RuntimeException expected){assertSame(primary,expected);}
        assertEquals(1,errors.size());assertEquals(0,completions.get());
    }
}
