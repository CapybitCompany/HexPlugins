package hexdailyrewards.config;

import org.bukkit.Material;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public record DailyRewardsConfig(
        boolean enabled,
        ZoneId timeZone,
        HexNpc hexNpc,
        TimeFormat timeFormat,
        Messages messages,
        PlaceholderTexts placeholderTexts,
        Sounds sounds,
        Reward reward,
        RewardsCalendar rewardsCalendar,
        Map<String, RewardGroup> rewardGroups,
        Gui gui
) {

    public record HexNpc(boolean enabled, String actionId) {
    }

    public record TimeFormat(String now, String hour, String minute, String second,
                             String resetTimePattern, String datePattern) {
    }

    public record Messages(
            String prefix,
            String noPermission,
            String playerOnly,
            String usage,
            String reloadSuccess,
            String reloadFailed,
            String disabled,
            String rewardClaimedChat,
            String rewardClaimedActionbar,
            String alreadyClaimedActionbar,
            String rewardLockedActionbar,
            String claimError,
            String noRewardConfigured
    ) {
        public String withPrefix(String message) {
            return prefix + message;
        }
    }

    public record PlaceholderTexts(
            String noPlayer,
            String noReward,
            String available,
            String unavailable,
            String statusAvailable,
            String statusClaimed,
            String playerStatusAvailable,
            String playerStatusClaimed,
            String hologramStatusAvailable,
            String hologramStatusClaimed
    ) {
    }

    public record Sounds(SoundSetting open, SoundSetting claim, SoundSetting unavailable) {
    }

    public record SoundSetting(boolean enabled, String name, float volume, float pitch) {
    }

    public record Reward(boolean closeGuiAfterClaim, boolean refreshGuiAfterClaim) {
    }

    public record RewardsCalendar(
            LocalDate startDate,
            int cycleDays,
            Map<Integer, RewardDefinition> days,
            Map<LocalDate, RewardDefinition> dateOverrides
    ) {
    }

    public record RewardGroup(
            String id,
            boolean enabled,
            String displayName,
            List<String> ranks,
            List<String> permissions,
            int priority,
            boolean fallbackAccess,
            int slot,
            Material frameMaterial,
            List<Integer> frameColumns,
            String frameName,
            List<String> frameLore,
            boolean frameHideTooltip,
            RewardsCalendar rewardsCalendar
    ) {
    }

    public record RewardDefinition(
            String id,
            String displayName,
            Material material,
            List<String> lore,
            List<String> commands
    ) {
    }

    public record Gui(int size, String title, GuiItem filler, GuiItems items) {
    }

    public record GuiItems(
            GuiItem available,
            GuiItem claimed,
            GuiItem locked,
            GuiItem statusAvailable,
            GuiItem statusClaimed,
            GuiItem info,
            GuiItem close
    ) {
    }

    public record GuiItem(
            boolean enabled,
            int slot,
            Material material,
            boolean useRewardMaterial,
            String name,
            List<String> lore,
            boolean hideTooltip
    ) {
    }
}
