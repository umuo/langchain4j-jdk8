import dev.langchain4j.internal.CancellationContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamHandleCleanupJdk8Test {
    interface Service { @UserMessage("fixture") TokenStream ask(); }
    static class Fixture {
        final RuntimeException closeA=new IllegalStateException("close-a"),closeB=new IllegalStateException("close-b"),cancelA=new IllegalStateException("cancel-a"),cancelB=new IllegalStateException("cancel-b"),handleError=new IllegalStateException("handle");
        final Set<Runnable> parentActions=new HashSet<>();
        final AtomicInteger closes=new AtomicInteger(),cancels=new AtomicInteger(),handleCancels=new AtomicInteger(),success=new AtomicInteger(),errors=new AtomicInteger();
        final AtomicReference<StreamingChatResponseHandler> handler=new AtomicReference<>();
        final AtomicReference<StreamingHandle> handle=new AtomicReference<>();
        final AtomicReference<Throwable> delivered=new AtomicReference<>();
        boolean fail;
        final CancellationContext.Registrar parent=action->{
            int index=parentActions.size();parentActions.add(action);AtomicBoolean closed=new AtomicBoolean();
            return ()->{if(closed.compareAndSet(false,true)){parentActions.remove(action);if(index>0){closes.incrementAndGet();if(fail)throw index==1?closeA:closeB;}}};
        };
        final StreamingHandle delegate=new StreamingHandle(){
            public void cancel(){handleCancels.incrementAndGet();if(fail)throw handleError;}
            public boolean isCancelled(){return handleCancels.get()>0;}
        };
        void start(boolean owned){
            assertTrue(System.getProperty("java.version").startsWith("1.8."));
            StreamingChatModel model=new StreamingChatModel(){
                public void doChat(ChatRequest request,StreamingChatResponseHandler response){
                    CancellationContext.register(()->{cancels.incrementAndGet();if(fail)throw cancelA;});
                    CancellationContext.register(()->{cancels.incrementAndGet();if(fail)throw cancelB;});handler.set(response);
                    // Exercise user callbacks while the model's private registrar is still installed.
                    response.onPartialResponse(new PartialResponse("early"),new PartialResponseContext(delegate));
                }
            };
            Service service=AiServices.builder(Service.class).streamingChatModel(model).build();
            TokenStream stream=owned?CancellationContext.call(parent,service::ask):service.ask();
            stream.onPartialResponseWithContext((part,context)->{assertSame(owned?parent:null,CancellationContext.current());handle.set(context.streamingHandle());})
                    .onCompleteResponse(response->{assertSame(owned?parent:null,CancellationContext.current());success.incrementAndGet();})
                    .onError(error->{assertSame(owned?parent:null,CancellationContext.current());errors.incrementAndGet();delivered.set(error);}).start();
            handler.get().onPartialResponse(new PartialResponse("first"),new PartialResponseContext(delegate));
            assertNull(CancellationContext.current());
        }
        void late(){handler.get().onPartialResponse("late");handler.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("late")).build());handler.get().onError(new IllegalStateException("late"));}
        Set<Throwable> all(Throwable failure){Set<Throwable> found=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());collect(failure,found);return found;}
        void collect(Throwable failure,Set<Throwable> found){if(!found.add(failure))return;for(Throwable suppressed:failure.getSuppressed())collect(suppressed,found);}
        void diagnostics(Throwable error){Set<Throwable> found=all(error);assertTrue(found.containsAll(Arrays.asList(closeA,closeB,cancelA,cancelB,handleError)));}
    }
    @Test(timeout=5000) public void manualCancellationPreservesAllCleanupFailuresAndRemainsSilent(){
        Fixture f=new Fixture();f.fail=true;f.start(true);
        try{f.handle.get().cancel();fail("expected cleanup failure");}catch(RuntimeException error){f.diagnostics(error);}
        assertTrue(f.handle.get().isCancelled());f.handle.get().cancel();f.late();assertEquals(2,f.closes.get());assertEquals(2,f.cancels.get());assertEquals(1,f.handleCancels.get());assertTrue(f.parentActions.isEmpty());assertEquals(0,f.success.get()+f.errors.get());
    }
    @Test(timeout=5000) public void streamErrorPreservesPrimaryAndCleansAllResourcesOnce(){
        Fixture f=new Fixture();f.fail=true;f.start(true);RuntimeException primary=new IllegalStateException("primary");
        f.handler.get().onError(primary);f.late();assertSame(primary,f.delivered.get());f.diagnostics(primary);assertEquals(1,f.errors.get());assertEquals(0,f.success.get());assertTrue(f.parentActions.isEmpty());assertEquals(2,f.cancels.get());assertEquals(2,f.closes.get());
    }
    @Test(timeout=5000) public void normalCompletionDetachesAndLateCancelOnlyMarksHandle(){
        Fixture f=new Fixture();f.start(true);f.handler.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        assertEquals(1,f.success.get());assertEquals(0,f.errors.get());assertEquals(2,f.closes.get());assertEquals(0,f.cancels.get());assertTrue(f.parentActions.isEmpty());
        f.handle.get().cancel();assertTrue(f.handle.get().isCancelled());f.late();assertEquals(1,f.success.get());assertEquals(0,f.handleCancels.get());
    }
    @Test(timeout=5000) public void ownerlessCallbacksDoNotExposePrivateStreamingRegistrar(){
        Fixture f=new Fixture();f.start(false);f.handle.get().cancel();f.late();assertEquals(2,f.cancels.get());assertEquals(0,f.success.get()+f.errors.get());assertNull(CancellationContext.current());
    }
    @Test(timeout=5000) public void normalCompletionAttemptsEveryDetachAfterCloseFailure(){
        Fixture f=new Fixture();f.start(true);f.fail=true;
        f.handler.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        assertEquals(1,f.success.get());assertEquals(0,f.errors.get());assertEquals(2,f.closes.get());assertEquals(0,f.cancels.get());assertTrue(f.parentActions.isEmpty());
    }

}
