package dev.langchain4j.agentic.patterns.jdk8;

import dev.langchain4j.agentic.*;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.patterns.debate.*;
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

public class DebateRecoveryJdk8Test {
    public interface Debate extends AgenticScopeAccess { @Agent ResultWithAgenticScope<String> run(@MemoryId String id); }
    @Test(timeout=15000) public void humanDebaterResumesWithoutRepeatingRound() throws Exception {child("opinion",null);}
    @Test(timeout=15000) public void asyncHumanDebaterResumesWithoutRepeatingRound() throws Exception {child("opinion-async",null);}
    @Test(timeout=15000) public void humanJudgeResumesWithoutRepeatingDebaters() throws Exception {child("judge",null);}
    @Test(timeout=15000) public void asyncHumanJudgeResumesWithoutRepeatingDebaters() throws Exception {child("judge-async",null);}
    @Test(timeout=15000) public void secondRoundResumesTwoUndispatchedConsumers() throws Exception {child("multi",null);}
    @Test(timeout=30000) public void secondRoundResumesAcrossActualJava8Processes() throws Exception {restart("multi",7,2);}
    @Test(timeout=30000) public void humanJudgeResumesAcrossActualJava8Processes() throws Exception {restart("judge-async",3,1);}
    static void restart(String mode,int count,int asks) throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("target"),"debate-restart-");
        try {child(mode+"-write",dir);child(mode+"-read",dir);List<String> calls=Files.readAllLines(dir.resolve("calls.txt"),StandardCharsets.UTF_8);
            assertEquals(count,calls.size());assertEquals(asks,Collections.frequency(calls,"ask"));assertEquals(asks,Collections.frequency(calls,"a"));assertEquals(asks,Collections.frequency(calls,"b"));assertEquals(mode.equals("multi")?1:0,Collections.frequency(calls,"judge"));
        } finally {try(Stream<Path> files=Files.walk(dir)){for(Path f:files.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))Files.delete(f);}}
    }
    static void child(String mode,Path dir) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"debate-recovery-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DebateRecoveryJdk8Test.class.getName(),mode,dir==null?"":dir.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("child timed out: "+mode,p.waitFor(8,TimeUnit.SECONDS));assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue());}
        finally {if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}Files.deleteIfExists(log);}
    }
    public static void main(String[] args) {
        assertTrue(System.getProperty("java.version").startsWith("1.8."));
        String mode=args[0];boolean judgeMode=mode.startsWith("judge"),multi=mode.startsWith("multi");Path dir=args[1].isEmpty()?null:Paths.get(args[1]);
        AgenticScopePersister.setStore(dir==null?null:new VotingRecoveryJdk8Test.FileStore(dir));
        AtomicInteger asks=new AtomicInteger(),a=new AtomicInteger(),b=new AtomicInteger(),judges=new AtomicInteger(),errors=new AtomicInteger();
        Object human=AgenticServices.humanInTheLoopBuilder().async(mode.contains("async")).outputKey(judgeMode?"verdict":"human").responseProvider(scope->{
            int n=asks.incrementAndGet();mark(dir,"ask");if(multi&&n==1)return "round1";assertTrue("provider replayed",n<=(multi?2:1));return new SuspendedResponse<String>(judgeMode?"verdict":"opinion");
        }).build();
        Object left=multi?consumer("a",a,dir):AgenticServices.nonAiAgentBuilder(scope->{a.incrementAndGet();mark(dir,"a");return "ACCEPT";}).name("a").outputKey("a").build();
        Object right=multi?consumer("b",b,dir):AgenticServices.nonAiAgentBuilder(scope->{b.incrementAndGet();mark(dir,"b");return "ACCEPT";}).name("b").outputKey("b").build();
        Object judge=AgenticServices.nonAiAgentBuilder(scope->{
            judges.incrementAndGet();mark(dir,"judge");String context=scope.readState("debateContext","");
            if(multi){assertTrue(context,context.contains("a:answer2"));assertTrue(context,context.contains("b:answer2"));assertFalse(context,context.contains("round1"));}else assertTrue(context,context.contains("ACCEPT"));return "judged";
        }).name("judge").outputKey("answer").build();
        Object[] agents=judgeMode?new Object[]{left,right,human}:multi?new Object[]{human,left,right,judge}:new Object[]{human,left,judge};
        Debate debate=AgenticServices.plannerBuilder(Debate.class).subAgents(agents).beforeCall(scope->{if(multi)scope.writeStateIfAbsent("human","seed");})
                .planner(()->multi?new DebatePlanner(2,positions->false):new DebatePlanner()).errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
        if(mode.endsWith("read")) {
            AgenticScope restored=debate.getAgenticScope("session");assertNotNull(restored);if(multi)assertTrue(restored.readState("debateContext","").contains("round1"));
            assertTrue(restored.completePendingResponse(judgeMode?"verdict":"opinion",judgeMode?"approved":"answer2"));
            ResultWithAgenticScope<String> done=debate.run("session");assertFalse(done.suspended());assertEquals(judgeMode?"approved":"judged",done.result());
            assertEquals(0,asks.get());assertEquals(multi?1:0,a.get());assertEquals(multi?1:0,b.get());assertEquals(multi?1:0,judges.get());assertEquals(0,errors.get());return;
        }
        ResultWithAgenticScope<String> paused=debate.run("session");assertTrue(paused.suspended());assertEquals(0,judges.get());assertEquals(multi?2:1,asks.get());assertEquals(1,a.get());assertEquals(judgeMode||multi?1:0,b.get());
        assertTrue(debate.run("session").suspended());assertEquals(multi?2:1,asks.get());assertEquals(1,a.get());if(multi)assertTrue(paused.agenticScope().readState("debateContext","").contains("round1"));
        if(mode.endsWith("write"))return;
        ResultWithAgenticScope<String> done=paused.completePendingResponse(judgeMode?"verdict":"opinion",judgeMode?"approved":multi?"answer2":"ACCEPT");
        assertFalse(done.suspended());assertEquals(judgeMode?"approved":"judged",done.result());assertEquals(multi?2:1,a.get());assertEquals(multi?2:judgeMode?1:0,b.get());assertEquals(judgeMode?0:1,judges.get());assertEquals(multi?2:1,asks.get());assertEquals(0,errors.get());
    }
    static Object consumer(String name,AtomicInteger calls,Path dir){
        return AgenticServices.nonAiAgentBuilder(scope->{calls.incrementAndGet();mark(dir,name);return name+":"+scope.readState("human","");}).name(name).inputKey(String.class,"human").outputKey(name).activationCondition(scope->{
            boolean nextRound=!scope.readState("debateContext","").isEmpty();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline){Object value=scope.state().get("human");if(nextRound?(value instanceof SuspendedResponse<?>||"answer2".equals(value)):"round1".equals(value))return true;Thread.yield();}
            throw new IllegalStateException("human response was not published");
        }).build();
    }
    static void mark(Path dir,String value){if(dir==null)return;try{Files.write(dir.resolve("calls.txt"),(value+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){throw new RuntimeException(e);}}
}
