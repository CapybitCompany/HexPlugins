package hex.core.api.config;

import java.util.Objects;

/**
 * Typed identifier for a configuration registered in ConfigService.
 * Combines a unique string id with the config class for type-safe access.
 */
public final class ConfigKey<T> {

    /** Unique string identifier of the config (e.g. "ui", "flags"). */
    private final String id;

    /** Java type the config is deserialized into. */
    private final Class<T> type;

    /**
     * Creates a new config key with a unique id and target config type.
     */
    public ConfigKey(String id, Class<T> type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Returns the unique config identifier. */
    public String id() { return id; }

    /** Returns the Java type of the config. */
    public Class<T> type() { return type; }

    /** Returns a readable representation of the key for logging/debugging. */
    @Override
    public String toString() {
        return "ConfigKey[" + id + ":" + type.getSimpleName() + "]";
    }

    /** Two keys are equal if both id and type match. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfigKey<?> that)) return false;
        return id.equals(that.id) && type.equals(that.type);
    }

    /** Hash based on id and type to ensure correct Map behavior. */
    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }
}
