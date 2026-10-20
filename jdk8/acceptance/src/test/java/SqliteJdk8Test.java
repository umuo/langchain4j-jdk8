import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.sqlite.SqliteEmbeddingStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.Assert.*;

public class SqliteJdk8Test {
    Path directory, database;
    final List<SqliteEmbeddingStore> stores = new ArrayList<>();
    @Before public void start() throws Exception {
        directory = Files.createTempDirectory(Paths.get("target"), "sqlite-");
        database = directory.resolve("vectors space # ?.db");
    }
    @After public void stop() throws Exception {
        for (SqliteEmbeddingStore store : stores) store.close();
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(file);
        }
    }
    SqliteEmbeddingStore store() { return store(database, 5000); }
    SqliteEmbeddingStore store(Path file, int timeout) {
        SqliteEmbeddingStore store = SqliteEmbeddingStore.builder().databasePath(file).busyTimeoutMillis(timeout).build();
        stores.add(store); return store;
    }
    Connection connection() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString()); }
    void sql(String sql) throws Exception { try (Connection c=connection(); Statement s=c.createStatement()) { s.execute(sql); } }
    static Embedding vector(float... values) { return Embedding.from(values); }
    static EmbeddingSearchRequest request(Embedding query) { return EmbeddingSearchRequest.builder().queryEmbedding(query).maxResults(1000).build(); }
    static TextSegment segment(String text, String tenant) { return TextSegment.from(text, Metadata.from("tenant", tenant)); }

    @Test public void actualJava8LoadsBundledNativeSqliteAndReopensFile() throws Exception {
        assertEquals("1.8", System.getProperty("java.specification.version"));
        SqliteEmbeddingStore first=store(); first.add("stable", vector(1,0), segment("persisted", "a")); first.close();
        try (Connection c=connection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT sqlite_version()")) {
            r.next(); assertEquals("3.53.4",r.getString(1));
        }
        SqliteEmbeddingStore reopened=store();
        assertEquals(1,reopened.size());
        EmbeddingMatch<TextSegment> match=reopened.search(request(vector(1,0))).matches().get(0);
        assertEquals("stable",match.embeddingId()); assertEquals("persisted",match.embedded().text());
        assertEquals("a",match.embedded().metadata().getString("tenant"));
        assertTrue(Files.isRegularFile(database));
        assertTrue(System.getProperty("org.sqlite.tmpdir").contains("/Volumes/Nas/"));
    }
    @Test public void allMetadataTypesAndLargeUnicodeSurviveReopen() {
        Map<String,Object> values=new HashMap<>();
        values.put("text",String.join("",Collections.nCopies(40000,"中")));
        values.put("int",12); values.put("long",Long.MAX_VALUE); values.put("float",1.25f); values.put("double",-2.75d);
        values.put("uuid",UUID.fromString("e75941fb-71b3-4c21-ae67-5b3cc3aee69d"));
        Metadata metadata=new Metadata(values);
        SqliteEmbeddingStore first=store(); first.add("types",vector(1,-0.0f),TextSegment.from("你好\nworld",metadata)); first.close();
        EmbeddingMatch<TextSegment> result=store().search(request(vector(1,0))).matches().get(0);
        assertEquals(values,result.embedded().metadata().toMap());
        assertEquals("你好\nworld",result.embedded().text()); assertArrayEquals(new float[]{1,-0.0f},result.embedding().vector(),0f);
    }
    @Test public void upsertReplacesAllFieldsAndDuplicateBatchIdsUseLastValue() {
        SqliteEmbeddingStore store=store();
        store.add("id",vector(1,0),segment("old","a"));
        store.addAll(Arrays.asList("id","id"),Arrays.asList(vector(1,0),vector(0,1)),Arrays.asList(segment("middle","a"),segment("new","b")));
        assertEquals(1,store.size());
        EmbeddingMatch<TextSegment> result=store.search(request(vector(0,1))).matches().get(0);
        assertEquals("new",result.embedded().text()); assertEquals("b",result.embedded().metadata().getString("tenant"));
        assertEquals(1d,result.score(),0.00001);
    }
    @Test public void generatedIdsBatchNullContentsAndEmptyContracts() {
        SqliteEmbeddingStore store=store();
        List<String> ids=store.addAll(Arrays.asList(vector(1,0),vector(0,1)));
        assertEquals(2,new HashSet<>(ids).size()); assertEquals(2,store.size());
        store.addAll(null,null,null); store.addAll(Collections.emptyList(),Collections.emptyList(),null);
        store.removeAll((Collection<String>)null); store.removeAll(Collections.emptyList());
        assertEquals(2,store.size());
        assertThrows(IllegalArgumentException.class,() -> store.addAll(null,Collections.singletonList(vector(1,0)),null));
        assertThrows(IllegalArgumentException.class,() -> store.addAll(Collections.singletonList("id"),Collections.singletonList(vector(1,0)),Collections.emptyList()));
        assertTrue(store.search(EmbeddingSearchRequest.builder().queryEmbedding(vector(1,0)).filter(metadataKey("tenant").isEqualTo("a")).build()).matches().isEmpty());
    }
    @Test public void filteringScoreThresholdTopKAndStableTieOrder() {
        SqliteEmbeddingStore store=store();
        store.add("b",vector(1,0),segment("match b","public")); store.add("a",vector(1,0),segment("match a","public"));
        store.add("private",vector(1,0),segment("secret","private")); store.add("orthogonal",vector(0,1),segment("other","public"));
        EmbeddingSearchRequest request=EmbeddingSearchRequest.builder().queryEmbedding(vector(1,0)).maxResults(2).minScore(0.9)
                .filter(metadataKey("tenant").isEqualTo("public").and(metadataKey("tenant").isNotEqualTo("private"))).build();
        List<EmbeddingMatch<TextSegment>> matches=store.search(request).matches();
        assertEquals(Arrays.asList("a","b"),Arrays.asList(matches.get(0).embeddingId(),matches.get(1).embeddingId()));
        assertTrue(store.search(EmbeddingSearchRequest.builder().queryEmbedding(vector(-1,0)).minScore(0.9).build()).matches().isEmpty());
    }
    @Test public void removalsByIdsFilterAndAllPersistButDimensionRemainsFixed() {
        SqliteEmbeddingStore store=store();
        store.add("one",vector(1,0),segment("one","a"));store.add("two",vector(1,0),segment("two","b"));store.add("bare",vector(1,0));
        store.removeAll(metadataKey("tenant").isEqualTo("a")); assertEquals(2,store.size());
        store.removeAll(Arrays.asList("missing","two","two")); assertEquals(1,store.size());
        store.removeAll(); assertEquals(0,store.size()); store.close();
        SqliteEmbeddingStore next=store(); assertEquals(0,next.size());
        assertThrows(IllegalArgumentException.class,() -> next.add(vector(1,0,0)));
    }
    @Test public void failingBatchRollsBackRowsAndInitialDimension() throws Exception {
        SqliteEmbeddingStore store=store();
        sql("CREATE TRIGGER reject_bad BEFORE INSERT ON lc4j_embeddings WHEN NEW.id='bad' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        assertThrows(IllegalStateException.class,() -> store.addAll(Arrays.asList("good","bad"),Arrays.asList(vector(1,0),vector(0,1)),null));
        assertEquals(0,store.size());
        store.add("after",vector(1,0,0)); assertEquals(1,store.size());
    }
    @Test public void failedUpsertAndDeleteBatchesRollBackExistingRows() throws Exception {
        SqliteEmbeddingStore store=store();store.add("good",vector(1,0),segment("original","a"));store.add("blocked",vector(1,0));
        sql("CREATE TRIGGER reject_bad BEFORE INSERT ON lc4j_embeddings WHEN NEW.id='bad' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        assertThrows(IllegalStateException.class,() -> store.addAll(Arrays.asList("good","bad"),Arrays.asList(vector(0,1),vector(0,1)),Arrays.asList(segment("modified","b"),null)));
        assertEquals("original",store.search(request(vector(1,0))).matches().stream().filter(m -> m.embeddingId().equals("good")).findFirst().get().embedded().text());
        sql("CREATE TRIGGER reject_delete BEFORE DELETE ON lc4j_embeddings WHEN OLD.id='blocked' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        assertThrows(IllegalStateException.class,() -> store.removeAll(Arrays.asList("good","blocked")));
        assertEquals(2,store.size());
    }
    @Test public void incompatibleDimensionsNonFiniteVectorsAndBlankIdsAreRejected() {
        SqliteEmbeddingStore store=store();store.add("base",vector(1,0));
        assertThrows(IllegalArgumentException.class,() -> store.addAll(Arrays.asList("a","b"),Arrays.asList(vector(1,0),vector(1,0,0)),null));
        assertEquals(1,store.size());
        assertThrows(IllegalArgumentException.class,() -> store.search(request(vector(1,0,0))));
        assertThrows(IllegalArgumentException.class,() -> store.add(vector(Float.NaN,0)));
        assertThrows(IllegalArgumentException.class,() -> store.add(vector(Float.POSITIVE_INFINITY,0)));
        assertThrows(IllegalArgumentException.class,() -> store.add(" ",vector(1,0)));
    }
    @Test public void schemaVersionAndCorruptPayloadsFailWithoutSilentDataLoss() throws Exception {
        SqliteEmbeddingStore store=store();store.add("row",vector(1,0),segment("text","a"));
        sql("UPDATE lc4j_embeddings SET vector=x'0102'");
        assertThrows(IllegalStateException.class,() -> store.search(request(vector(1,0)))); assertEquals(1,store.size());
        store.add("row",vector(1,0),segment("text","a"));sql("UPDATE lc4j_embeddings SET metadata=x'0000006300000000'");
        assertThrows(IllegalStateException.class,() -> store.search(request(vector(1,0))));
        sql("UPDATE lc4j_embedding_config SET schema_version=99");store.close();
        assertThrows(IllegalStateException.class,this::store);
        try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT schema_version FROM lc4j_embedding_config")) { r.next();assertEquals(99,r.getInt(1)); }
    }
    @Test public void closeIsIdempotentAndOperationsReleaseDatabaseLocks() throws Exception {
        SqliteEmbeddingStore store=store();store.add(vector(1,0));store.search(request(vector(1,0)));store.removeAll();
        try(Connection c=connection();Statement s=c.createStatement()) { s.execute("BEGIN EXCLUSIVE"); s.execute("ROLLBACK"); }
        store.close();store.close();assertThrows(IllegalStateException.class,store::size);
        assertThrows(IllegalStateException.class,() -> store.add(vector(1,0)));assertEquals(0,store().size());
    }
    @Test public void busyTimeoutFailureDoesNotLeavePoisonedConnections() throws Exception {
        SqliteEmbeddingStore store=store(database,50);
        try(Connection c=connection();Statement s=c.createStatement()) {
            s.execute("BEGIN EXCLUSIVE");
            assertThrows(IllegalStateException.class,() -> store.add(vector(1,0)));s.execute("ROLLBACK");
        }
        store.add(vector(1,0));assertEquals(1,store.size());
    }
    @Test public void concurrentStoreInstancesCommitWithoutLosingRows() throws Exception {
        SqliteEmbeddingStore first=store(),second=store();ExecutorService workers=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int writer=0;writer<4;writer++) {
                final int number=writer;final SqliteEmbeddingStore store=writer%2==0?first:second;
                tasks.add(workers.submit(() -> { for(int i=0;i<30;i++) store.add(number+"-"+i,vector(1,0),segment("row","a")); }));
            }
            for(Future<?> task:tasks) task.get(15,TimeUnit.SECONDS);
            assertEquals(120,first.size());assertEquals(120,second.search(request(vector(1,0))).matches().size());
        } finally { workers.shutdownNow(); }
    }
    @Test public void readTransactionSeesStableSnapshotWhileAnotherInstanceWrites() throws Exception {
        SqliteEmbeddingStore reader=store(),writer=store();reader.add("original",vector(1,0),segment("original","a"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            dev.langchain4j.store.embedding.filter.Filter barrier=metadata -> { entered.countDown();ReactiveConcurrencyJdk8Test.await(release);return true; };
            Future<EmbeddingSearchResult<TextSegment>> result=workers.submit(() -> reader.search(EmbeddingSearchRequest.builder().queryEmbedding(vector(1,0)).filter(barrier).build()));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> write=workers.submit(() -> writer.add("later",vector(1,0),segment("later","a")));
            release.countDown();assertEquals(1,result.get(5,TimeUnit.SECONDS).matches().size());write.get(5,TimeUnit.SECONDS);
            assertEquals(2,reader.size());
        } finally { release.countDown();workers.shutdownNow(); }
    }
    @Test public void blockingSearchRequiresExplicitRagOffload() throws Exception {
        SqliteEmbeddingStore store=store();store.add(vector(1,0,0),segment(RagJdk8Test.APPLE,"public"));
        EmbeddingStoreContentRetriever strict=EmbeddingStoreContentRetriever.builder().embeddingModel(RagJdk8Test.EMBEDDINGS).embeddingStore(store).build();
        ExecutionException failure=assertThrows(ExecutionException.class,() -> strict.retrieveAsync(Query.from("apple")).get(5,TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof dev.langchain4j.exception.UnsupportedFeatureException);
        assertEquals(RagJdk8Test.APPLE,EmbeddingStoreContentRetriever.builder().embeddingModel(RagJdk8Test.EMBEDDINGS)
                .embeddingStore(store).offloadBlocking(true).build().retrieveAsync(Query.from("apple")).get(5,TimeUnit.SECONDS).get(0).textSegment().text());
    }
    @Test public void invalidPathsAndTimeoutAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> SqliteEmbeddingStore.builder().build());
        assertThrows(IllegalArgumentException.class,() -> SqliteEmbeddingStore.builder().databasePath(directory).build());
        assertThrows(IllegalArgumentException.class,() -> SqliteEmbeddingStore.builder().databasePath(database).busyTimeoutMillis(-1).build());
    }
    @Test public void emptyBatchesDoNotTouchLockedBackend() throws Exception {
        SqliteEmbeddingStore store=store(database,50);
        try(Connection c=connection();Statement statement=c.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");
            store.removeAll(Collections.emptyList());store.removeAll((Collection<String>)null);
            store.addAll(null,null,null);assertTrue(store.addAll(Collections.emptyList()).isEmpty());
            statement.execute("ROLLBACK");
        }
        assertEquals(0,store.size());
    }
    @Test public void exactSearchAgreesWithInMemoryReferenceAfterRoundTrip() {
        SqliteEmbeddingStore sqlite=store();
        dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<TextSegment> reference=new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
        Random random=new Random(816L);
        for(int i=0;i<80;i++) {
            float[] vector=new float[8];for(int j=0;j<vector.length;j++) vector[j]=random.nextFloat()*2-1;
            Embedding embedding=vector(vector);TextSegment text=segment("document-"+i,i%2==0?"a":"b");
            sqlite.add("id-"+i,embedding,text);reference.add("id-"+i,embedding,text);
        }
        sqlite.close();SqliteEmbeddingStore reopened=store();
        for(int i=0;i<10;i++) {
            float[] query=new float[8];for(int j=0;j<query.length;j++) query[j]=random.nextFloat()*2-1;
            EmbeddingSearchRequest request=EmbeddingSearchRequest.builder().queryEmbedding(vector(query)).maxResults(7).minScore(0.6)
                    .filter(metadataKey("tenant").isEqualTo("a")).build();
            List<EmbeddingMatch<TextSegment>> actual=reopened.search(request).matches(),expected=reference.search(request).matches();
            assertEquals(expected.size(),actual.size());
            for(int j=0;j<actual.size();j++) {
                assertEquals(expected.get(j).embeddingId(),actual.get(j).embeddingId());
                assertEquals(expected.get(j).score(),actual.get(j).score(),0.0000001);
                assertEquals(expected.get(j).embedded(),actual.get(j).embedded());
            }
        }
    }
    @Test public void filterFailureRollsBackAndReleasesTransaction() {
        SqliteEmbeddingStore store=store();store.add("one",vector(1,0),segment("one","a"));store.add("two",vector(0,1),segment("two","b"));
        AtomicInteger evaluated=new AtomicInteger();
        dev.langchain4j.store.embedding.filter.Filter failing=metadata -> { if(evaluated.incrementAndGet()==2) throw new IllegalStateException("filter failure");return true; };
        assertThrows(IllegalStateException.class,() -> store.removeAll(failing));assertEquals(2,store.size());
        store.removeAll(metadataKey("tenant").isEqualTo("a"));assertEquals(1,store.size());
    }
    @Test public void existingNonDatabaseFileIsRejectedWithoutOverwritingIt() throws Exception {
        byte[] contents="personal fixture, not SQLite".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database,contents);
        assertThrows(IllegalStateException.class,this::store);
        assertArrayEquals(contents,Files.readAllBytes(database));
    }

}
