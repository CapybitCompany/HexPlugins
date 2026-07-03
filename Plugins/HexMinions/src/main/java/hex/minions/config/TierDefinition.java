package hex.minions.config;

import java.util.Locale;
import java.util.Map;

public record TierDefinition(int tier, double actionTimeSeconds, int storage, int storageSlots, UpgradeRequirements upgradeRequirements) {

    public String actionTimeText() {
        return formatSeconds(actionTimeSeconds);
    }

    public static String formatSeconds(double seconds) {
        if (seconds < 45.0D) {
            return String.format(Locale.US, "%.1f", seconds);
        }
        return String.valueOf(Math.round(seconds));
    }
    /**
     * Backward-compatible accessor used by older menu/placeholder code.
     * These are now town collection requirements, not inventory resources.
     */
    public Map<String, Long> upgradeResources() {
        return upgradeRequirements.collectionAmounts();
    }
}
