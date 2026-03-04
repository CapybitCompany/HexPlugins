package hex.core.api.config;

/**
 * Defines whether a config can be reloaded at runtime.
 */
public enum ReloadPolicy {
    /** Fully safe to reload at runtime. */
    HOT,

    /** Reloadable but may affect active logic. */
    SAFE,

    /** Requires full server restart to apply changes. */
    RESTART_REQUIRED
}