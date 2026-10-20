import com.github.javaparser.*;import com.github.javaparser.ast.*;import com.github.javaparser.ast.body.*;import com.github.javaparser.ast.expr.*;import java.nio.file.*;import java.util.*;
/** Add the explicit JSON metadata that Jackson otherwise inferred from record components. */
public class RecordJson {
 public static void main(String[] files)throws Exception{
  Set<String> names=Set.of("ParsedJson","MethodSignature","Route","RouteOption","Success","Failure","DefaultRawStreamingEvent","EmbeddingParameter","EmbeddedBatch","ChatCompletionOptions");
  for(String file:files){Path p=Path.of(file);var cu=StaticJavaParser.parse(p);
   for(var c:cu.findAll(ClassOrInterfaceDeclaration.class))if(names.contains(c.getNameAsString())){
    for(var ctor:c.getConstructors()){
     ctor.addMarkerAnnotation("com.fasterxml.jackson.annotation.JsonCreator");
     for(var parameter:ctor.getParameters())parameter.addSingleMemberAnnotation("com.fasterxml.jackson.annotation.JsonProperty",new StringLiteralExpr(parameter.getNameAsString()));
    }
    for(var f:c.getFields())if(!f.isStatic())f.addSingleMemberAnnotation("com.fasterxml.jackson.annotation.JsonProperty",new StringLiteralExpr(f.getVariable(0).getNameAsString()));
   }
   Files.writeString(p,cu.toString());
  }
 }
}
