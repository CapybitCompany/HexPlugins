package hex.minions.crafting;

import org.bukkit.Particle;

import java.util.List;
import java.util.Locale;

public record BoosterDefinition(
        int tier,
        String specialItemId,
        double speedBoostPercent,
        int durationSeconds,
        Particle particle,
        int particleCount,
        double particleRadius,
        double particleYOffset,
        List<String> targetCategories
) {
    public BoosterDefinition {
        targetCategories = targetCategories == null ? List.of() : targetCategories.stream()
                .filter(category -> category != null && !category.isBlank())
                .map(category -> category.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public double actionTimeMultiplier() {
        return Math.max(0.05D, 1.0D - Math.max(0.0D, speedBoostPercent) / 100.0D);
    }

    public boolean categoryRestricted() {
        return !targetCategories.isEmpty();
    }

    public boolean supportsCategory(String category) {
        if (targetCategories.isEmpty()) return true;
        if (category == null || category.isBlank()) return false;
        return targetCategories.contains(category.toLowerCase(Locale.ROOT));
    }
}
