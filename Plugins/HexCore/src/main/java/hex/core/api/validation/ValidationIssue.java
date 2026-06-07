package hex.core.api.validation;

import java.util.Objects;

public record ValidationIssue(ValidationSeverity severity, String path, String message) {
    public ValidationIssue {
        severity = Objects.requireNonNull(severity, "severity");
        path = path == null ? "" : path;
        message = Objects.requireNonNull(message, "message");
    }

    public static ValidationIssue error(String path, String message) {
        return new ValidationIssue(ValidationSeverity.ERROR, path, message);
    }

    public static ValidationIssue warning(String path, String message) {
        return new ValidationIssue(ValidationSeverity.WARNING, path, message);
    }

    public static ValidationIssue info(String path, String message) {
        return new ValidationIssue(ValidationSeverity.INFO, path, message);
    }
}

