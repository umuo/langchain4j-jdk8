package dev.langchain4j.agentic.jdk8;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class AgenticRecoveryJdk8Test {
    public interface Supervisor extends AgenticScopeAccess {
        ResultWithAgenticScope<String> invoke(@MemoryId String session, @V("request") String request);
    }

    public interface RecoveryWorkflow extends AgenticScopeAccess {
        @Agent String process(@MemoryId String session, @V("input") String input);
    }

    @After public void resetPersister() { AgenticScopePersister.setStore(null); }

    @Test(timeout = 10000) public void supervisorSuspendsWithoutExtraPlanningThenResumes() {
        AgenticScopePersister.setStore(new JsonStore());
        AtomicInteger calls = new AtomicInteger();
        List<String> replies = Arrays.asList(
                "{\"agentName\":\"askUser\",\"arguments\":{\"question\":\"Your name?\"}}",
                "{\"agentName\":\"query\",\"arguments\":{}}",
                "{\"agentName\":\"done\",\"arguments\":{\"response\":\"Hello Alice\"}}");
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                int index = calls.getAndIncrement();
                assertTrue("unexpected planner request " + index, index < replies.size());
                return ChatResponse.builder().aiMessage(AiMessage.from(replies.get(index))).build();
            }
        };
        HumanInTheLoop human = AgenticServices.humanInTheLoopBuilder().outputKey("name")
                .description("Ask for the name").responseProvider(scope -> new SuspendedResponse<String>("name-1")).build();
        Object query = AgenticServices.nonAiAgentBuilder(scope -> "queried:" + scope.readState("name", ""))
                .name("query").description("Query using the name").outputKey("queryResult").build();
        Supervisor supervisor = AgenticServices.supervisorBuilder(Supervisor.class).chatModel(model)
                .responseStrategy(SupervisorResponseStrategy.LAST).contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY)
                .subAgents(human, query).build();
        ResultWithAgenticScope<String> suspended = supervisor.invoke("session-1", "Greet the user");
        assertTrue(suspended.suspended());
        assertEquals(Collections.singleton("name-1"), suspended.agenticScope().pendingResponseIds());
        assertEquals(1, calls.get());
        ResultWithAgenticScope<String> completed = suspended.completePendingResponse("name-1", "Alice");
        assertFalse(completed.suspended());
        // LAST returns the last sub-agent output, per SupervisorResponseStrategy.
        assertEquals("queried:Alice", completed.result());
        assertEquals("queried:Alice", completed.agenticScope().readState("queryResult"));
        assertEquals(3, calls.get());
    }

    @Test(timeout = 30000) public void twoActualJava8ProcessesResumeCheckpointWithoutReplayingSteps() throws Exception {
        Path directory = Files.createDirectories(Paths.get("target", "agentic-restart-" + UUID.randomUUID()));
        try {
            runChild("write", directory);
            assertEquals(Arrays.asList("prepare", "ask"), Files.readAllLines(directory.resolve("calls.txt"), StandardCharsets.UTF_8));
            runChild("read", directory);
            assertEquals(Arrays.asList("prepare", "ask", "finish"), Files.readAllLines(directory.resolve("calls.txt"), StandardCharsets.UTF_8));
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(file);
            }
        }
    }

    private static void runChild(String mode, Path directory) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        File executable = new File(System.getProperty("java.home"), "bin/java");
        Path output = directory.resolve(mode + ".log");
        Process process = new ProcessBuilder(executable.getAbsolutePath(), "-Xverify:all", "-cp", classpath,
                AgenticRecoveryJdk8Test.class.getName(), mode, directory.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue("child timed out", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(output), StandardCharsets.UTF_8), 0, process.exitValue());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(3, TimeUnit.SECONDS)); }
        }
    }

    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        Path directory = Paths.get(args[1]);
        AgenticScopePersister.setStore(new FileStore(directory));
        RecoveryWorkflow workflow = workflow(directory);
        if (args[0].equals("write")) {
            try { workflow.process("session-1", "输入"); fail("must suspend"); }
            catch (AgenticSystemSuspendedException suspended) {
                assertEquals(Collections.singleton("approval-1"), suspended.scope().pendingResponseIds());
                assertEquals("processed:输入", suspended.scope().readState("processed"));
                assertTrue(suspended.scope().state().keySet().stream().anyMatch(k -> k.startsWith("__planner_state_")));
            }
        } else {
            AgenticScope restored = workflow.getAgenticScope("session-1");
            assertNotNull(restored);
            assertEquals("processed:输入", restored.readState("processed"));
            assertTrue(restored.completePendingResponse("approval-1", "批准"));
            assertFalse(restored.completePendingResponse("approval-1", "duplicate"));
            assertEquals("processed:输入|批准", workflow.process("session-1", "输入"));
            assertTrue(restored.pendingResponseIds().isEmpty());
            assertFalse(restored.state().keySet().stream().anyMatch(k -> k.startsWith("__planner_state_")));
        }
    }

    private static RecoveryWorkflow workflow(Path directory) {
        Object prepare = AgenticServices.nonAiAgentBuilder(scope -> {
            mark(directory, "prepare"); return "processed:" + scope.readState("input", "");
        }).name("prepare").inputKey(String.class, "input").outputKey("processed").build();
        HumanInTheLoop human = AgenticServices.humanInTheLoopBuilder().outputKey("approval")
                .responseProvider(scope -> { mark(directory, "ask"); return new SuspendedResponse<String>("approval-1"); }).build();
        Object finish = AgenticServices.nonAiAgentBuilder(scope -> {
            mark(directory, "finish"); return scope.readState("processed", "") + "|" + scope.readState("approval", "");
        }).name("finish").inputKeys(String.class, "processed", String.class, "approval").outputKey("answer").build();
        return AgenticServices.sequenceBuilder(RecoveryWorkflow.class).subAgents(prepare, human, finish).outputKey("answer").build();
    }

    private static void mark(Path directory, String call) {
        try { Files.write(directory.resolve("calls.txt"), (call + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    /** JSON snapshots, so tests cannot accidentally share the original scope object. */
    static class JsonStore implements AgenticScopeStore {
        final Map<AgenticScopeKey, String> snapshots = new ConcurrentHashMap<>();
        public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) { snapshots.put(key, AgenticScopeSerializer.toJson(scope)); return true; }
        public Optional<DefaultAgenticScope> load(AgenticScopeKey key) { return Optional.ofNullable(snapshots.get(key)).map(AgenticScopeSerializer::fromJson); }
        public boolean delete(AgenticScopeKey key) { return snapshots.remove(key) != null; }
        public Set<AgenticScopeKey> getAllKeys() { return new HashSet<>(snapshots.keySet()); }
    }

    /** Single-writer fixture for String memory IDs; not a general production persistence backend. */
    static class FileStore implements AgenticScopeStore {
        final Path directory;
        FileStore(Path directory) { this.directory = directory; }
        Path file(AgenticScopeKey key) {
            return directory.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString((key.agentId() + "\n" + key.memoryId()).getBytes(StandardCharsets.UTF_8)) + ".json");
        }
        public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) {
            try { Files.write(file(key), AgenticScopeSerializer.toJson(scope).getBytes(StandardCharsets.UTF_8)); return true; }
            catch (IOException e) { throw new RuntimeException(e); }
        }
        public Optional<DefaultAgenticScope> load(AgenticScopeKey key) {
            Path path = file(key);
            if (!Files.exists(path)) return Optional.empty();
            try { return Optional.of(AgenticScopeSerializer.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))); }
            catch (IOException e) { throw new RuntimeException(e); }
        }
        public boolean delete(AgenticScopeKey key) {
            try { return Files.deleteIfExists(file(key)); } catch (IOException e) { throw new RuntimeException(e); }
        }
        public Set<AgenticScopeKey> getAllKeys() {
            try (Stream<Path> files = Files.list(directory)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".json")).map(p -> {
                    String encoded = p.getFileName().toString().replaceFirst("\\.json$", "");
                    String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\n", 2);
                    return new AgenticScopeKey(parts[0], parts[1]);
                }).collect(Collectors.toSet());
            } catch (IOException e) { throw new RuntimeException(e); }
        }
    }
}
