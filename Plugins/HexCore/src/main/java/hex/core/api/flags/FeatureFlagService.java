package hex.core.api.flags;

/**
 * Provides runtime feature toggles with contextual overrides.
 * Used to enable/disable functionality dynamically.
 */
public interface FeatureFlagService {
    /** Returns whether a feature flag is enabled in the given context. */
    boolean isEnabled(String flagKey, FlagContext ctx, boolean defaultValue);

    /** Returns whether a feature flag is enabled (default false). */
    default boolean isEnabled(String flagKey, FlagContext ctx) {
        return isEnabled(flagKey, ctx, false);
    }
}
