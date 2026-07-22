package hexdailyrewards;

import hexdailyrewards.config.DailyRewardsConfig;

public record ResolvedDailyReward(
        DailyRewardsConfig.RewardGroup group,
        DailyRewardsConfig.RewardDefinition definition,
        int cycleDay,
        boolean dateOverride
) {
}
