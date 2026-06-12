package hex.minions.crafting;

import org.bukkit.Particle;

public record BoosterDefinition(
        int tier,
        String specialItemId,
        double speedBoostPercent,
        int durationSeconds,
        Particle particle,
        int particleCount,
        double particleRadius,
        double particleYOffset
) {
    public double actionTimeMultiplier() {
        return Math.max(0.05D, 1.0D - Math.max(0.0D, speedBoostPercent) / 100.0D);
    }
}
