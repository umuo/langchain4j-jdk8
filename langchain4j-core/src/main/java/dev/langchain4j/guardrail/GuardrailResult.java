package dev.langchain4j.guardrail;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The result of the validation of an interaction between a user and the LLM.
 *
 * @param <GR>
 *            The type of guardrail result to expect
 *
 * @see InputGuardrailResult
 * @see OutputGuardrailResult
 */
public interface GuardrailResult<GR extends GuardrailResult<GR>> {

    /**
     * The possible results of a guardrails validation.
     */
    enum Result {

        /**
         * A successful validation.
         */
        SUCCESS,
        /**
         * A successful validation with a specific result.
         */
        SUCCESS_WITH_RESULT,
        /**
         * A failed validation not preventing the subsequent validations eventually registered to be evaluated.
         */
        FAILURE,
        /**
         * A fatal failed validation, blocking the evaluation of any other validations eventually registered.
         */
        FATAL
    }

    /**
     * The message and the cause of the failure of a single validation.
     */
    interface Failure {

        /**
         * Build a failure from a specific {@link Guardrail} class
         */
        Failure withGuardrailClass(Class<? extends Guardrail> guardrailClass);

        /**
         * The failure message
         */
        String message();

        /**
         * The cause of the failure
         */
        Throwable cause();

        /**
         * The {@link Guardrail} class
         */
        Class<? extends Guardrail> guardrailClass();

        /**
         * The string representation of the failure
         * @return A string representation of the failure
         */
        default String asString() {
            java.lang.String guardrailName = Optional.ofNullable(guardrailClass()).map(Class::getName).orElse("");
            return String.format("The guardrail %s failed with this message: %s", guardrailName, message());
        }
    }

    /**
     * The result of the guardrail
     */
    Result result();

    /**
     * @return The list of failures eventually resulting from a set of validations.
     */
    <F extends Failure> List<F> failures();

    /**
     * The message of the successful result
     */
    String successfulText();

    /**
     * Whether or not the result is successful, but the result was re-written, potentially due to re-prompting
     */
    default boolean hasRewrittenResult() {
        return result() == Result.SUCCESS_WITH_RESULT;
    }

    /**
     * Whether or not the result is considered fatal
     */
    default boolean isFatal() {
        return result() == Result.FATAL;
    }

    /**
     * Whether or not the result is considered successful
     */
    default boolean isSuccess() {
        dev.langchain4j.guardrail.GuardrailResult.Result result = result();
        return (result == Result.SUCCESS) || (result == Result.SUCCESS_WITH_RESULT);
    }

    /**
     * Gets the exception from the first failure
     */
    default Throwable getFirstFailureException() {
        return !isSuccess() ? failures().stream().map(Failure::cause).filter(Objects::nonNull).findFirst().orElse(null) : null;
    }

    /**
     * The {@link Guardrail} class which performed this validation
     */
    default GR validatedBy(Class<? extends Guardrail> guardrailClass) {
        ensureNotNull(guardrailClass, "guardrailClass");
        if (!isSuccess()) {
            java.util.List<dev.langchain4j.guardrail.GuardrailResult.Failure> failures = failures();
            if (failures.size() != 1) {
                throw new IllegalArgumentException();
            }
            failures.set(0, failures.get(0).withGuardrailClass(guardrailClass));
        }
        return (GR) this;
    }

    default String asString() {
        if (isSuccess()) {
            return hasRewrittenResult() ? String.format("Success with '%s'", successfulText()) : "Success";
        }
        return failures().stream().map(Failure::toString).collect(Collectors.joining(", "));
    }
}
