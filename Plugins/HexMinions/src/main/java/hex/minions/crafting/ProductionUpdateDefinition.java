package hex.minions.crafting;

import java.util.List;
import java.util.Locale;

public record ProductionUpdateDefinition(
        String id,
        String specialItemId,
        double speedBoostPercent,
        List<String> targetCategories
) {
    public ProductionUpdateDefinition {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
        specialItemId = specialItemId == null ? "" : specialItemId.toLowerCase(Locale.ROOT);
        speedBoostPercent = Math.max(0.0D, speedBoostPercent);
        targetCategories = targetCategories == null ? List.of() : targetCategories.stream()
                .filter(category -> category != null && !category.isBlank())
                .map(category -> category.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public double actionTimeMultiplier() {
        return Math.max(0.05D, 1.0D - speedBoostPercent / 100.0D);
    }

    public boolean supportsCategory(String category) {
        if (targetCategories.isEmpty()) return true;
        if (category == null || category.isBlank()) return false;
        return targetCategories.contains(category.toLowerCase(Locale.ROOT));
    }
}
