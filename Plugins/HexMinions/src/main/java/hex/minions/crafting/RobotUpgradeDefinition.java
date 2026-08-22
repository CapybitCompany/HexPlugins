package hex.minions.crafting;

import java.util.List;
import java.util.Locale;

/** Configurable upgrade usable by robot implementations. */
public record RobotUpgradeDefinition(
        String id,
        String specialItemId,
        double workIntervalMultiplier,
        double fuelDurationMultiplier,
        double pickaxeDamageSaveChance,
        List<String> targetRobotTypes
) {
    public RobotUpgradeDefinition {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
        specialItemId = specialItemId == null ? "" : specialItemId.toLowerCase(Locale.ROOT);
        workIntervalMultiplier = Math.max(0.10D, Math.min(4.0D, workIntervalMultiplier));
        fuelDurationMultiplier = Math.max(0.10D, Math.min(10.0D, fuelDurationMultiplier));
        pickaxeDamageSaveChance = Math.max(0.0D, Math.min(1.0D, pickaxeDamageSaveChance));
        targetRobotTypes = targetRobotTypes == null ? List.of() : targetRobotTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public boolean supportsRobotType(String robotType) {
        if (targetRobotTypes.isEmpty()) return true;
        if (robotType == null || robotType.isBlank()) return false;
        return targetRobotTypes.contains(robotType.toUpperCase(Locale.ROOT));
    }
}
