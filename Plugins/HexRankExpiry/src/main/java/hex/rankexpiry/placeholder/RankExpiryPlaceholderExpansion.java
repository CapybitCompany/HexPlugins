package hex.rankexpiry.placeholder;

import hex.rankexpiry.config.RankExpirySettings;
import hex.rankexpiry.model.RankExpiry;
import hex.rankexpiry.service.RankExpiryService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class RankExpiryPlaceholderExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final RankExpiryService service;

    public RankExpiryPlaceholderExpansion(Plugin plugin, RankExpiryService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hexrankexpiry";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
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
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        RankExpirySettings settings = service.settings();
        RankExpiryService.RankLookup lookup = service.lookup(player.getUniqueId());
        String key = params.toLowerCase(Locale.ROOT);

        if (lookup.loading()) {
            return switch (key) {
                case "has_rank" -> "false";
                case "days", "seconds" -> settings.placeholderExpired();
                default -> settings.placeholderLoading();
            };
        }

        if (lookup.rank().isEmpty()) {
            return switch (key) {
                case "has_rank" -> "false";
                case "days", "seconds" -> settings.placeholderExpired();
                default -> settings.placeholderNoRank();
            };
        }

        RankExpiry rank = lookup.rank().get();
        long now = service.nowEpochSeconds();
        long days = rank.daysRemaining(now);
        long seconds = rank.secondsRemaining(now);

        return switch (key) {
            case "days" -> Long.toString(days);
            case "days_text", "text" -> days + " " + RankExpiryService.dayWord(days);
            case "seconds" -> Long.toString(seconds);
            case "rank", "display" -> rank.displayName();
            case "permission" -> rank.permission();
            case "expiry", "expiry_epoch" -> Long.toString(rank.expiryEpochSeconds());
            case "has_rank" -> "true";
            case "message" -> service.format(settings.placeholderMessage(), rank);
            default -> "";
        };
    }
}
