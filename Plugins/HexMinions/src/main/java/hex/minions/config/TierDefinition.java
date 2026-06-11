package hex.minions.config;

import java.util.Map;

public record TierDefinition(int tier, int actionTimeSeconds, int storage, int storageSlots, UpgradeRequirements upgradeRequirements) {
    /**
     * Backward-compatible accessor used by older menu/placeholder code.
     * These are now town collection requirements, not inventory resources.
     */
    public Map<String, Long> upgradeResources() {
        return upgradeRequirements.collectionAmounts();
    }
}
