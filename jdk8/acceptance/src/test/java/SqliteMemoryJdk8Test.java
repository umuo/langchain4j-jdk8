import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.sqlite.SqliteChatMemoryStore;
import dev.langchain4j.store.embedding.sqlite.SqliteEmbeddingStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class SqliteMemoryJdk8Test {
    Path directory,database;
    final List<SqliteChatMemoryStore> stores=new ArrayList<>();
    final List<ExecutorService> executors=new ArrayList<>();
    @Before public void start() throws Exception {
        directory=Files.createTempDirectory(Paths.get("target"),"sqlite-memory-");database=directory.resolve("memory # ?.db");
    }
    @After public void stop() throws Exception {
        for(ExecutorService executor:executors) { executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS); }
        for(SqliteChatMemoryStore store:stores) store.close();
        try(java.util.stream.Stream<Path> files=Files.walk(directory)) {
            for(Path file:files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(file);
        }
    }
    SqliteChatMemoryStore store() { return store(null); }
    SqliteChatMemoryStore store(Executor executor) {
        SqliteChatMemoryStore.Builder builder=SqliteChatMemoryStore.builder().databasePath(database);
        if(executor!=null) builder.asyncExecutor(executor);
        SqliteChatMemoryStore store=builder.build();stores.add(store);return store;
    }
    ExecutorService executor() { ExecutorService executor=Executors.newSingleThreadExecutor();executors.add(executor);return executor; }
    Connection connection() throws Exception { return DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath().toUri().toASCIIString()); }
    void sql(String sql) throws Exception { try(Connection c=connection();Statement s=c.createStatement()) { s.execute(sql); } }
    static List<ChatMessage> messages(String text) { return new ArrayList<>(Arrays.asList(UserMessage.from(text),AiMessage.from("reply"))); }

    @Test public void snapshotsReplaceAndReopenWithIndependentMutableReads() {
        SqliteChatMemoryStore store=store();assertTrue(store.getMessages("unknown").isEmpty());
        List<ChatMessage> input=messages("first");store.updateMessages("session",input);input.clear();
        assertEquals(messages("first"),store.getMessages("session"));
        List<ChatMessage> read=store.getMessages("session");read.clear();assertEquals(2,store.getMessages("session").size());
        store.updateMessages("session",messages("replacement"));store.close();
        assertEquals(messages("replacement"),store().getMessages("session"));
    }
    @Test public void typedIdsAreIsolatedAndUnsupportedTypesDoNotAlias() {
        SqliteChatMemoryStore store=store();UUID uuid=UUID.randomUUID();
        for(Object id:Arrays.asList("1",1,1L,uuid,uuid.toString(),"")) store.updateMessages(id,messages(id.getClass().getSimpleName()));
        assertEquals(messages("String"),store.getMessages("1"));assertEquals(messages("Integer"),store.getMessages(1));
        assertEquals(messages("Long"),store.getMessages(1L));assertEquals(messages("UUID"),store.getMessages(uuid));
        assertEquals(messages("String"),store.getMessages(uuid.toString()));assertEquals(messages("String"),store.getMessages(""));
        assertThrows(IllegalArgumentException.class,() -> store.getMessages(null));
        assertThrows(IllegalArgumentException.class,() -> store.updateMessages(new Object(),messages("bad")));
        assertThrows(IllegalArgumentException.class,() -> store.deleteMessages(1.0d));
    }
    @Test public void coreMessageTypesAttributesAndToolResultsSurviveReopen() {
        ToolExecutionRequest call=ToolExecutionRequest.builder().id("tool-1").name("lookup").arguments("{\"city\":\"上海\"}").build();
        List<ChatMessage> input=Arrays.asList(
                SystemMessage.builder().text("system").attributes(Collections.singletonMap("cache","explicit")).build(),
                UserMessage.builder().name("alice").contents(Arrays.asList(TextContent.from("问题"),ImageContent.from("https://fixture.invalid/image.png")))
                        .attributes(Collections.singletonMap("source","fixture")).build(),
                AiMessage.builder().text("checking").thinking("reason").toolExecutionRequests(Collections.singletonList(call))
                        .attributes(Collections.singletonMap("extra","value")).build(),
                ToolExecutionResultMessage.builder().id("tool-1").toolName("lookup").text("result").isError(true)
                        .attributes(Collections.singletonMap("status","fixture")).build(),
                AiMessage.from("final"),CustomMessage.from(Collections.singletonMap("role","fixture")));
        SqliteChatMemoryStore store=store();store.updateMessages("session",input);store.close();
        assertEquals(input,store().getMessages("session"));
    }
    @Test public void emptySnapshotsAndDeletesAffectOnlyOneConversation() {
        SqliteChatMemoryStore store=store();store.updateMessages("a",messages("a"));store.updateMessages("b",messages("b"));
        store.updateMessages("a",Collections.emptyList());assertTrue(store.getMessages("a").isEmpty());
        store.deleteMessages("a");store.deleteMessages("missing");assertEquals(messages("b"),store.getMessages("b"));
    }
    @Test public void validationAndFailedUpdateLeavePreviousSnapshotUntouched() throws Exception {
        SqliteChatMemoryStore store=store();store.updateMessages("session",messages("before"));
        assertThrows(IllegalArgumentException.class,() -> store.updateMessages("session",null));
        assertThrows(IllegalArgumentException.class,() -> store.updateMessages("session",Arrays.asList(UserMessage.from("new"),null)));
        sql("CREATE TRIGGER reject_memory BEFORE UPDATE ON lc4j_chat_memories BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        assertThrows(IllegalStateException.class,() -> store.updateMessages("session",messages("after")));
        assertEquals(messages("before"),store.getMessages("session"));
        sql("DROP TRIGGER reject_memory");store.updateMessages("session",messages("after"));assertEquals(messages("after"),store.getMessages("session"));
    }
    @Test public void failedDeleteDoesNotDeleteOrPoisonTheConnection() throws Exception {
        SqliteChatMemoryStore store=store();store.updateMessages("session",messages("before"));
        sql("CREATE TRIGGER reject_memory_delete BEFORE DELETE ON lc4j_chat_memories BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        assertThrows(IllegalStateException.class,() -> store.deleteMessages("session"));assertEquals(messages("before"),store.getMessages("session"));
        sql("DROP TRIGGER reject_memory_delete");store.deleteMessages("session");assertTrue(store.getMessages("session").isEmpty());
    }
    @Test public void invalidSchemaFormatAndJsonFailWithoutSilentClearing() throws Exception {
        SqliteChatMemoryStore store=store();store.updateMessages("session",messages("before"));
        for(String payload:Arrays.asList("null","[null]","{invalid","[{\"type\":\"UNKNOWN\"}]")) {
            try(Connection c=connection();PreparedStatement s=c.prepareStatement("UPDATE lc4j_chat_memories SET messages=?")) { s.setString(1,payload);s.executeUpdate(); }
            assertThrows(IllegalStateException.class,() -> store.getMessages("session"));
        }
        store.updateMessages("session",messages("repaired"));sql("UPDATE lc4j_chat_memories SET format_version=99");
        assertThrows(IllegalStateException.class,() -> store.getMessages("session"));
        sql("UPDATE lc4j_chat_memory_config SET schema_version=99");store.close();
        assertThrows(IllegalStateException.class,this::store);
        try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM lc4j_chat_memories")) { r.next();assertEquals(1,r.getInt(1)); }
    }
    @Test public void vectorAndMemoryStoresCoexistWithoutDeletingEachOthersTables() {
        try(SqliteEmbeddingStore vectors=SqliteEmbeddingStore.builder().databasePath(database).build()) {
            vectors.add(dev.langchain4j.data.embedding.Embedding.from(new float[]{1,0}));
            SqliteChatMemoryStore memory=store();memory.updateMessages("a",messages("a"));memory.deleteMessages("a");assertEquals(1,vectors.size());
            memory.updateMessages("b",messages("b"));vectors.removeAll();assertEquals(messages("b"),memory.getMessages("b"));
            memory.close();assertEquals(0,vectors.size());
        }
    }
    @Test public void defaultAsyncIsExplicitlyUnsupported() throws Exception {
        SqliteChatMemoryStore store=store();
        ExecutionException error=assertThrows(ExecutionException.class,() -> store.getMessagesAsync("s").get(5,TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof dev.langchain4j.exception.AsyncNotSupportedException);
        assertThrows(ExecutionException.class,() -> store.updateMessagesAsync("s",messages("s")).get(5,TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,() -> store.deleteMessagesAsync("s").get(5,TimeUnit.SECONDS));
    }
    @Test public void explicitExecutorSupportsAsyncUpdatesReadsDeletesAndWindowMemory() throws Exception {
        ExecutorService executor=executor();SqliteChatMemoryStore store=store(executor);
        MessageWindowChatMemory memory=MessageWindowChatMemory.builder().id("a").maxMessages(10).chatMemoryStore(store).build();
        memory.addAsync(messages("first")).get(5,TimeUnit.SECONDS);assertEquals(messages("first"),memory.messagesAsync().get(5,TimeUnit.SECONDS));
        memory.setAsync(messages("replacement")).get(5,TimeUnit.SECONDS);assertEquals(messages("replacement"),store.getMessages("a"));
        store.deleteMessagesAsync("a").get(5,TimeUnit.SECONDS);assertTrue(memory.messages().isEmpty());
        store.close();assertFalse(executor.isShutdown());assertEquals("caller-owned",executor.submit(() -> "caller-owned").get(5,TimeUnit.SECONDS));
    }
    @Test public void queuedWritesSnapshotInputAndCancellationSkipsQueuedWrite() throws Exception {
        ExecutorService executor=executor();SqliteChatMemoryStore store=store(executor);CountDownLatch release=new CountDownLatch(1);
        try {
            Future<?> block=executor.submit(() -> ReactiveConcurrencyJdk8Test.await(release));
            List<ChatMessage> source=messages("captured");CompletableFuture<Void> write=store.updateMessagesAsync("snapshot",source);source.clear();
            CompletableFuture<Void> cancel=store.updateMessagesAsync("cancelled",messages("not written"));assertTrue(cancel.cancel(true));
            release.countDown();block.get(5,TimeUnit.SECONDS);write.get(5,TimeUnit.SECONDS);executor.submit(() -> {}).get(5,TimeUnit.SECONDS);
            assertEquals(messages("captured"),store.getMessages("snapshot"));assertTrue(store.getMessages("cancelled").isEmpty());
        } finally { release.countDown(); }
    }
    @Test public void asyncRejectionValidationAndCloseUseFailedFutures() throws Exception {
        ExecutorService executor=executor();SqliteChatMemoryStore store=store(executor);
        assertThrows(ExecutionException.class,() -> store.updateMessagesAsync("s",null).get(5,TimeUnit.SECONDS));
        executor.shutdown();
        ExecutionException rejected=assertThrows(ExecutionException.class,() -> store.getMessagesAsync("s").get(5,TimeUnit.SECONDS));
        assertTrue(rejected.getCause() instanceof RejectedExecutionException);
        store.close();assertThrows(IllegalStateException.class,() -> store.getMessages("s"));
        assertThrows(ExecutionException.class,() -> store.deleteMessagesAsync("s").get(5,TimeUnit.SECONDS));
    }
    @Test public void concurrentWritersReplaceWholeSnapshotsAcrossInstances() throws Exception {
        SqliteChatMemoryStore first=store(),second=store();ExecutorService workers=Executors.newFixedThreadPool(4);executors.add(workers);
        List<Future<?>> tasks=new ArrayList<>();
        for(int writer=0;writer<4;writer++) {
            final int id=writer;final SqliteChatMemoryStore store=writer%2==0?first:second;
            tasks.add(workers.submit(() -> { for(int n=0;n<20;n++) {
                List<ChatMessage> snapshot=messages("writer-"+id+"-"+n);store.updateMessages("shared",snapshot);store.updateMessages("isolated-"+id,snapshot);
                List<ChatMessage> read=store.getMessages("shared");assertEquals(2,read.size());assertEquals(AiMessage.from("reply"),read.get(1));
            } }));
        }
        for(Future<?> task:tasks) task.get(15,TimeUnit.SECONDS);
        for(int writer=0;writer<4;writer++) assertEquals(messages("writer-"+writer+"-19"),first.getMessages("isolated-"+writer));
    }
    @Test public void busyFailureAndFinishedOperationsReleaseLocks() throws Exception {
        SqliteChatMemoryStore store=SqliteChatMemoryStore.builder().databasePath(database).busyTimeoutMillis(50).build();stores.add(store);
        try(Connection c=connection();Statement s=c.createStatement()) { s.execute("BEGIN EXCLUSIVE");
            assertThrows(IllegalStateException.class,() -> store.updateMessages("s",messages("locked")));s.execute("ROLLBACK"); }
        store.updateMessages("s",messages("ok"));store.getMessages("s");store.deleteMessages("s");
        try(Connection c=connection();Statement s=c.createStatement()) { s.execute("BEGIN EXCLUSIVE");s.execute("ROLLBACK"); }
        store.close();store.close();assertTrue(store().getMessages("s").isEmpty());
    }
    @Test public void windowEvictionDoesNotRestoreOrphanedToolResultsAfterReopen() {
        SqliteChatMemoryStore store=store();ToolExecutionRequest request=AiServicesJdk8Test.weatherCall();
        MessageWindowChatMemory memory=MessageWindowChatMemory.builder().id("s").maxMessages(3).chatMemoryStore(store).build();
        memory.add(UserMessage.from("question"));memory.add(AiMessage.from(request));memory.add(ToolExecutionResultMessage.from(request,"sunny"));
        memory.add(AiMessage.from("done"));memory.add(UserMessage.from("next"));store.close();
        List<ChatMessage> persisted=store().getMessages("s");
        assertFalse(persisted.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage));
        assertEquals(Arrays.asList(AiMessage.from("done"),UserMessage.from("next")),persisted);
    }
}
