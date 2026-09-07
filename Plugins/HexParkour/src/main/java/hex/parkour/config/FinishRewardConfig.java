package hex.parkour.config;

import java.util.Map;

public record FinishRewardConfig(
        boolean enabled,
        String command,
        Map<String, ArenaReward> arenaRewards
) {
    public FinishRewardConfig {
        arenaRewards = Map.copyOf(arenaRewards);
    }

    public ArenaReward rewardFor(String arenaId) {
        return arenaId == null ? null : arenaRewards.get(arenaId);
    }

    public record ArenaReward(
            boolean enabled,
            long maxTimeMillis,
            int amount
    ) {
        public boolean qualifies(long timeMillis) {
            return enabled && amount > 0 && maxTimeMillis > 0L && timeMillis <= maxTimeMillis;
        }
    }
}
