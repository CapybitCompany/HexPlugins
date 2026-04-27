package hex.core.api.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    private final List<String> errors;

    private ValidationResult(List<String> errors) {
        this.errors = errors;
    }

    public static ValidationResult ok() {
        return new ValidationResult(List.of());
    }

    public static ValidationResult errors(List<String> errors) {
        return new ValidationResult(Collections.unmodifiableList(new ArrayList<>(errors)));
    }

    public boolean isOk() { return errors.isEmpty(); }
    public List<String> errors() { return errors; }
}
