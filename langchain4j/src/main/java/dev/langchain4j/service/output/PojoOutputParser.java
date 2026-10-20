package dev.langchain4j.service.output;

import static dev.langchain4j.internal.JsonParsingUtils.extractAndParseJson;
import static dev.langchain4j.internal.JsonSchemaElementUtils.jsonObjectOrReferenceSchemaFrom;
import static dev.langchain4j.internal.JsonSchemaElementUtils.polymorphicSchemaFrom;
import static dev.langchain4j.internal.JsonSchemaElementUtils.referenceIfRecursive;
import static dev.langchain4j.internal.JsonSchemaElementUtils.wrapPolymorphic;
import static dev.langchain4j.internal.PolymorphicTypes.isPolymorphic;
import static dev.langchain4j.internal.Utils.isNullOrBlank;
import static dev.langchain4j.service.IllegalConfigurationException.illegalConfiguration;
import static dev.langchain4j.service.output.ParsingUtils.outputParsingException;
import static java.lang.String.format;
import static java.lang.reflect.Modifier.isAbstract;
import dev.langchain4j.Internal;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.internal.Json;
import dev.langchain4j.internal.JsonSchemaElementUtils.VisitedClassMetadata;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.output.structured.Description;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Internal
class PojoOutputParser<T> implements OutputParser<T> {

    private final Class<T> type;

    PojoOutputParser(Class<T> type) {
        this.type = type;
    }

    @Override
    public T parse(String text) {
        if (isNullOrBlank(text)) {
            throw outputParsingException(text, type);
        }
        try {
            if (isPolymorphic(type)) {
                return extractAndParseJson(text, json -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = Json.fromJson(json, Map.class);
                    if (map != null && map.size() == 1 && map.containsKey("value")) {
                        return Json.fromJson(Json.toJson(map.get("value")), type);
                    } else {
                        return Json.fromJson(json, type);
                    }
                }).value();
            } else {
                return extractAndParseJson(text, type).value();
            }
        } catch (Exception e) {
            throw outputParsingException(text, type.getTypeName(), e);
        }
    }

    @Override
    public Optional<JsonSchema> jsonSchema() {
        JsonSchemaElement root;
        if (isPolymorphic(type)) {
            Map<Class<?>, VisitedClassMetadata> visited = new LinkedHashMap<>();
            JsonSchemaElement anyOf = polymorphicSchemaFrom(type, null, false, visited);
            root = wrapPolymorphic("value", referenceIfRecursive(anyOf, type, visited), visited);
        } else {
            root = jsonObjectOrReferenceSchemaFrom(type, null, false, new LinkedHashMap<>(), true);
        }
        if (root instanceof JsonObjectSchema && ((JsonObjectSchema) root).properties().isEmpty() && isAbstract(type.getModifiers())) {
            throw new UnsupportedFeatureException(String.format("Type %s is an interface or abstract class with no permitted subtypes discoverable " + "by langchain4j, which produced an empty JSON schema. To use it as a " + "polymorphic return type, either make it sealed or annotate it with @JsonSubTypes.", type.getName()));
        }
        return Optional.of(JsonSchema.builder().name(type.getSimpleName()).rootElement(root).build());
    }

    @Override
    public String formatInstructions() {
        String jsonStructure = jsonStructure(type, new HashSet<>());
        validateJsonStructure(jsonStructure, type);
        return "\nYou must answer strictly in the following JSON format: " + jsonStructure;
    }

    private static String jsonStructure(Class<?> type, Set<Class<?>> visited) {
        StringBuilder jsonSchema = new StringBuilder();
        jsonSchema.append("{\n");
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName();
            if (name.equals("__$hits$__") || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                // Skip coverage instrumentation field.
                continue;
            }
            jsonSchema.append(format("\"%s\": (%s),\n", name, descriptionFor(field, visited)));
        }
        int trailingCommaIndex = jsonSchema.lastIndexOf(",");
        if (trailingCommaIndex > 0) {
            jsonSchema.delete(trailingCommaIndex, trailingCommaIndex + 1);
        }
        jsonSchema.append("}");
        return jsonSchema.toString();
    }

    private static String descriptionFor(Field field, Set<Class<?>> visited) {
        Description fieldDescription = field.getAnnotation(Description.class);
        if (fieldDescription == null) {
            return "type: " + typeOf(field, visited);
        }
        return String.join(" ", fieldDescription.value()) + "; type: " + typeOf(field, visited);
    }

    private static String typeOf(Field field, Set<Class<?>> visited) {
        return typeOf(field.getGenericType(), visited);
    }

    private static String typeOf(Type type, Set<Class<?>> visited) {
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?> && (((Class<?>) rawType) == List.class || ((Class<?>) rawType) == Set.class)) {
                return format("array of %s", typeOf(((ParameterizedType) type).getActualTypeArguments()[0], visited));
            }
        } else if (type instanceof Class<?>) {
            if (((Class<?>) type).isArray()) {
                return format("array of %s", typeOf(((Class<?>) type).getComponentType(), visited));
            } else if (((Class<?>) type).isEnum()) {
                return "enum, must be one of " + Arrays.stream(((Class<?>) type).getEnumConstants()).map(e -> ((Enum<?>) e).name()).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
            }
            return simpleNameOrJsonStructure(((Class<?>) type), visited);
        }
        return simpleTypeName(type);
    }

    private static String simpleNameOrJsonStructure(Class<?> structured, Set<Class<?>> visited) {
        String simpleTypeName = simpleTypeName(structured);
        if (structured.getPackage() == null || structured.getPackage().getName().startsWith("java.") || visited.contains(structured)) {
            return simpleTypeName;
        } else {
            visited.add(structured);
            return simpleTypeName + ": " + jsonStructure(structured, visited);
        }
    }

    private static String simpleTypeName(Type type) {
        switch(type.getTypeName()) {
            case "java.lang.String":
                return "string";
            case "java.lang.Integer":
            case "int":
                return "integer";
            case "java.lang.Boolean":
            case "boolean":
                return "boolean";
            case "java.lang.Float":
            case "float":
                return "float";
            case "java.lang.Double":
            case "double":
                return "double";
            case "java.util.Date":
            case "java.time.LocalDate":
                return "date string (2023-12-31)";
            case "java.time.LocalTime":
                return "time string (23:59:59)";
            case "java.time.LocalDateTime":
                return "date-time string (2023-12-31T23:59:59)";
            default:
                return type.getTypeName();
        }
    }

    private void validateJsonStructure(String jsonStructure, Type returnType) {
        if (jsonStructure.replaceAll("\\s", "").equals("{}")) {
            if (returnType.toString().contains("reactor.core.publisher.Flux")) {
                throw illegalConfiguration("Please import langchain4j-reactor module " + "if you wish to use Flux<String> as a method return type");
            }
            throw illegalConfiguration("Illegal method return type: " + returnType);
        }
    }
}
