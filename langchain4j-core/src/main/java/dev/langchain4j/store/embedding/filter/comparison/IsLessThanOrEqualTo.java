package dev.langchain4j.store.embedding.filter.comparison;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.filter.Filter;
import java.util.Objects;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static dev.langchain4j.store.embedding.filter.comparison.NumberComparator.isLessThanOrEqualTo;
import static dev.langchain4j.store.embedding.filter.comparison.TypeChecker.ensureTypesAreCompatible;

public class IsLessThanOrEqualTo implements Filter {

    private final String key;

    private final Comparable<?> comparisonValue;

    public IsLessThanOrEqualTo(String key, Comparable<?> comparisonValue) {
        this.key = ensureNotBlank(key, "key");
        this.comparisonValue = ensureNotNull(comparisonValue, "comparisonValue with key '" + key + "'");
    }

    public String key() {
        return key;
    }

    public Comparable<?> comparisonValue() {
        return comparisonValue;
    }

    @Override
    public boolean test(Object object) {
        if (!(object instanceof Metadata)) {
            return false;
        }
        if (!((Metadata) object).containsKey(key)) {
            return false;
        }
        Object actualValue = ((Metadata) object).toMap().get(key);
        ensureTypesAreCompatible(actualValue, comparisonValue, key);
        if (actualValue instanceof Number) {
            return isLessThanOrEqualTo(actualValue, comparisonValue);
        }
        return ((Comparable) actualValue).compareTo(comparisonValue) <= 0;
    }

    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (!(o instanceof IsLessThanOrEqualTo))
            return false;
        return Objects.equals(this.key, ((IsLessThanOrEqualTo) o).key) && Objects.equals(this.comparisonValue, ((IsLessThanOrEqualTo) o).comparisonValue);
    }

    public int hashCode() {
        return Objects.hash(key, comparisonValue);
    }

    public String toString() {
        return "IsLessThanOrEqualTo(key=" + this.key + ", comparisonValue=" + this.comparisonValue + ")";
    }
}
