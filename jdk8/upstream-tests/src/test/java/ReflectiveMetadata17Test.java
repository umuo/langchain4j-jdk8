import dev.langchain4j.internal.Jdk8;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The Java 8 artifact can still inspect modern consumer DTOs when run on Java 17. */
class ReflectiveMetadata17Test {
    record Person(String name, List<Integer> scores) {}
    @Test void recordMetadataRemainsAvailableOnJava17() {
        assertTrue(Jdk8.isRecord(Person.class));
        Jdk8.RecordComponent[] parts = Jdk8.getRecordComponents(Person.class);
        assertEquals(2, parts.length);
        assertEquals("name", parts[0].getName());
        assertEquals(String.class, parts[0].getType());
        assertEquals(Integer.class, ((ParameterizedType) parts[1].getGenericType()).getActualTypeArguments()[0]);
    }
    @Test void ordinaryClassesHaveNoRecordComponents() {
        assertFalse(Jdk8.isRecord(String.class));
        assertEquals(0, Jdk8.getRecordComponents(String.class).length);
    }
}
