import com.github.javaparser.StaticJavaParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Expand handwritten compatibility helpers and acceptance sources into reviewable Java formatting. */
public final class Pretty {
    public static void main(String[] files) throws Exception {
        for (String file : files) {
            Path path = Path.of(file);
            Files.writeString(path, StaticJavaParser.parse(path).toString());
        }
    }
}
