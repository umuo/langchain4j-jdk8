import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import java.nio.file.*;
import java.util.*;

/** One-time AST lowering; applied after javac type inference. */
public class LowerSyntax {
 static final String C="dev.langchain4j.internal.Jdk8";
 public static void main(String[] args)throws Exception{
  StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  for(String dir:args)try(var files=Files.walk(Path.of(dir))){for(Path p:files.filter(f->f.toString().endsWith(".java")).toList()){
   CompilationUnit cu=StaticJavaParser.parse(p);
   // Records: preserve canonical constructor, component accessors and value semantics.
   for(RecordDeclaration r:new ArrayList<>(cu.findAll(RecordDeclaration.class))){
    ClassOrInterfaceDeclaration c=new ClassOrInterfaceDeclaration();c.setName(r.getNameAsString());c.setModifiers(r.getModifiers());c.setFinal(true);c.setTypeParameters(r.getTypeParameters());c.setImplementedTypes(r.getImplementedTypes());c.setAnnotations(r.getAnnotations());r.getComment().ifPresent(c::setComment);
    if(r.getParentNode().orElse(null) instanceof TypeDeclaration)c.setStatic(true);
    if(r.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration outer&&outer.isInterface())c.setPublic(true);
    ConstructorDeclaration ctor=new ConstructorDeclaration();ctor.setName(r.getNameAsString());ctor.setPublic(true);BlockStmt body=new BlockStmt();
    for(BodyDeclaration<?> m:new ArrayList<>(r.getMembers())) {
      if(m instanceof CompactConstructorDeclaration cc)body=cc.getBody().clone();else c.addMember(m.clone());
    }
    for(Parameter a:r.getParameters()){
      String name=a.getNameAsString();c.addField(a.getType().clone(),name,Modifier.Keyword.PRIVATE,Modifier.Keyword.FINAL);ctor.addParameter(a.clone());
      body.addStatement("this."+name+" = "+name+";");
      if(c.getMethodsByName(name).isEmpty())c.addMethod(name,Modifier.Keyword.PUBLIC).setType(a.getType().clone()).setBody(StaticJavaParser.parseBlock("{return "+name+";}"));
    }
    ctor.setBody(body);c.addMember(ctor);
    String name=r.getNameAsString(),generic=r.getTypeParameters().isEmpty()?name:name+"<"+String.join(", ",Collections.nCopies(r.getTypeParameters().size(),"?"))+">";
    if(c.getMethodsByName("equals").isEmpty()){
      String eq="obj == this || (obj != null && obj.getClass() == getClass()";
      for(Parameter a:r.getParameters())eq+=" && java.util.Objects.equals("+a.getNameAsString()+", (("+generic+")obj)."+a.getNameAsString()+")";
      eq+=")";c.addMember(StaticJavaParser.parseBodyDeclaration("public boolean equals(Object obj) {return "+eq+";}"));
    }
    if(c.getMethodsByName("hashCode").isEmpty()) {
      String hash="0";for(Parameter a:r.getParameters())hash="(31 * "+hash+" + java.util.Objects.hashCode("+a.getNameAsString()+"))";
      c.addMember(StaticJavaParser.parseBodyDeclaration("public int hashCode() {return "+hash+";}"));
    }
    if(c.getMethodsByName("toString").isEmpty()){
      String value="\""+name+"[\"";for(int i=0;i<r.getParameters().size();i++){Parameter a=r.getParameter(i);value+=" + \""+(i==0?"":", ")+a.getNameAsString()+"=\" + "+a.getNameAsString();}value+=" + \"]\"";
      c.addMember(StaticJavaParser.parseBodyDeclaration("public String toString(){return "+value+";}"));
    }
    r.replace(c);
   }
   cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c->{c.getModifiers().removeIf(m->m.getKeyword()==Modifier.Keyword.SEALED||m.getKeyword()==Modifier.Keyword.NON_SEALED);c.getPermittedTypes().clear();});
   for(TextBlockLiteralExpr t:new ArrayList<>(cu.findAll(TextBlockLiteralExpr.class))) t.replace(new StringLiteralExpr().setString(t.asString()));
   for(AnnotationExpr a:cu.findAll(AnnotationExpr.class))if(a.getNameAsString().equals("Deprecated")&&a instanceof NormalAnnotationExpr)a.replace(new MarkerAnnotationExpr("Deprecated"));
   // Process deepest switch first. Return switches need no lambda or capture.
   List<SwitchExpr> switches=new ArrayList<>(cu.findAll(SwitchExpr.class));Collections.reverse(switches);
   for(SwitchExpr sw:switches){
     SwitchStmt stmt=convert(sw.getSelector(),sw.getEntries(),true);
     if(sw.getParentNode().orElse(null) instanceof ReturnStmt ret)ret.replace(stmt);
     else {
       // All other expression contexts use an immediately invoked Supplier; compiler verification checks captures.
       String type="Object";Node parent=sw.getParentNode().orElse(null);
       if(parent instanceof VariableDeclarator vd)type=vd.getType().toString();
       if(type.equals("int"))type="Integer";if(type.equals("boolean"))type="Boolean";if(type.equals("long"))type="Long";
       sw.replace(StaticJavaParser.parseExpression("((java.util.function.Supplier<"+type+">) () -> {"+stmt+"}).get()"));
     }
   }
   for(SwitchStmt sw:new ArrayList<>(cu.findAll(SwitchStmt.class)))if(sw.getEntries().stream().anyMatch(e->e.getType()!=SwitchEntry.Type.STATEMENT_GROUP))sw.replace(convert(sw.getSelector(),sw.getEntries(),false));
   for(MethodCallExpr m:new ArrayList<>(cu.findAll(MethodCallExpr.class))){
    String n=m.getNameAsString(),scope=m.getScope().map(Object::toString).orElse("");
    String target=null;
    if((scope.equals("List")||scope.equals("java.util.List"))&&n.equals("of"))target="list";
    if((scope.equals("Set")||scope.equals("java.util.Set"))&&n.equals("of"))target="set";
    if((scope.equals("Map")||scope.equals("java.util.Map"))&&n.equals("of"))target="map";
    if(scope.equals("List")&&n.equals("copyOf"))target="copyList";if(scope.equals("Set")&&n.equals("copyOf"))target="copySet";if(scope.equals("Map")&&n.equals("copyOf"))target="copyMap";
    if(scope.equals("CompletableFuture")&&n.equals("failedFuture"))target="failedFuture";
    if(target!=null){m.setScope(new NameExpr(C));m.setName(target);}
    else if(n.equals("toList")&&m.getArguments().isEmpty()) {m.setName("collect");m.addArgument(StaticJavaParser.parseExpression("java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList)"));}
    else if(n.equals("orElseThrow")&&m.getArguments().isEmpty())m.addArgument(StaticJavaParser.parseExpression("java.util.NoSuchElementException::new"));
    else if(Set.of("isBlank","strip","repeat","isRecord","isSealed","getPermittedSubclasses","getRecordComponents","readAllBytes").contains(n)&&m.getScope().isPresent()){
      NodeList<Expression> a=new NodeList<>();a.add(m.getScope().get().clone());m.getArguments().forEach(x->a.add(x.clone()));m.setScope(new NameExpr(C));m.setArguments(a);
    }
   }
   // Interface private helpers have no Java 8 equivalent; expose instance helpers as defaults.
   for(ClassOrInterfaceDeclaration c:cu.findAll(ClassOrInterfaceDeclaration.class))if(c.isInterface())for(MethodDeclaration m:c.getMethods())if(m.isPrivate()){m.setPrivate(false);if(!m.isStatic())m.setDefault(true);}
   Files.writeString(p,cu.toString());
  }}
 }
 static SwitchStmt convert(Expression selector,NodeList<SwitchEntry> entries,boolean returning){
   NodeList<SwitchEntry> result=new NodeList<>();
   for(SwitchEntry e:entries){
    NodeList<Statement> statements=new NodeList<>();
    for(Statement s:e.getStatements()){
      if(returning&&s instanceof ExpressionStmt x)statements.add(new ReturnStmt(x.getExpression().clone()));else statements.add(s.clone());
    }
    if(returning) for(Statement s:statements)for(YieldStmt y:new ArrayList<>(s.findAll(YieldStmt.class)))y.replace(new ReturnStmt(y.getExpression().clone()));
    if(!returning&&e.getType()!=SwitchEntry.Type.STATEMENT_GROUP&&!(statements.getLast().orElse(new EmptyStmt()) instanceof ThrowStmt))statements.add(new BreakStmt());
    if(e.getLabels().isEmpty()){SwitchEntry x=new SwitchEntry();x.setStatements(statements);result.add(x);}
    else for(int i=0;i<e.getLabels().size();i++){SwitchEntry x=new SwitchEntry();x.setLabels(new NodeList<>(e.getLabels().get(i).clone()));if(i==e.getLabels().size()-1)x.setStatements(statements);result.add(x);}
   }
   SwitchStmt stmt=new SwitchStmt(selector.clone(),result);
   if(returning&&entries.stream().noneMatch(e->e.getLabels().isEmpty())){SwitchEntry d=new SwitchEntry();d.addStatement("throw new IllegalStateException(\"Unexpected enum value\");");stmt.getEntries().add(d);}
   return stmt;
 }
}
