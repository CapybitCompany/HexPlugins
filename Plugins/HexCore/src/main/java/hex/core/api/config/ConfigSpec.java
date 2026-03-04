package hex.core.api.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Defines how a single configuration is loaded, validated and reloaded.
 * Combines key, file location, defaults, validator and reload policy.
 */
public final class ConfigSpec<T> {
    /** Unique typed key identifying this config. */
    private final ConfigKey<T> key;
    /** Physical file path of the config. */
    private final Path file;
    /** Supplies default config instance if file is missing. */
    private final Supplier<T> defaultSupplier;
    /** Validates the loaded config before it is applied. */
    private final Validator<T> validator;
    /** Determines whether and how this config can be reloaded. */
    private final ReloadPolicy reloadPolicy;

    public ConfigSpec(
            ConfigKey<T> key,
            Path file,
            Supplier<T> defaultSupplier,
            Validator<T> validator,
            ReloadPolicy reloadPolicy
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.file = Objects.requireNonNull(file, "file");
        this.defaultSupplier = Objects.requireNonNull(defaultSupplier, "defaultSupplier");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reloadPolicy = Objects.requireNonNull(reloadPolicy, "reloadPolicy");
    }

    public ConfigKey<T> key() { return key; }
    public Path file() { return file; }
    public Supplier<T> defaultSupplier() { return defaultSupplier; }
    public Validator<T> validator() { return validator; }
    public ReloadPolicy reloadPolicy() { return reloadPolicy; }
}
