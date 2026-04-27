package hex.core.api.config;

/**
 * Validates a config object before it is committed.
 * Prevents invalid configurations from being applied.
 */
@FunctionalInterface
public interface Validator<T> {
    /** Returns validation result containing possible errors. */
    ValidationResult validate(T config);
}
