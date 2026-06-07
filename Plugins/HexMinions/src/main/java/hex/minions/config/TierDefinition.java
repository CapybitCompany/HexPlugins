package hex.minions.config;

import java.util.Map;

public record TierDefinition(int tier, int actionTimeSeconds, int storage, int storageSlots, Map<String, Long> upgradeResources) {
}

