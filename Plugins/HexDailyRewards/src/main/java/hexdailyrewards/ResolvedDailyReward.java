package hexdailyrewards;

import hexdailyrewards.config.DailyRewardsConfig;

public record ResolvedDailyReward(
        DailyRewardsConfig.RewardDefinition definition,
        int cycleDay,
        boolean dateOverride
) {
}

