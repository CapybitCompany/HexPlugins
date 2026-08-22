package hex.minions.crafting;

import java.util.List;
import java.util.Locale;

public record MachineUpgradeDefinition(
        String id,
        String specialItemId,
        int extraBufferCapacity,
        double bufferCapacityMultiplier,
        double energyConsumptionMultiplier,
        double energyGenerationMultiplier,
        double energyTransferMultiplier,
        List<String> targetMachineTypes
) {
    public MachineUpgradeDefinition {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
        specialItemId = specialItemId == null ? "" : specialItemId.toLowerCase(Locale.ROOT);
        extraBufferCapacity = Math.max(0, extraBufferCapacity);
        bufferCapacityMultiplier = clamp(bufferCapacityMultiplier, 0.10D, 10.0D, 1.0D);
        energyConsumptionMultiplier = clamp(energyConsumptionMultiplier, 0.05D, 1.0D, 1.0D);
        energyGenerationMultiplier = clamp(energyGenerationMultiplier, 0.10D, 10.0D, 1.0D);
        energyTransferMultiplier = clamp(energyTransferMultiplier, 0.10D, 10.0D, 1.0D);
        targetMachineTypes = targetMachineTypes == null ? List.of() : targetMachineTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    public boolean supportsMachineType(String machineType) {
        if (targetMachineTypes.isEmpty()) return true;
        if (machineType == null || machineType.isBlank()) return false;
        return targetMachineTypes.contains(machineType.toUpperCase(Locale.ROOT));
    }
}
