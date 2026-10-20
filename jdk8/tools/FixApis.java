import com.github.javaparser.*;import com.github.javaparser.ast.*;import com.github.javaparser.ast.expr.*;import java.nio.file.*;import java.util.*;
public class FixApis {
 public static void main(String[] args)throws Exception {
  for(String dir:args)try(var files=Files.walk(Path.of(dir))){for(Path p:files.filter(f->f.toString().endsWith(".java")).toList()){
   var cu=StaticJavaParser.parse(p);boolean changed=false;
   for(var m:new ArrayList<>(cu.findAll(MethodCallExpr.class))) {
    String n=m.getNameAsString(),scope=m.getScope().map(Object::toString).orElse("");
    if(n.equals("formatted")&&m.getScope().isPresent()){m.getArguments().addFirst(m.getScope().get().clone());m.setScope(new NameExpr("String"));m.setName("format");changed=true;}
    if(n.equals("arrayType")&&m.getScope().isPresent()){m.replace(StaticJavaParser.parseExpression("java.lang.reflect.Array.newInstance("+m.getScope().get()+", 0).getClass()"));changed=true;}
    if(n.equals("delayedExecutor")&&scope.equals("CompletableFuture")){m.setScope(new NameExpr("dev.langchain4j.internal.Jdk8"));changed=true;}
    if(n.equals("exceptionallyCompose")&&m.getScope().isPresent()){m.getArguments().addFirst(m.getScope().get().clone());m.setScope(new NameExpr("dev.langchain4j.internal.Jdk8"));changed=true;}
    if(n.equals("findFirst")&&scope.startsWith("ServiceLoader.load(")){m.getArguments().add(m.getScope().get().clone());m.setScope(new NameExpr("dev.langchain4j.internal.Jdk8"));m.setName("firstService");changed=true;}
   }
   if(changed)Files.writeString(p,cu.toString());
  }}
 }
}
