package hex.endevent.placeholder;

import hex.endevent.service.EndEventService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class EndEventPlaceholderExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final EndEventService service;

    public EndEventPlaceholderExpansion(Plugin plugin, EndEventService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override public @NotNull String getIdentifier() { return "hexendevent"; }
    @Override public @NotNull String getAuthor() { return String.join(", ", plugin.getDescription().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return resolve(params);
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return resolve(params);
    }

    private String resolve(String params) {
        String key = normalize(params).toLowerCase(Locale.ROOT);
        return switch (key) {
            case "next_open" -> service.nextOpenPlaceholder();
            case "next_open_date" -> service.nextOpenDate();
            case "next_open_time" -> service.nextOpenTime();
            case "next_open_relative" -> service.nextOpenRelative();
            case "is_open" -> String.valueOf(service.isOpen());
            case "status" -> service.statusText();
            case "time_remaining" -> service.remainingText();
            case "closes_at" -> service.closesAtText();
            default -> "";
        };
    }

    private static String normalize(String identifier) {
        if (identifier == null || identifier.isBlank()) return "";
        return identifier.startsWith(":") ? identifier.substring(1) : identifier;
    }
}
