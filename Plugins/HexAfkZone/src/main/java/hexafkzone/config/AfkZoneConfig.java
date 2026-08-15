package hexafkzone.config;

import org.bukkit.Location;

import java.util.List;

public record AfkZoneConfig(
        boolean enabled,
        Region region,
        Messages messages,
        int rewardMessageSeconds,
        Sounds sounds,
        List<RankProfile> rankProfiles,
        Rewards rewards
) {

    public record Region(String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public boolean contains(Location location) {
            if (location == null || location.getWorld() == null) {
                return false;
            }
            if (!location.getWorld().getName().equals(world)) {
                return false;
            }
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    public record Messages(
            String prefix,
            String noPermission,
            String usage,
            String reloadSuccess,
            String reloadFailed,
            String zoneSubtitle,
            String timerActionbar,
            String rewardActionbar
    ) {
        public String withPrefix(String message) {
            return prefix + message;
        }
    }

    public record Sounds(SoundSetting reward) {
    }

    public record SoundSetting(boolean enabled, String name, float volume, float pitch) {
    }

    public record RankProfile(
            String id,
            String displayName,
            String color,
            long rewardIntervalSeconds,
            int priority,
            boolean fallbackAccess,
            boolean operatorAccess,
            List<String> permissions
    ) {
    }

    public record Rewards(BaseReward base, List<ChanceReward> chanceRewards) {
    }

    public record BaseReward(
            String displayName,
            int amount,
            List<String> commands
    ) {
    }

    public record ChanceReward(
            String id,
            String displayName,
            double chancePercent,
            int amount,
            List<String> commands
    ) {
    }
}
