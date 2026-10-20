import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;
import javax.lang.model.type.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;

/** Resolve var and anonymous diamond using javac 17 before syntax lowering. */
public class InferTypes {
  record Edit(int start,int end,String text) {}
  public static void main(String[] args) throws Exception {
    JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fm=compiler.getStandardFileManager(null,null,null);
    List<Path> files=new ArrayList<>();
    for(int i=1;i<args.length;i++)try(var s=Files.walk(Path.of(args[i]))){s.filter(p->p.toString().endsWith(".java")).forEach(files::add);}
    DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
    JavacTask task=(JavacTask)compiler.getTask(null,fm,diagnostics,List.of("-encoding","UTF-8","-proc:none","--release","17","-classpath",Files.readString(Path.of(args[0])).trim()),null,fm.getJavaFileObjectsFromPaths(files));
    List<CompilationUnitTree> units=new ArrayList<>();task.parse().forEach(units::add);task.analyze();
    Trees trees=Trees.instance(task);SourcePositions pos=trees.getSourcePositions();
    for(var u:units){
      Path file=Path.of(u.getSourceFile().toUri());String source=Files.readString(file);List<Edit> edits=new ArrayList<>();
      Map<javax.lang.model.element.Element,String> bindings=new HashMap<>();
      new TreePathScanner<Void,Void>() {
        public Void visitInstanceOf(InstanceOfTree n,Void v) {
          if(n.getPattern() instanceof BindingPatternTree b) {
            var path=TreePath.getPath(u,b.getVariable());var element=trees.getElement(path);
            int es=(int)pos.getStartPosition(u,n.getExpression()),ee=(int)pos.getEndPosition(u,n.getExpression());
            String cast="(("+b.getVariable().getType()+") "+source.substring(es,ee)+")";
            bindings.put(element,cast);
            int bs=(int)pos.getStartPosition(u,b),be=(int)pos.getEndPosition(u,b);
            edits.add(new Edit(bs,be,b.getVariable().getType().toString()));
          }return super.visitInstanceOf(n,v);
        }
      }.scan(u,null);
      new TreePathScanner<Void,Void>(){
        public Void visitIdentifier(IdentifierTree n,Void v) {
          String cast=bindings.get(trees.getElement(getCurrentPath()));
          if(cast!=null) edits.add(new Edit((int)pos.getStartPosition(u,n),(int)pos.getEndPosition(u,n),cast));
          return super.visitIdentifier(n,v);
        }
        public Void visitVariable(VariableTree v,Void unused){
          int start=(int)pos.getStartPosition(u,v),end=(int)pos.getEndPosition(u,v);
          if(start>=0&&end>start){String declaration=source.substring(start,end);Matcher m=Pattern.compile("\\bvar\\s+"+Pattern.quote(v.getName().toString())+"\\b").matcher(declaration);
            if(m.find()&&m.start()<declaration.indexOf(v.getName().toString())+5){
              var el=trees.getElement(getCurrentPath());String type=el==null?"":el.asType().toString();
              if(!type.isEmpty()&&!type.contains("capture#")&&!type.contains("<anonymous")&&!type.contains("&")&&!type.equals("<any>")) edits.add(new Edit(start+m.start(),start+m.start()+3,type));
              else System.err.println("UNRESOLVED "+file+":"+v.getName()+" "+type);
            }
          }return super.visitVariable(v,unused);
        }
        public Void visitNewClass(NewClassTree n,Void unused){
          if(n.getClassBody()!=null){int s=(int)pos.getStartPosition(u,n.getIdentifier()),e=(int)pos.getEndPosition(u,n.getIdentifier());
            if(s>=0&&e>s&&source.substring(s,e).endsWith("<>")){
              TypeMirror t=trees.getTypeMirror(new TreePath(getCurrentPath(),n.getIdentifier()));
              if(t instanceof DeclaredType dt&&!dt.getTypeArguments().isEmpty()){StringJoiner j=new StringJoiner(", ","<",">");dt.getTypeArguments().forEach(a->j.add(a.toString()));edits.add(new Edit(e-2,e,j.toString()));}
            }
          }return super.visitNewClass(n,unused);
        }
      }.scan(u,null);
      edits.sort(Comparator.comparingInt(Edit::start).reversed());StringBuilder result=new StringBuilder(source);for(var e:edits) result.replace(e.start,e.end,e.text);if(!edits.isEmpty())Files.writeString(file,result.toString());
    }
    long errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).count();System.out.println("Attribution errors="+errors);diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).limit(25).forEach(System.err::println);
  }
}
