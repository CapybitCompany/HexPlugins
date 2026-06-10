package hexnpc.model;

/**
 * Per-NPC interaction settings. proximityRadius and proximityCooldownTicks
 * use 0 (or negative) as a sentinel meaning "fall back to the global
 * default from config.yml at use time".
 */
public record InteractionSettings(
        boolean clickEnabled,
        boolean proximityEnabled,
        double proximityRadius,
        int proximityCooldownTicks
) {
    public InteractionSettings {
        proximityRadius = Math.max(0.0D, proximityRadius);
        proximityCooldownTicks = Math.max(0, proximityCooldownTicks);
    }

    /** Default interaction config used by newly created NPCs. 0 -> use global defaults. */
    public static InteractionSettings defaultClick() {
        return new InteractionSettings(true, false, 0.0D, 0);
    }

    public InteractionSettings withClick(boolean enabled) {
        return new InteractionSettings(enabled, proximityEnabled, proximityRadius, proximityCooldownTicks);
    }

    public InteractionSettings withProximity(boolean enabled, double radius, int cooldownTicks) {
        return new InteractionSettings(clickEnabled, enabled, radius, cooldownTicks);
    }

    /** Returns radius if explicitly set (>0), else the supplied global default. */
    public double effectiveRadius(double globalDefault) {
        return proximityRadius > 0.0D ? proximityRadius : globalDefault;
    }

    /** Returns cooldown if explicitly set (>0), else the supplied global default. */
    public int effectiveCooldownTicks(int globalDefault) {
        return proximityCooldownTicks > 0 ? proximityCooldownTicks : globalDefault;
    }
}
