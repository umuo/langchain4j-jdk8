import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryCancellationJdk8Test {
    static ChatMemoryStore pending(CompletableFuture<List<ChatMessage>> read,CompletableFuture<Void> write) {
        return new ChatMemoryStore() {
            public List<ChatMessage> getMessages(Object id) { throw new AssertionError("No blocking reads"); }
            public void updateMessages(Object id,List<ChatMessage> messages) { throw new AssertionError("No blocking writes"); }
            public void deleteMessages(Object id) { }
            public CompletableFuture<List<ChatMessage>> getMessagesAsync(Object id) { return read; }
            public CompletableFuture<Void> updateMessagesAsync(Object id,List<ChatMessage> messages) { return write; }
        };
    }
    @Test public void cancellingWindowedReadReachesUnderlyingStoreRead() {
        CompletableFuture<List<ChatMessage>> read=new CompletableFuture<>();
        MessageWindowChatMemory memory=MessageWindowChatMemory.builder().maxMessages(10).chatMemoryStore(pending(read,new CompletableFuture<>())).build();
        CompletableFuture<List<ChatMessage>> result=memory.messagesAsync();assertFalse(result.isDone());result.cancel(true);
        assertTrue("Windowing must not break store read cancellation",read.isCancelled());
    }
    @Test public void cancellingAddDuringReadReachesUnderlyingStoreRead() {
        CompletableFuture<List<ChatMessage>> read=new CompletableFuture<>();CompletableFuture<Void> write=new CompletableFuture<>();
        MessageWindowChatMemory memory=MessageWindowChatMemory.builder().maxMessages(10).chatMemoryStore(pending(read,write)).build();
        CompletableFuture<Void> result=memory.addAsync(Collections.singletonList(UserMessage.from("question")));result.cancel(true);
        assertTrue(read.isCancelled());assertFalse(write.isDone());
    }
    @Test public void cancellingAddDuringUpdateReachesUnderlyingStoreWrite() {
        CompletableFuture<List<ChatMessage>> read=CompletableFuture.completedFuture(new ArrayList<>());
        CompletableFuture<Void> write=new CompletableFuture<>();
        MessageWindowChatMemory memory=MessageWindowChatMemory.builder().maxMessages(10).chatMemoryStore(pending(read,write)).build();
        CompletableFuture<Void> result=memory.addAsync(Collections.singletonList(UserMessage.from("question")));assertFalse(result.isDone());result.cancel(true);
        assertTrue("Composed memory updates must remain cancellable",write.isCancelled());
    }
}
