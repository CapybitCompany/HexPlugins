package hex.minions.api;

import hex.minions.config.TierDefinition;

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
        double actionTimeSeconds,
        String state,
        boolean canUpgrade,
        String nextUpgradeRequirementsText,
        int menuSlotHint,
        int storageSlotsUnlocked,
        int activeBoosterTier,
        long boosterSecondsRemaining,
        int boosterDurationSeconds,
        int boosterItemsQueued,
        double boosterSpeedBoostPercent,
        String headMaterial,
        Map<String, Long> storage
) {
    public String actionTimeText() {
        return TierDefinition.formatSeconds(actionTimeSeconds);
    }
}

