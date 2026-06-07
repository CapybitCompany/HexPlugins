package hex.minions.api;

import java.util.Map;
import java.util.UUID;

public record MinionMenuData(
        UUID id,
        String shortId,
        String typeId,
        String displayName,
        int tier,
        int maxTier,
        String world,
        int x,
        int y,
        int z,
        int storageUsed,
        int storageLimit,
        int storagePercent,
        int actionTimeSeconds,
        String state,
        boolean canUpgrade,
        String nextUpgradeRequirementsText,
        int menuSlotHint,
        int storageSlotsUnlocked,
        Map<String, Long> storage
) {
}

