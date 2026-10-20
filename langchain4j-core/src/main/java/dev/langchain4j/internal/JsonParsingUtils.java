package dev.langchain4j.internal;

import dev.langchain4j.Internal;

@Internal
public class JsonParsingUtils {

    @FunctionalInterface
    public interface ThrowingFunction<T, R> {

        R apply(T t) throws Exception;
    }

    public final static class ParsedJson<T> {

        @com.fasterxml.jackson.annotation.JsonProperty("value")
        private final T value;

        public T value() {
            return value;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("json")
        private final String json;

        public String json() {
            return json;
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public ParsedJson(@com.fasterxml.jackson.annotation.JsonProperty("value") T value, @com.fasterxml.jackson.annotation.JsonProperty("json") String json) {
            this.value = value;
            this.json = json;
        }

        public boolean equals(Object obj) {
            return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(value, ((ParsedJson<?>) obj).value) && java.util.Objects.equals(json, ((ParsedJson<?>) obj).json));
        }

        public int hashCode() {
            return (31 * (31 * 0 + java.util.Objects.hashCode(value)) + java.util.Objects.hashCode(json));
        }

        public String toString() {
            return "ParsedJson[" + "value=" + value + ", json=" + json + "]";
        }
    }

    public static <T> ParsedJson<T> extractAndParseJson(String text, Class<T> type) throws Exception {
        return extractAndParseJson(text, s -> Json.fromJson(s, type));
    }

    public static <T> ParsedJson<T> extractAndParseJson(String text, ThrowingFunction<String, T> parser) throws Exception {
        Exception parseException = null;
        try {
            return new ParsedJson<>(parser.apply(text), text);
        } catch (Exception ex) {
            // Temporarily ignore parsing errors and try to find a JSON block in the text
            parseException = ex;
        }
        int index = text.length();
        while (true) {
            int jsonEnd = findJsonEnd(text, index);
            if (jsonEnd < 0) {
                throw parseException;
            }
            int jsonStart = findJsonStart(text, jsonEnd, text.charAt(jsonEnd));
            if (jsonStart < 0) {
                throw parseException;
            }
            try {
                String tentativeJson = text.substring(jsonStart, jsonEnd + 1);
                return new ParsedJson<>(parser.apply(tentativeJson), tentativeJson);
            } catch (Exception ignored) {
                // If parsing fails, try to extract a JSON block from the text
            }
            // Move to the next character after the found JSON block
            index = jsonStart;
        }
    }

    private static int findJsonEnd(String text, int fromIndex) {
        int jsonMapEnd = text.lastIndexOf('}', fromIndex);
        int jsonListEnd = text.lastIndexOf(']', fromIndex);
        return Math.max(jsonMapEnd, jsonListEnd);
    }

    private static int findJsonStart(String text, int jsonEnd, char closingBrace) {
        char openingBrace = closingBrace == '}' ? '{' : '[';
        int braceCount = 0;
        for (int i = jsonEnd; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '"' && !isEscaped(text, i)) {
                i--;
                while (i >= 0) {
                    if (text.charAt(i) == '"' && !isEscaped(text, i)) {
                        break;
                    }
                    i--;
                }
                continue;
            }
            if (c == openingBrace) {
                braceCount++;
                if (braceCount == 0) {
                    return i == 0 || text.charAt(i - 1) != openingBrace ? i : -1;
                }
            } else if (c == closingBrace) {
                braceCount--;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int backslashCount = 0;
        int i = index - 1;
        while (i >= 0 && text.charAt(i) == '\\') {
            backslashCount++;
            i--;
        }
        return backslashCount % 2 != 0;
    }
}
