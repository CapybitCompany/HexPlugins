package hex.core.command;

import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Administrative command used by shop/bot integrations.
 *
 * Usage:
 * - /hexcoinscache invalidate <playerName>
 * - /hexcoinscache refresh <playerName>
 */
public final class CoinsCacheCommand implements CommandExecutor, TabCompleter {

    private final HexApi api;

    public CoinsCacheCommand(HexApi api) {
        this.api = api;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " <invalidate|refresh> <playerName>");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String playerName = args[1];

        UUID uuid = resolveUuid(playerName);
        if (uuid == null) {
            sender.sendMessage("[HexCore] Nie znaleziono UUID dla gracza: " + playerName);
            return true;
        }

        switch (action) {
            case "invalidate" -> {
                api.statsCache().onCoinsChanged(uuid);
                sender.sendMessage("[HexCore] Coins cache invalidated for " + playerName + " (" + uuid + ")");
            }
            case "refresh" -> {
                int coins = api.statsCache().refreshCoins(uuid);
                sender.sendMessage("[HexCore] Coins cache refreshed for " + playerName + " (" + uuid + "), balance=" + coins);
            }
            default -> sender.sendMessage("Usage: /" + label + " <invalidate|refresh> <playerName>");
        }

        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            return prefix(List.of("invalidate", "refresh"), args[0]);
        }

        if (args.length == 2) {
            List<String> online = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                online.add(player.getName());
            }
            return prefix(online, args[1]);
        }

        return List.of();
    }

    private UUID resolveUuid(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online == null) {
            for (Player candidate : Bukkit.getOnlinePlayers()) {
                if (candidate.getName().equalsIgnoreCase(playerName)) {
                    online = candidate;
                    break;
                }
            }
        }

        if (online != null) {
            return online.getUniqueId();
        }

        return api.coins().findUuidByPlayerName(playerName).orElse(null);
    }

    private List<String> prefix(List<String> source, String current) {
        String lower = current == null ? "" : current.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }
}
