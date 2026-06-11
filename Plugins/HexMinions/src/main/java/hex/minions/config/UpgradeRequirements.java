package hex.minions.config;

import java.util.List;
import java.util.Map;

public record UpgradeRequirements(
        Map<String, Long> collectionAmounts,
        List<ItemRequirement> items
) {
    public static UpgradeRequirements empty() {
        return new UpgradeRequirements(Map.of(), List.of());
    }

    public boolean emptyRequirements() {
        return collectionAmounts.isEmpty() && items.isEmpty();
    }
}
