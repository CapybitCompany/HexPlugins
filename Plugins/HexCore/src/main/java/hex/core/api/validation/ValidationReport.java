package hex.core.api.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationReport {
    private static final ValidationReport OK = new ValidationReport(List.of());

    private final List<ValidationIssue> issues;

    private ValidationReport(List<ValidationIssue> issues) {
        this.issues = Collections.unmodifiableList(issues);
    }

    public static ValidationReport ok() {
        return OK;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ValidationIssue> issues() {
        return issues;
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.ERROR);
    }

    public boolean hasWarnings() {
        return issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.WARNING);
    }

    public boolean success() {
        return !hasErrors();
    }

    public String summary() {
        long errors = issues.stream().filter(issue -> issue.severity() == ValidationSeverity.ERROR).count();
        long warnings = issues.stream().filter(issue -> issue.severity() == ValidationSeverity.WARNING).count();
        return "ValidationReport{errors=" + errors + ", warnings=" + warnings + ", issues=" + issues.size() + "}";
    }

    public static final class Builder {
        private final List<ValidationIssue> issues = new ArrayList<>();

        private Builder() {
        }

        public Builder add(ValidationIssue issue) {
            if (issue != null) {
                issues.add(issue);
            }
            return this;
        }

        public Builder error(String path, String message) {
            return add(ValidationIssue.error(path, message));
        }

        public Builder warning(String path, String message) {
            return add(ValidationIssue.warning(path, message));
        }

        public Builder info(String path, String message) {
            return add(ValidationIssue.info(path, message));
        }

        public ValidationReport build() {
            return issues.isEmpty() ? OK : new ValidationReport(new ArrayList<>(issues));
        }
    }
}

