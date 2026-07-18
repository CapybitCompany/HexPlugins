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
        Sounds sounds,
        Reward reward,
        RewardsCalendar rewardsCalendar,
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
            String claimError,
            String noRewardConfigured
    ) {
        public String withPrefix(String message) {
            return prefix + message;
        }
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
