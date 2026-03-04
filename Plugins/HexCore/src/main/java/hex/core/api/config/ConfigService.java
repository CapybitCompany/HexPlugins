package hex.core.api.config;

import java.util.Optional;

/**
 * Central registry responsible for loading, storing and reloading configurations.
 * Provides type-safe access to configs via ConfigKey.
 */
public interface ConfigService {
    /** Registers a config definition and loads it immediately. */
    <T> void register(ConfigSpec<T> spec);

    /** Returns the currently loaded config instance for the given key. */
    <T> T get(ConfigKey<T> key);

    /** Returns config if present without throwing exception. */
    <T> Optional<T> find(ConfigKey<T> key);

    /** Reloads config by string id. */
    ReloadResult reload(String id);

    /** Reloads config by typed key. */
    ReloadResult reload(ConfigKey<?> key);
}
