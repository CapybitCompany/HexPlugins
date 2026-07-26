package hexmobplaceholder.integration;

import hexmobplaceholder.HexMobPlaceholderPlugin;
import hexmobplaceholder.MobKillCounter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;

public final class HexMobPlaceholderExpansion extends PlaceholderExpansion {

    private final HexMobPlaceholderPlugin plugin;

    public HexMobPlaceholderExpansion(HexMobPlaceholderPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hexmobplaceholder";
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
        if (plugin.counter() == null) {
            return "0";
        }

        return switch (params.toLowerCase(Locale.ROOT)) {
            case "hostile_kills", "hostile", "kills", "total" -> player == null
                    ? "0"
                    : String.valueOf(plugin.counter().totalKills(player));
            case "top_player", "top_name", "leader", "leader_name" -> plugin.counter()
                    .topPlayer(plugin.knownPlayers())
                    .map(MobKillCounter.TopPlayer::playerName)
                    .orElseGet(() -> plugin.config().placeholders().noTopPlayer());
            case "top_kills", "leader_kills" -> plugin.counter()
                    .topPlayer(plugin.knownPlayers())
                    .map(topPlayer -> String.valueOf(topPlayer.kills()))
                    .orElse("0");
            default -> null;
        };
    }
}
