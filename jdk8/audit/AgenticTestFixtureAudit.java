import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Verify all baseline *Test classes are selected unchanged, and fixture adaptations are exact. */
public final class AgenticTestFixtureAudit {
    static final String BASELINE = "7fcb12d4cf1f23a8445850aedc7f3deb1cfc0a79";
    static Path root;

    static String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(command.toString());
        return output;
    }

    static String original(String path) throws Exception { return git("show", BASELINE + ":" + path); }

    static TypeDeclaration<?> fixture(ClassOrInterfaceDeclaration owner, String name) {
        return owner.getMembers().stream().filter(m -> m instanceof TypeDeclaration<?> t && t.getNameAsString().equals(name))
                .map(m -> (TypeDeclaration<?>) m).findFirst().orElseThrow();
    }

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        Set<String> expected = new TreeSet<>();
        git("ls-tree", "-r", "--name-only", BASELINE).lines().filter(p -> p.startsWith("langchain4j-agentic/src/test/java/") && p.endsWith("Test.java")).forEach(expected::add);
        if (expected.size() != 51) throw new AssertionError("Pinned test source count changed");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(root.resolve("jdk8/agentic-upstream-tests/pom.xml").toFile());
        Set<String> selected = new TreeSet<>();
        Set<String> originalSources = new TreeSet<>();
        var includes = document.getElementsByTagName("testInclude");
        for (int i = 0; i < includes.getLength(); i++) {
            String path = includes.item(i).getTextContent().trim();
            if (path.endsWith("Test.java")) selected.add(path);
            if (path.startsWith("langchain4j-") && path.endsWith(".java")) originalSources.add(path);
        }
        if (!expected.equals(selected)) throw new AssertionError("Test selection differs from baseline: " + selected);
        for (String path : originalSources)
            if (!original(path).equals(Files.readString(root.resolve(path)))) throw new AssertionError("Changed upstream assertions: " + path);
        String mock = "langchain4j-core/src/test/java/dev/langchain4j/model/chat/mock/StreamingChatModelMock.java";
        String adapted = original(mock).replace("java.util.concurrent.Flow.Publisher", "org.reactivestreams.Publisher")
                .replace("java.util.concurrent.Flow.Subscription", "org.reactivestreams.Subscription");
        if (!adapted.equals(Files.readString(root.resolve("jdk8/agentic-upstream-tests/src/test/java/dev/langchain4j/model/chat/mock/StreamingChatModelMock.java"))))
            throw new AssertionError("Unexpected streaming fixture changes");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var before = StaticJavaParser.parse(original("langchain4j-agentic/src/test/java/dev/langchain4j/agentic/SupervisorAgentIT.java")).getClassByName("SupervisorAgentIT").orElseThrow();
        var after = StaticJavaParser.parse(root.resolve("jdk8/agentic-upstream-tests/src/test/java/dev/langchain4j/agentic/SupervisorAgentIT.java")).getClassByName("SupervisorAgentIT").orElseThrow();
        if (after.getMembers().size() != 3) throw new AssertionError("Unexpected Invoice fixture members");
        for (String name : List.of("Document", "Invoice", "InvoiceRegistrationAgent"))
            if (!fixture(before, name).toString().equals(fixture(after, name).toString())) throw new AssertionError("Changed fixture: " + name);
        System.out.println("Agentic upstream fixture audit passed: all 51 baseline Test sources selected, 58 original test/helper sources unchanged; 2 Flow imports adapted; 3 nested Invoice fixture declarations preserved.");
    }
}
