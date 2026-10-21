package dev.langchain4j.agentic.patterns.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.patterns.voting.*;
import dev.langchain4j.agentic.scope.*;
import dev.langchain4j.service.MemoryId;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

public class VotingRecoveryJdk8Test {
    public interface Vote extends AgenticScopeAccess { @Agent ResultWithAgenticScope<Double> run(@MemoryId String id); }
    @Test(timeout=15000) public void syncHumanVoteResumesWithoutRepeatingFinishedVotes() throws Exception { child("sync", null); }
    @Test(timeout=15000) public void asyncHumanVoteResumesWithoutRepeatingFinishedVotes() throws Exception { child("async", null); }
    @Test(timeout=15000) public void partialHumanResponsesRemainSuspended() throws Exception { child("two", null); }
    @Test(timeout=15000) public void suspendedAsyncConsumerReentersAfterHumanVote() throws Exception { child("consumer", null); }
    @Test(timeout=30000) public void votesResumeAcrossTwoActualJava8Processes() throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("target"),"voting-restart-");
        try { child("write",dir); child("read",dir); List<String> calls=Files.readAllLines(dir.resolve("calls.txt"),StandardCharsets.UTF_8);
            assertEquals(2,calls.size());assertEquals(1,Collections.frequency(calls,"fixed"));assertEquals(1,Collections.frequency(calls,"ask"));
        } finally {try(Stream<Path> files=Files.walk(dir)){for(Path f:files.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))Files.delete(f);}}
    }
    @Test(timeout=30000) public void asyncConsumerResumesAcrossTwoActualJava8Processes() throws Exception {
        restart("consumer",4,2,"consume");
    }
    @Test(timeout=30000) public void skippedVoterStaysCompletedAcrossTwoActualJava8Processes() throws Exception {
        restart("skipped",3,1,"skip-check");
    }
    static void restart(String mode,int count,int frequency,String marker) throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("target"),"voting-"+mode+"-");
        try { child(mode+"-write",dir); child(mode+"-read",dir);List<String> calls=Files.readAllLines(dir.resolve("calls.txt"),StandardCharsets.UTF_8);
            assertEquals(count,calls.size());assertEquals(1,Collections.frequency(calls,"fixed"));assertEquals(1,Collections.frequency(calls,"ask"));assertEquals(frequency,Collections.frequency(calls,marker));
        } finally {try(Stream<Path> files=Files.walk(dir)){for(Path f:files.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))Files.delete(f);}}
    }
    static void child(String mode,Path dir) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"voting-recovery-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),VotingRecoveryJdk8Test.class.getName(),mode,dir==null?"":dir.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("child timed out: "+mode,p.waitFor(8,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally {if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        String mode=args[0];boolean consumerMode=mode.contains("consumer");boolean skippedMode=mode.contains("skipped");Path dir=args[1].isEmpty()?null:Paths.get(args[1]);AgenticScopePersister.setStore(dir==null?null:new FileStore(dir));
        AtomicInteger fixed=new AtomicInteger(),asks=new AtomicInteger(),others=new AtomicInteger(),consumer=new AtomicInteger(),skipChecks=new AtomicInteger(),errors=new AtomicInteger();
        Object one=AgenticServices.nonAiAgentBuilder(scope->{fixed.incrementAndGet();mark(dir,"fixed");return 2;}).name("fixed").outputKey("fixed").build();
        Object human=AgenticServices.humanInTheLoopBuilder().async(!mode.equals("sync")).outputKey("human").responseProvider(scope->{asks.incrementAndGet();mark(dir,"ask");return new SuspendedResponse<Integer>("approval");}).build();
        List<Object> agents=new ArrayList<>(Arrays.asList(one,human));
        if(mode.equals("two")) agents.add(AgenticServices.humanInTheLoopBuilder().outputKey("other").responseProvider(scope->{others.incrementAndGet();return new SuspendedResponse<Integer>("other");}).build());
        if(consumerMode) agents.add(AgenticServices.nonAiAgentBuilder(scope->{consumer.incrementAndGet();mark(dir,"consume");return ((Number)scope.readState("human")).intValue()+2;}).name("consumer").async(true).outputKey("consumer").build());
        if(skippedMode) agents.add(AgenticServices.nonAiAgentBuilder(scope->{others.incrementAndGet();return 99;}).name("skipped").activationCondition(scope->{skipChecks.incrementAndGet();mark(dir,"skip-check");return false;}).outputKey("skipped").build());
        Vote vote=AgenticServices.plannerBuilder(Vote.class).subAgents(agents.toArray()).planner(()->new VotingPlanner(VotingStrategy.average()))
                .errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
        if(mode.endsWith("read")) {
            AgenticScope scope=vote.getAgenticScope("session");assertNotNull(scope);assertTrue(scope.completePendingResponse("approval",4));
            ResultWithAgenticScope<Double> done=vote.run("session");assertFalse(done.suspended());assertEquals(consumerMode?4.0:3.0,done.result(),0.0);assertEquals(0,asks.get());assertEquals(0,fixed.get());assertEquals(0,skipChecks.get());assertEquals(0,others.get());assertEquals(consumerMode?1:0,consumer.get());return;
        }
        ResultWithAgenticScope<Double> paused=vote.run("session");assertTrue(paused.suspended());assertEquals(0,errors.get());
        assertEquals(1,fixed.get());assertEquals(1,asks.get());assertTrue(vote.run("session").suspended());assertEquals(1,fixed.get());assertEquals(1,asks.get());
        if(consumerMode)assertEquals(1,consumer.get());if(skippedMode)assertEquals(1,skipChecks.get());
        if(mode.endsWith("write"))return;
        ResultWithAgenticScope<Double> result=paused.completePendingResponse("approval",4);
        if(mode.equals("two")){assertTrue(result.suspended());assertEquals(Collections.singleton("other"),result.agenticScope().pendingResponseIds());assertEquals(1,others.get());result=result.completePendingResponse("other",6);}
        assertFalse(result.suspended());assertEquals(mode.equals("two")||consumerMode?4.0:3.0,result.result(),0.0);
        assertEquals(1,fixed.get());assertEquals(1,asks.get());assertEquals(0,errors.get());if(consumerMode)assertEquals(2,consumer.get());if(skippedMode){assertEquals(1,skipChecks.get());assertEquals(0,others.get());}
    }
    static void mark(Path dir,String call){if(dir==null)return;try{Files.write(dir.resolve("calls.txt"),(call+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){throw new RuntimeException(e);}}
    static class FileStore implements AgenticScopeStore {
        final Path dir;FileStore(Path dir){this.dir=dir;}
        Path file(AgenticScopeKey key){return dir.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString((key.agentId()+"\n"+key.memoryId()).getBytes(StandardCharsets.UTF_8))+".json");}
        public boolean save(AgenticScopeKey key,DefaultAgenticScope scope){try{Files.write(file(key),AgenticScopeSerializer.toJson(scope).getBytes(StandardCharsets.UTF_8));return true;}catch(IOException e){throw new RuntimeException(e);}}
        public Optional<DefaultAgenticScope> load(AgenticScopeKey key){if(!Files.exists(file(key)))return Optional.empty();try{return Optional.of(AgenticScopeSerializer.fromJson(new String(Files.readAllBytes(file(key)),StandardCharsets.UTF_8)));}catch(IOException e){throw new RuntimeException(e);}}
        public boolean delete(AgenticScopeKey key){try{return Files.deleteIfExists(file(key));}catch(IOException e){throw new RuntimeException(e);}}
        public Set<AgenticScopeKey> getAllKeys(){try(Stream<Path> files=Files.list(dir)){return files.filter(f->f.getFileName().toString().endsWith(".json")).map(f->{String stem=f.getFileName().toString().replaceFirst("\\.json$","");String[] key=new String(Base64.getUrlDecoder().decode(stem),StandardCharsets.UTF_8).split("\\n",2);return new AgenticScopeKey(key[0],key[1]);}).collect(Collectors.toSet());}catch(IOException e){throw new RuntimeException(e);}}
    }
}
