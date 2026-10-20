import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.nio.file.*;
import java.util.*;

/** Explicit JSON component metadata for the four new public record replacements. */
public final class Stage2RecordMetadata {
 public static void main(String[] args)throws Exception{
  Path path=Path.of(args[0]);var unit=StaticJavaParser.parse(path);
  Set<String> names=Set.of("ToolResultsOutcome","NextChatRequest","CompensableToolExecution","CombinedToolResults");
  for(var type:unit.findAll(ClassOrInterfaceDeclaration.class))if(names.contains(type.getNameAsString())){
   for(var constructor:type.getConstructors()){
    constructor.addMarkerAnnotation("com.fasterxml.jackson.annotation.JsonCreator");
    for(var parameter:constructor.getParameters())parameter.addSingleMemberAnnotation("com.fasterxml.jackson.annotation.JsonProperty",new StringLiteralExpr(parameter.getNameAsString()));
   }
   for(var field:type.getFields())if(!field.isStatic())field.addSingleMemberAnnotation("com.fasterxml.jackson.annotation.JsonProperty",new StringLiteralExpr(field.getVariable(0).getNameAsString()));
  }
  Files.writeString(path,unit.toString());
 }
}
