package hexnpc.model;

public record InteractionSettings(
        boolean clickEnabled,
        boolean proximityEnabled,
        double proximityRadius,
        int proximityCooldownTicks
) {
    public InteractionSettings {
        proximityRadius = Math.max(0.5D, proximityRadius);
        proximityCooldownTicks = Math.max(0, proximityCooldownTicks);
    }

    public static InteractionSettings defaultClick() {
        return new InteractionSettings(true, false, 3.0D, 600);
    }

    public InteractionSettings withClick(boolean enabled) {
        return new InteractionSettings(enabled, proximityEnabled, proximityRadius, proximityCooldownTicks);
    }

    public InteractionSettings withProximity(boolean enabled, double radius, int cooldownTicks) {
        return new InteractionSettings(clickEnabled, enabled, radius, cooldownTicks);
    }
}
