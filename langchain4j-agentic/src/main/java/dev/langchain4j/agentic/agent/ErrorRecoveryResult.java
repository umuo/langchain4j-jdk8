package dev.langchain4j.agentic.agent;

public final class ErrorRecoveryResult {

    public enum Type {

        THROW_EXCEPTION, RETURN_RESULT, RETRY
    }

    public static ErrorRecoveryResult throwException() {
        return new ErrorRecoveryResult(Type.THROW_EXCEPTION, null);
    }

    public static ErrorRecoveryResult retry() {
        return new ErrorRecoveryResult(Type.RETRY, null);
    }

    public static ErrorRecoveryResult result(Object result) {
        return new ErrorRecoveryResult(Type.RETURN_RESULT, result);
    }

    private final Type type;

    public Type type() {
        return type;
    }

    private final Object result;

    @com.fasterxml.jackson.annotation.JsonCreator
    public ErrorRecoveryResult(@com.fasterxml.jackson.annotation.JsonProperty("type") Type type, @com.fasterxml.jackson.annotation.JsonProperty("result") Object result) {
        this.type = type;
        this.result = result;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(type, ((ErrorRecoveryResult) obj).type) && java.util.Objects.equals(result, ((ErrorRecoveryResult) obj).result));
    }

    public final int hashCode() {
        return (31 * (31 * 0 + java.util.Objects.hashCode(type)) + java.util.Objects.hashCode(result));
    }

    public final String toString() {
        return "ErrorRecoveryResult[" + "type=" + type + ", result=" + result + "]";
    }

    public Object result() {
        return result;
    }
}
