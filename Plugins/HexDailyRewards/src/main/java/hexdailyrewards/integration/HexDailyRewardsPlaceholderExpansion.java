package hexdailyrewards.integration;

import hexdailyrewards.ClaimState;
import hexdailyrewards.DailyRewardService;
import hexdailyrewards.HexDailyRewardsPlugin;
import hexdailyrewards.config.DailyRewardsConfig;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class HexDailyRewardsPlaceholderExpansion extends PlaceholderExpansion {

    private final HexDailyRewardsPlugin plugin;

    public HexDailyRewardsPlaceholderExpansion(HexDailyRewardsPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hexdailyrewards";
    }

    @Override
    public @NotNull String getAuthor() {
        return "HexDevTeam";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        DailyRewardsConfig config = plugin.config();
        DailyRewardService service = plugin.rewardService();
        if (config == null || service == null) {
            return "";
        }
        if (player == null) {
            return config.placeholderTexts().noPlayer();
        }

        Player onlinePlayer = player.getPlayer();
        ClaimState state = onlinePlayer == null ? service.state(player.getUniqueId()) : service.state(onlinePlayer);
        Map<String, String> values = service.placeholders(player.getUniqueId(), player.getName(), state);
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "time", "remaining", "remaining_time", "next_time" -> values.get("time");
            case "hours", "remaining_hours" -> values.get("hours");
            case "minutes", "remaining_minutes" -> values.get("minutes");
            case "available", "can_claim" -> values.get("available");
            case "status" -> values.get("status");
            case "player_status", "claim_status", "colored_status" -> values.get("player_status");
            case "hologram", "hologram_line", "hologram_status" -> values.get("hologram_status");
            case "reward", "reward_name" -> values.get("reward_name");
            case "reward_day" -> values.get("reward_day");
            case "reward_id" -> values.get("reward_id");
            case "group", "group_id", "reward_group" -> values.get("group_id");
            case "group_name" -> values.get("group_name");
            case "group_ranks" -> values.get("group_ranks");
            case "reset_time" -> values.get("reset_time");
            default -> null;
        };
    }
}
