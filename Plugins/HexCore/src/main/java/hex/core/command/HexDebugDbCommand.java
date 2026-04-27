package hex.core.command;

import hex.core.api.HexApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Debug-only command to verify DB reads for coins/ranking without needing PlaceholderAPI.
 *
 * Usage: /hexdebugdb
 */
public final class HexDebugDbCommand implements CommandExecutor {

    private final HexApi api;

    public HexDebugDbCommand(HexApi api) {
        this.api = api;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players");
            return true;
        }

        var uuid = player.getUniqueId();

        int coins = api.coins().getCoins(uuid);
        int global = api.rankingPoints().getGlobalPoints(uuid);
        int season = api.rankingPoints().getSeasonPoints(uuid);

        sender.sendMessage("coins=" + coins + ", global_points=" + global + ", season_points=" + season);
        return true;
    }
}

