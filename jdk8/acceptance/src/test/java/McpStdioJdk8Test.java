import com.fasterxml.jackson.databind.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.*;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.protocol.McpListToolsRequest;
import dev.langchain4j.mcp.transport.stdio.JsonRpcIoHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real Java 8 parent and child processes; no external server or credentials. */
public class McpStdioJdk8Test {
    StdioMcpTransport transport;
    DefaultMcpClient client;
    ExecutorService supplied;
    final CountDownLatch progress = new CountDownLatch(1);
    final List<String> logs = new CopyOnWriteArrayList<>();
    final List<String> updates = new CopyOnWriteArrayList<>();

    List<String> command(String mode) {
        return new ArrayList<>(Arrays.asList(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Server.class.getName(), mode));
    }
    DefaultMcpClient connect(String mode, long timeout) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        transport = StdioMcpTransport.builder().command(command(mode))
                .environment(Collections.singletonMap("MCP_TEST_VALUE", "synthetic"))
                .workingDirectory(Paths.get(System.getProperty("user.dir"))).executorService(supplied).build();
        client = DefaultMcpClient.builder().transport(transport).autoHealthCheck(false)
                .initializationTimeout(Duration.ofSeconds(5)).toolExecutionTimeout(Duration.ofMillis(timeout))
                .logHandler(m -> logs.add(m.toString())).progressHandler(n -> progress.countDown())
                .onResourceUpdated((c, uri) -> updates.add(uri)).build();
        return client;
    }
    CompletableFuture<dev.langchain4j.service.tool.ToolExecutionResult> call(String action) {
        return client.executeToolAsync(ToolExecutionRequest.builder().id("call").name("echo")
                .arguments("{\"action\":\""+action+"\"}").build(),
                InvocationContext.builder().chatMemoryId("stdio").build());
    }
    @After public void close() throws Exception {
        if (client != null) client.close();
        if (transport != null) transport.close();
        if (supplied != null) supplied.shutdownNow();
    }
    @Test public void utf8AndEnvironmentReachRealChild() throws Exception {
        connect("normal", 5000);
        assertEquals("echo", client.listTools().get(0).name());
        assertTrue(call("echo").get(5, TimeUnit.SECONDS).resultText().contains("中文 🚀"));
        assertEquals(0, progress.getCount());
        assertFalse(logs.isEmpty());
    }
    @Test public void resourcesAndPromptsOverStdio() throws Exception {
        connect("normal", 5000);
        assertEquals(2, client.listResources().size());
        assertEquals(1, client.listResourceTemplates().size());
        List<McpResourceContents> result = client.readResource("test://data").contents();
        assertEquals("中文 🚀", ((McpTextResourceContents)result.get(0)).text());
        assertEquals(McpResourceContents.Type.BLOB, result.get(1).type());
        assertEquals("greet", client.listPrompts().get(0).name());
        McpGetPromptResult prompt = client.getPrompt("greet", Collections.singletonMap("name", "世界"));
        assertTrue(prompt.messages().get(0).toChatMessage().toString().contains("世界"));
        client.subscribeToResource("test://data");
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (updates.isEmpty() && System.nanoTime()<end) Thread.sleep(5);
        assertEquals(Collections.singletonList("test://data"), updates);
        client.unsubscribeFromResource("test://data");
    }
    @Test public void cancelNotifiesChildAndAllowsNextRequest() throws Exception {
        connect("normal", 5000);
        CompletableFuture<?> pending = call("hold");
        assertTrue(progress.await(5, TimeUnit.SECONDS));
        assertTrue(pending.cancel(true));
        assertTrue(call("cancelled").get(5, TimeUnit.SECONDS).resultText().contains("true"));
    }
    @Test public void timeoutNotifiesChildAndAllowsNextRequest() throws Exception {
        connect("normal", 150);
        assertTrue(call("hold").get(5, TimeUnit.SECONDS).isError());
        assertTrue(call("cancelled").get(5, TimeUnit.SECONDS).resultText().contains("true"));
    }
    @Test public void exitFailsPendingAndHealth() throws Exception {
        connect("normal", 5000); Process process = transport.getProcess();
        assertThrows(ExecutionException.class, () -> call("exit").get(5, TimeUnit.SECONDS));
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> transport.checkHealth());
    }
    @Test public void stdoutEofFailsPendingAndStopsChild() throws Exception {
        connect("normal", 5000); Process process = transport.getProcess();
        assertThrows(ExecutionException.class, () -> call("eof").get(5, TimeUnit.SECONDS));
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
    }
    @Test public void closeKillsChildAndFailsPendingIdempotently() throws Exception {
        connect("normal", 5000); Process process = transport.getProcess();
        CompletableFuture<?> pending = call("hold"); assertTrue(progress.await(5, TimeUnit.SECONDS));
        long start = System.nanoTime(); transport.close(); transport.close();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3000);
        assertFalse(process.isAlive());
        assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> transport.checkHealth());
    }
    @Test public void externallyKilledChildFailsPending() throws Exception {
        connect("normal", 5000); Process process = transport.getProcess();
        CompletableFuture<?> pending = call("hold"); assertTrue(progress.await(5, TimeUnit.SECONDS));
        process.destroyForcibly();
        assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
    }
    @Test public void malformedLineDoesNotKillReader() throws Exception {
        connect("malformed", 5000);
        assertEquals("echo", client.listTools().get(0).name());
        assertTrue(call("echo").get(5, TimeUnit.SECONDS).resultText().contains("中文"));
    }
    @Test public void concurrentRequestsDoNotInterleaveLines() throws Exception {
        connect("normal", 5000);
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for(int i=0;i<100;i++) calls.add(call("echo"));
        CompletableFuture.allOf(calls.toArray(new CompletableFuture<?>[0])).get(10, TimeUnit.SECONDS);
    }
    @Test public void callerExecutorRemainsUsableAfterClose() throws Exception {
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        supplied = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "stdio-caller-owned");
            thread.setUncaughtExceptionHandler((t, error) -> uncaught.compareAndSet(null, error));
            return thread;
        });
        connect("normal", 5000); client.listTools(); transport.close();
        assertEquals("alive", supplied.submit(() -> "alive").get(5, TimeUnit.SECONDS));
        assertNull("Closing process streams must not escape into the caller executor", uncaught.get());
    }
    @Test public void restartStopsPreviousGenerationAndDoesNotReplay() throws Exception {
        connect("normal", 5000); Process old = transport.getProcess();
        CompletableFuture<?> pending = call("hold"); assertTrue(progress.await(5, TimeUnit.SECONDS));
        ConcurrentHashMap<Long,CompletableFuture<String>> registry = new ConcurrentHashMap<>();
        McpOperationHandler handler = new McpOperationHandler(registry, Collections::emptyList, transport,
                m -> {}, () -> {}, () -> {}, () -> {}, uri -> {}, null, null, null, null, null);
        transport.start(handler);
        assertFalse(old.isAlive());
        assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertTrue(transport.sendRequest(new McpListToolsRequest(900L, null)).get(5, TimeUnit.SECONDS).contains("echo"));
        assertTrue(registry.isEmpty());
    }
    @Test public void failedStartupAndClosedStartAreExplicit() throws Exception {
        transport = StdioMcpTransport.builder().command(Collections.singletonList("/nonexistent/mcp-java8-fixture")).build();
        assertThrows(IllegalStateException.class, () -> DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).build());
        transport.close();
        assertThrows(IllegalStateException.class, () -> transport.start(null));
        assertThrows(ExecutionException.class, () -> transport.sendRequest(new McpListToolsRequest(1L,null)).get());
    }
    @Test public void rejectedIoExecutorDoesNotLeakProcess() throws Exception {
        supplied = Executors.newFixedThreadPool(2); supplied.shutdownNow();
        assertThrows(IllegalStateException.class, () -> connect("normal", 5000));
        assertNull(transport.getProcess());
    }
    @Test public void unresponsiveShutdownIsForciblyBounded() throws Exception {
        connect("ignoreTerm", 5000); Process process = transport.getProcess();
        long started = System.nanoTime(); transport.close();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000);
        assertFalse(process.isAlive());
    }
    @Test public void initializationTimeoutCleansUpChild() throws Exception {
        transport = StdioMcpTransport.builder().command(command("noInit")).build();
        assertThrows(RuntimeException.class, () -> DefaultMcpClient.builder().transport(transport)
                .autoHealthCheck(false).initializationTimeout(Duration.ofMillis(300)).build());
        assertNull(transport.getProcess());
    }
    @Test public void commandAndEnvironmentAreDefensiveSnapshots() throws Exception {
        List<String> command=command("normal");Map<String,String> environment=new HashMap<>();
        environment.put("MCP_TEST_VALUE","snapshot");
        transport=StdioMcpTransport.builder().command(command).environment(environment).build();
        command.clear(); environment.put("MCP_TEST_VALUE","mutated");
        client=DefaultMcpClient.builder().transport(transport).autoHealthCheck(false).build();
        assertEquals("snapshot 中文 🚀",call("echo").get(5,TimeUnit.SECONDS).resultText());
    }
    @Test public void brokenWriterReportsIoFailure() throws Exception {
        JsonRpcIoHandler io = new JsonRpcIoHandler(new ByteArrayInputStream(new byte[0]), new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("broken pipe"); }
        }, ignored -> {}, false);
        assertThrows(IOException.class, () -> io.submit("{}"));
    }
    @Test public void multilineAndClosedWritesAreRejected() throws Exception {
        JsonRpcIoHandler io = new JsonRpcIoHandler(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), ignored -> {}, false);
        assertThrows(IOException.class, () -> io.submit("{}\n{}")); io.close();
        assertThrows(IOException.class, () -> io.submit("{}"));
    }

    /** Synthetic line-delimited MCP server running in a separately launched actual Java 8 JVM. */
    public static class Server {
        static final ObjectMapper json = new ObjectMapper();
        static boolean cancelled;
        public static void main(String[] args) throws Exception {
            if (!System.getProperty("java.version").startsWith("1.8.")) System.exit(91);
            if ("ignoreTerm".equals(args[0])) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(30000); } catch (InterruptedException ignored) { }
            }));
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8),true);
            if ("malformed".equals(args[0])) out.println("not-json");
            String line;
            while ((line=input.readLine())!=null) {
                JsonNode request=json.readTree(line); String method=request.path("method").asText();
                JsonNode params=request.path("params");
                if (!request.has("id")) { if (method.equals("notifications/cancelled")) cancelled=true; continue; }
                if ("noInit".equals(args[0]) && method.equals("initialize")) continue;
                Object result;
                switch(method) {
                    case "initialize": result=map("protocolVersion","2025-11-25","serverInfo",map("name","child","version","1"),
                            "capabilities",map("tools",map("listChanged",true),"resources",map("subscribe",true,"listChanged",true),"prompts",map("listChanged",true),"logging",map())); break;
                    case "tools/list": result=map("tools",Arrays.asList(map("name","echo","inputSchema",map("type","object","properties",map("action",map("type","string")))))); break;
                    case "tools/call":
                        String action=params.path("arguments").path("action").asText();
                        if (action.equals("exit")) System.exit(3);
                        if (action.equals("eof")) { System.out.close(); Thread.sleep(30000); return; }
                        out.println(json.writeValueAsString(map("jsonrpc","2.0","method","notifications/progress","params",map("progressToken",params.path("_meta").path("progressToken").asText(),"progress",1,"total",2))));
                        out.println(json.writeValueAsString(map("jsonrpc","2.0","method","notifications/message","params",map("level","info","logger","fixture","data","synthetic log"))));
                        if (action.equals("hold")) continue;
                        result=map("content",Arrays.asList(map("type","text","text",action.equals("cancelled") ? String.valueOf(cancelled) : System.getenv("MCP_TEST_VALUE")+" 中文 🚀"))); break;
                    case "resources/list":
                        boolean next=params.has("cursor");
                        result=next ? map("resources",Arrays.asList(map("uri","test://second","name","second"))) :
                                map("resources",Arrays.asList(map("uri","test://data","name","data")),"nextCursor","next"); break;
                    case "resources/templates/list": result=map("resourceTemplates",Arrays.asList(map("uriTemplate","test://{name}","name","template"))); break;
                    case "resources/read": result=map("contents",Arrays.asList(map("uri","test://data","text","中文 🚀","mimeType","text/plain"),map("uri","test://blob","blob","AQID","mimeType","application/octet-stream"))); break;
                    case "resources/subscribe":
                        out.println(json.writeValueAsString(map("jsonrpc","2.0","method","notifications/resources/updated","params",map("uri",params.path("uri").asText()))));
                        result=map(); break;
                    case "prompts/list": result=map("prompts",Arrays.asList(map("name","greet","arguments",Arrays.asList(map("name","name","required",true))))); break;
                    case "prompts/get": result=map("description","greeting","messages",Arrays.asList(map("role","user","content",map("type","text","text","Hello "+params.path("arguments").path("name").asText())))); break;
                    default: result=map();
                }
                out.println(json.writeValueAsString(map("jsonrpc","2.0","id",request.path("id").asLong(),"result",result)));
                System.err.println("fixture handled "+method);
            }
        }
        static Map<String,Object> map(Object... pairs) {
            Map<String,Object> map=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]); return map;
        }
    }
}
