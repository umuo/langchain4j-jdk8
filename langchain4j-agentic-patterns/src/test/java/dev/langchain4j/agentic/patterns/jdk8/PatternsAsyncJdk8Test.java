package dev.langchain4j.agentic.patterns.jdk8;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.patterns.debate.DebatePlanner;
import dev.langchain4j.agentic.patterns.voting.VotingPlanner;
import dev.langchain4j.agentic.patterns.voting.VotingStrategy;
import dev.langchain4j.agentic.scope.AgenticScopePersister;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PatternsAsyncJdk8Test {
    public interface StreamVote { @dev.langchain4j.agentic.Agent(outputKey="vote") @dev.langchain4j.service.UserMessage("Vote now") dev.langchain4j.service.TokenStream vote(); }
    public interface Router { @dev.langchain4j.agentic.Agent String route(@dev.langchain4j.service.V("request") String request); }
    @Test(timeout=15000) public void votingAwaitsIntermediateStream() throws Exception { child("stream-voting"); }
    @Test(timeout=15000) public void asyncFailureIsNotRecoveredAgainAsPlannerFailure() throws Exception { child("voting-error"); }
    @Test(timeout=15000) public void debateUsesRealPositionsAcrossAllRounds() throws Exception { child("debate-rounds"); }
    @Test(timeout=15000) public void routerReturnsActualAsyncOutput() throws Exception { child("router"); }
    @Test(timeout=15000) public void votingWaitsForConcurrentAsyncVotes() throws Exception { child("voting"); }
    @Test(timeout=15000) public void debateUsesActualAsyncPositionsAndJudgeResult() throws Exception { child("debate"); }
    static void child(String mode) throws Exception {
        Path log=Files.createTempFile(Paths.get("target"),"patterns-async-",".log");
        Process p=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xverify:all","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),PatternsAsyncJdk8Test.class.getName(),mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue("child timed out: "+mode,p.waitFor(8,TimeUnit.SECONDS)); assertEquals(new String(Files.readAllBytes(log),StandardCharsets.UTF_8),0,p.exitValue()); }
        finally { if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));} Files.deleteIfExists(log); }
    }
    public static void main(String[] args) throws Exception {
        assertTrue(System.getProperty("java.version").startsWith("1.8.")); AgenticScopePersister.setStore(null);
        if (args[0].equals("voting-error")) {
            AtomicInteger errors=new AtomicInteger();
            Object bad=AgenticServices.nonAiAgentBuilder(scope->{Thread.sleep(100);throw new IllegalStateException("vote failed");}).name("bad").async(true).outputKey("bad").build();
            Object good=AgenticServices.nonAiAgentBuilder(scope->2).name("good").async(true).outputKey("good").build();
            UntypedAgent system=AgenticServices.plannerBuilder().subAgents(bad,good).planner(()->new VotingPlanner(VotingStrategy.average()))
                    .errorHandler(error->{errors.incrementAndGet();return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();}).build();
            try {system.invoke(Collections.emptyMap());fail("must propagate failure");}
            catch(dev.langchain4j.agentic.agent.AgentInvocationException expected){Throwable cause=expected;while(cause.getCause()!=null)cause=cause.getCause();assertTrue(expected.toString(),cause instanceof IllegalStateException);assertEquals("vote failed",cause.getMessage());}
            assertEquals("one recovery decision for the failed computation",1,errors.get()); return;
        }
        if (args[0].equals("stream-voting")) {
            ExecutorService emitter=Executors.newSingleThreadExecutor();
            try {
                dev.langchain4j.model.chat.StreamingChatModel model=new dev.langchain4j.model.chat.StreamingChatModel(){
                    @Override public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler){
                        emitter.submit(()->{try {Thread.sleep(100);handler.onPartialResponse("ACC");handler.onPartialResponse("EPT");handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("ACCEPT")).build());}
                        catch(InterruptedException e){Thread.currentThread().interrupt();handler.onError(e);}});
                    }
                };
                Object stream=AgenticServices.agentBuilder(StreamVote.class).streamingChatModel(model).build();
                Object fixed=AgenticServices.nonAiAgentBuilder(scope->"ACCEPT").name("fixed").outputKey("fixed").build();
                UntypedAgent system=AgenticServices.plannerBuilder().subAgents(stream,fixed).planner(()->new VotingPlanner(votes->votes.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("|")))).build();
                assertEquals("ACCEPT|ACCEPT",system.invoke(Collections.emptyMap()));
            } finally {emitter.shutdownNow();assertTrue(emitter.awaitTermination(2,TimeUnit.SECONDS));} return;
        }
        if (args[0].equals("router")) {
            Object candidate=AgenticServices.nonAiAgentBuilder(scope->{Thread.sleep(100);return "routed";}).name("candidate").async(true).outputKey("answer").build();
            Router router=AgenticServices.plannerBuilder(Router.class).subAgents(candidate).planner(()->new dev.langchain4j.agentic.patterns.decisionrouter.DecisionRouterPlanner(request->{throw new AssertionError("single candidate must not call model");})).build();
            assertEquals("routed",router.route("request")); return;
        }
        CyclicBarrier started=new CyclicBarrier(2);
        AtomicInteger calls=new AtomicInteger(),judges=new AtomicInteger();
        Object left=AgenticServices.nonAiAgentBuilder(scope->{
            started.await(3,TimeUnit.SECONDS); Thread.sleep(100); calls.incrementAndGet();
            return args[0].equals("voting")?2:"left ACCEPT";
        }).name("left").async(true).outputKey("left").build();
        Object right=AgenticServices.nonAiAgentBuilder(scope->{
            started.await(3,TimeUnit.SECONDS); Thread.sleep(100); calls.incrementAndGet();
            return args[0].equals("voting")?4:"right ACCEPT";
        }).name("right").async(true).outputKey("right").build();
        {
            UntypedAgent system;
            if(args[0].equals("voting")) {
                system=AgenticServices.plannerBuilder().subAgents(left,right).planner(()->new VotingPlanner(VotingStrategy.average())).build();
                assertEquals(3.0,(Double)system.invoke(Collections.emptyMap()),0.0);
            } else {
                Object judge=AgenticServices.nonAiAgentBuilder(scope->{
                    String context=scope.readState("debateContext",""); assertTrue(context,context.contains("left ACCEPT")); assertTrue(context,context.contains("right ACCEPT"));
                    judges.incrementAndGet(); return "approved";
                }).name("judge").async(true).outputKey("answer").build();
                system=AgenticServices.plannerBuilder().subAgents(left,right,judge).planner(()->args[0].equals("debate-rounds")?new DebatePlanner():new DebatePlanner(dev.langchain4j.agentic.patterns.debate.ConvergenceStrategy.unanimousLastWord())).build();
                assertEquals("approved",system.invoke(Collections.emptyMap())); assertEquals(1,judges.get());
            }
            assertEquals(args[0].equals("debate-rounds")?6:2,calls.get());
        }
    }
}
