import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Read-only, fixed-baseline declaration inventory; does not rewrite sources or assert support. */
class AgenticApiInventory {
    static final List<String> MODULES=List.of("langchain4j-agentic","langchain4j-agentic-a2a","langchain4j-agentic-mcp","langchain4j-agentic-patterns");
    static final Set<String> API_CANDIDATES=Set.of("of","copyOf","toList","getFirst","getLast","removeFirst","removeLast","reversed","isBlank","strip","stripLeading","stripTrailing","formatted","repeat","readAllBytes","readString","writeString","orTimeout","completeOnTimeout","failedFuture","exceptionallyCompose","compareAndExchange","privateLookupIn","startVirtualThread","ofVirtual","newVirtualThreadPerTaskExecutor");
    static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    static boolean publicType(TypeDeclaration<?> type){
        Node parent=type.getParentNode().orElse(null);
        boolean visible=type.isPublic()||type.isProtected()||(parent instanceof ClassOrInterfaceDeclaration c&&c.isInterface());
        if(!visible)return false;
        Optional<TypeDeclaration> outer=type.findAncestor(TypeDeclaration.class);
        return outer.isEmpty()||publicType(outer.get());
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);JavaParser parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        List<Object> modules=new ArrayList<>();int filesTotal=0,typesTotal=0,membersTotal=0;
        for(String module:MODULES){
            List<Object> files=new ArrayList<>();
            List<Path> sources;try(Stream<Path> stream=Files.walk(root.resolve(module).resolve("src/main/java"))){sources=stream.filter(p->p.toString().endsWith(".java")).sorted().toList();}
            for(Path source:sources){
                ParseResult<CompilationUnit> parsed=parser.parse(source);
                if(!parsed.isSuccessful()||parsed.getResult().isEmpty())throw new IllegalStateException(source+": "+parsed.getProblems());
                CompilationUnit unit=parsed.getResult().get();List<Object> types=new ArrayList<>(),findings=new ArrayList<>();
                for(TypeDeclaration<?> type:unit.findAll(TypeDeclaration.class)){
                    boolean exported=publicType(type);List<Object> members=new ArrayList<>();
                    boolean iface=type instanceof ClassOrInterfaceDeclaration c&&c.isInterface();
                    for(BodyDeclaration<?> member:type.getMembers()){
                        if(member instanceof MethodDeclaration method){
                            boolean visible=method.isPublic()||method.isProtected()||(iface&&!method.isPrivate());
                            if(visible)members.add(map("kind","method","signature",method.getDeclarationAsString(true,true,true),"type_parameters",method.getTypeParameters().toString(),"line",method.getBegin().map(p->p.line).orElse(0),"exported_owner",exported));
                        }else if(member instanceof ConstructorDeclaration constructor&&(constructor.isPublic()||constructor.isProtected())){
                            members.add(map("kind","constructor","signature",constructor.getDeclarationAsString(true,true,true),"line",constructor.getBegin().map(p->p.line).orElse(0),"exported_owner",exported));
                        }else if(member instanceof AnnotationMemberDeclaration annotation){
                            members.add(map("kind","annotation-member","signature",annotation.getType()+" "+annotation.getNameAsString()+"()","line",annotation.getBegin().map(p->p.line).orElse(0),"exported_owner",exported));
                        }else if(member instanceof FieldDeclaration field&&(field.isPublic()||field.isProtected()||iface)){
                            for(VariableDeclarator variable:field.getVariables())members.add(map("kind","field","signature",variable.getType()+" "+variable.getNameAsString(),"exported_owner",exported));
                        }
                    }
                    types.add(map("name",type.getFullyQualifiedName().orElse(type.getNameAsString()),"kind",type.getClass().getSimpleName(),"exported",exported,"line",type.getBegin().map(p->p.line).orElse(0),"annotations",type.getAnnotations().stream().map(a->a.getNameAsString()).toList(),"record_components",type instanceof RecordDeclaration r?r.getParameters().toString():null,"members",members));
                    typesTotal++;membersTotal+=members.size();
                }
                for(Node node:unit.findAll(Node.class)){
                    String issue=null;
                    if(node instanceof RecordDeclaration)issue="record";
                    else if(node instanceof SwitchExpr)issue="switch-expression";
                    else if(node instanceof SwitchEntry e&&e.getType()!=SwitchEntry.Type.STATEMENT_GROUP)issue="switch-rule";
                    else if(node instanceof InstanceOfExpr e&&e.getPattern().isPresent())issue="instanceof-pattern";
                    else if(node instanceof TextBlockLiteralExpr)issue="text-block";
                    else if(node instanceof com.github.javaparser.ast.type.VarType)issue="var";
                    else if(node instanceof Modifier m&&(m.getKeyword()==Modifier.Keyword.SEALED||m.getKeyword()==Modifier.Keyword.NON_SEALED))issue="sealed-modifier";
                    else if(node instanceof MethodCallExpr call&&API_CANDIDATES.contains(call.getNameAsString()))issue="api-candidate:"+call.getNameAsString();
                    if(issue!=null)findings.add(map("kind",issue,"line",node.getBegin().map(p->p.line).orElse(0)));
                }
                files.add(map("path",root.relativize(source).toString(),"package",unit.getPackageDeclaration().map(p->p.getNameAsString()).orElse(""),"imports",unit.getImports().stream().map(i->i.getNameAsString()).toList(),"types",types,"java8_findings",findings,"implementation_status","pending"));filesTotal++;
            }
            modules.add(map("module",module,"source_files",sources.size(),"files",files,"functional_acceptance","pending","transitive_dependency_audit","pending"));
        }
        Map<String,Object> result=map("baseline","7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79","scope","All four baseline Agentic production source trees","limitations",List.of("Declared members only; inherited/bridge APIs require compiled comparison later","API-name findings are candidates; no symbol resolution or compatibility claim","All implementation and functional acceptance states remain pending"),"source_files",filesTotal,"declared_types",typesTotal,"public_or_protected_members_including_internal_owners",membersTotal,"modules",modules);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),result);
        System.out.println("Agentic declaration inventory: "+filesTotal+" source files, "+typesTotal+" types, "+membersTotal+" candidate API members; no implementation accepted");
    }
}
